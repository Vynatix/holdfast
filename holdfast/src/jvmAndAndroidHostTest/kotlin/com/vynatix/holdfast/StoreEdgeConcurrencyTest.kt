@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RacedSource : Store<RacedSource>() {
    val a by state { 0 }
}

private class RacedHost : Store<RacedHost>()

/** The followers [store]'s store-level edges hold. */
private fun followersOf(store: Store<*>): Int = store.internalAttachment(STORE_EDGES)?.followerCount ?: 0

/**
 * Store-level derivation edges racing commits (issue #20 plan PR 15, D21):
 * adding an edge from one thread while another commits never loses a commit
 * — the add registers the edge, then queues a recompute that reads the store
 * after any commit whose fanout missed it — and edges added, removed and
 * dropped by `dispose()` from several threads leave no follower behind and
 * never hang. Every round runs under a watchdog, so a regression fails
 * instead of hanging, and every worker's failure fails the test.
 */
class StoreEdgeConcurrencyTest {
    @Test fun anEdgeAddedWhileTheStoreCommitsNeverMissesTheLastCommit() {
        val commits = 40
        completesWithin(120, "adding edges while their stores commit") {
            repeat(300) { round ->
                val source = RacedSource()
                val host = RacedHost()
                val node = host.derivedStateOverStores("raced") { source.a.value }
                val start = CyclicBarrier(2)
                val added = AtomicBoolean(false)
                val writer =
                    daemon("edge-writer-$round") {
                        start.await()
                        for (k in 1..commits) source action { a mutate k }
                    }
                val adder =
                    daemon("edge-adder-$round") {
                        start.await()
                        if (round % 2 == 0) Thread.yield()
                        added.set(node.addSourceStore(source))
                    }
                writer.join()
                adder.join()
                assertTrue(added.get(), "round $round: the edge was added")
                assertEquals(commits, node.value, "round $round: the node reflects the last commit")
                node.dispose()
                assertEquals(0, followersOf(source))
            }
        }
    }

    @Test fun edgesChurnedAcrossThreadsWhileFramesCommitLeaveNoFollowerBehind() {
        val left = RacedSource()
        val right = RacedSource()
        val host = RacedHost()
        val failures = ConcurrentLinkedQueue<Throwable>()
        listOf(left, right, host).forEach { store -> store.uncaughtObserverHandler = { failures += it } }
        val node = host.derivedStateOverStores("churned") { left.a.value + right.a.value }
        val done = AtomicBoolean(false)
        val start = CyclicBarrier(3)
        try {
            completesWithin(120, "edges churned while frames commit") {
                val writer =
                    daemon("frame-writer", failures) {
                        try {
                            start.await()
                            for (k in 1..3_000) {
                                atomic(left, right) {
                                    left { a mutate k }
                                    right { a mutate k }
                                }.getOrThrow()
                            }
                        } finally {
                            done.set(true)
                        }
                    }
                val churners =
                    List(2) { c ->
                        daemon("edge-churner-$c", failures) {
                            start.await()
                            val store = if (c == 0) left else right
                            while (!done.get()) {
                                node.addSourceStore(store)
                                node.removeSourceStore(store)
                            }
                        }
                    }
                writer.join()
                churners.forEach { it.join() }
            }
        } finally {
            // A watchdog timeout, too, stops the churners.
            done.set(true)
        }

        assertEquals(emptyList(), failures.toList(), "nothing failed or was reported")
        assertEquals(0, followersOf(left))
        assertEquals(0, followersOf(right))
        assertTrue(node.addSourceStore(left) && node.addSourceStore(right))
        assertEquals(6_000, node.value, "after the churn, the edges follow both stores again")
        left action { a mutate 1 }
        assertEquals(3_001, node.value)
        node.dispose()
        assertEquals(0, followersOf(left) + followersOf(right))
    }

    @Test fun addsAndRemovesOfTheSameStoreRacingLeaveItListedExactlyWhenFollowed() {
        val source = RacedSource()
        val host = RacedHost()
        val failures = ConcurrentLinkedQueue<Throwable>()
        listOf(source, host).forEach { store -> store.uncaughtObserverHandler = { failures += it } }
        val node = host.derivedStateOverStores("same-store") { source.a.value }
        val writing = AtomicBoolean(true)
        try {
            completesWithin(120, "adds and removes of one store racing while it commits") {
                val writer =
                    daemon("same-store-writer", failures) {
                        var k = 0
                        while (writing.get()) (source action { a mutate ++k }).getOrThrow()
                    }
                repeat(5_000) { round ->
                    // Each round starts followed or not, then one add and one
                    // remove of the same store race from a barrier.
                    if (round % 2 == 0) node.addSourceStore(source) else node.removeSourceStore(source)
                    val start = CyclicBarrier(2)
                    val remover = daemon("same-store-remover", failures) { start.await().also { node.removeSourceStore(source) } }
                    val adder = daemon("same-store-adder", failures) { start.await().also { node.addSourceStore(source) } }
                    remover.join()
                    adder.join()
                    val followers = followersOf(source)
                    assertTrue(followers <= 1, "round $round: the store's edges hold the follower once at most")
                    assertEquals(
                        followers == 1,
                        node.sourceStores.any { it === source },
                        "round $round: the node lists the store exactly when its edges hold the follower",
                    )
                }
                writing.set(false)
                writer.join()
            }
        } finally {
            writing.set(false)
        }

        assertEquals(emptyList(), failures.toList(), "nothing failed or was reported")
        node.addSourceStore(source)
        source action { a mutate -1 }
        assertEquals(-1, node.value, "a followed store's commit recomputes the node")
        node.dispose()
        assertEquals(0, followersOf(source), "a disposed node leaves no follower")
    }

    @Test fun storesAttachedAndDisposedOnSeveralThreadsLeaveNoneFollowed() {
        val anchor = RacedSource()
        val host = RacedHost()
        val failures = ConcurrentLinkedQueue<Throwable>()
        host.uncaughtObserverHandler = { failures += it }
        val node = host.derivedStateOverStores("branches", listOf(anchor)) { anchor.a.value }
        val start = CyclicBarrier(4)
        completesWithin(120, "stores attached and disposed on several threads") {
            val workers =
                List(4) { w ->
                    daemon("branch-worker-$w", failures) {
                        start.await()
                        repeat(250) { i ->
                            val branch = RacedSource()
                            check(node.addSourceStore(branch)) { "a live branch was not followed" }
                            // An Error result throws here, into [failures].
                            (branch action { a mutate i }).getOrThrow()
                            (anchor action { a mutate w * 1_000 + i }).getOrThrow()
                            branch.dispose()
                        }
                    }
                }
            workers.forEach { it.join() }
        }

        assertEquals(emptyList(), failures.toList())
        assertEquals(listOf<Store<*>>(anchor), node.sourceStores, "every disposed branch was dropped")
        assertEquals(1, followersOf(anchor))
        assertEquals(anchor.a.value, node.value, "the node settled on the anchor's last commit")
        node.dispose()
        assertEquals(0, followersOf(anchor))
    }
}
