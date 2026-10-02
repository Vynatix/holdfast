@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LkLeafStore : Store<LkLeafStore>() {
    val n by state { 0 }
}

private class LkKeyedStore(
    id: Int,
    root: LkRoot,
) : Store<LkKeyedStore>(root.keyed.at(id)) {
    val n by state { 0 }
}

private class LkRoot : Root("lk") {
    val a = LkLeafStore()
    val b = LkLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
    val keyed by keyed<Int, LkKeyedStore>()
}

/** Thread-safe: counts started and terminal hooks per transaction. */
private class CountingMiddleware : TreeMiddleware() {
    val started = ConcurrentHashMap<String, Int>()
    val terminal = ConcurrentHashMap<String, Int>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        started.merge(key(context), 1, Int::plus)
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        terminal.merge(key(context), 1, Int::plus)
    }

    override fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
        terminal.merge(key(context), 1, Int::plus)
    }

    private fun key(context: Middleware.MiddlewareContext<*>): String =
        "${context.store.lockOrderKey}/${context.transaction.id}@${context.transaction.hashCode()}"
}

private class Recording : TreeMiddleware() {
    val events = ConcurrentLinkedQueue<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "completed ${node.name}"
    }
}

/** The tree ring against concurrent leaf actions, creates and disposes: never a deadlock, never a lost terminal. */
class TreeMiddlewareLockTest {
    @Test
    fun installCompletesWhileALeafActionIsInFlightAndDoesNotRetroApply() =
        completesWithin(30, "install beside an in-flight leaf action") {
            val root = LkRoot()
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker =
                daemon("in-flight") {
                    root.a action {
                        inside.countDown()
                        release.await()
                    }
                }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val recording = Recording()
            root.middlewares(recording)
            assertEquals(emptyList<String>(), recording.events.toList(), "installed, without waiting for the action")
            release.countDown()
            worker.join()
            assertEquals(emptyList<String>(), recording.events.toList(), "the in-flight action's chain was snapshotted before")
            root.a action { }
            assertEquals(listOf("started a", "completed a"), recording.events.toList())
        }

    @Test
    fun removeDuringAParkedBlockingActionStillDeliversCompleted() =
        completesWithin(30, "remove beside a parked leaf action") {
            val root = LkRoot()
            val recording = Recording()
            root.middlewares(recording)
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker =
                daemon("parked") {
                    root.a action {
                        inside.countDown()
                        release.await()
                    }
                }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            assertTrue(root.removeMiddleware(recording))
            release.countDown()
            worker.join()
            assertEquals(listOf("started a", "completed a"), recording.events.toList())
            root.a action { }
            assertEquals(2, recording.events.size, "nothing new after the removal")
        }

    @Test
    fun concurrentCreateDisposeInstallAndRemoveLeaveTheRingConsistent() =
        completesWithin(120, "ring stress") {
            val root = LkRoot()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val stop = AtomicBoolean(false)
            val middlewares = List(3) { CountingMiddleware() }
            val churn =
                daemon("churn", failures) {
                    var i = 0
                    while (!stop.get()) {
                        val k = root.keyed.create(i) { LkKeyedStore(it, root) }
                        k action { n mutate 1 }
                        root.a action { n mutate i }
                        k.dispose()
                        i++
                    }
                }
            val staleAdapters = AtomicInteger()
            val installer =
                daemon("installer", failures) {
                    repeat(200) { round ->
                        val m = middlewares[round % middlewares.size]
                        root.middlewares(m)
                        root.b action { n mutate round }
                        val held = listOf(root.a, root.b).flatMap { root.treeMiddleware.adaptersOf(it) }.filter { it.middleware === m }
                        root.removeMiddleware(m)
                        // Once removal returned: its adapters are retired (no new observation starts) and off every ring.
                        if (held.any { !it.isRetired }) staleAdapters.incrementAndGet()
                        for (leaf in listOf(root.a, root.b)) {
                            if (root.treeMiddleware.adaptersOf(leaf).any { it.middleware === m }) staleAdapters.incrementAndGet()
                        }
                    }
                }
            installer.join()
            stop.set(true)
            churn.join()
            assertEquals(emptyList<Throwable>(), failures.toList())
            assertEquals(0, staleAdapters.get(), "a removed middleware's adapters are retired and off every ring")
            for (m in middlewares) {
                for ((key, count) in m.started) assertEquals(count, m.terminal[key], "every started has its terminal: $key")
            }
            assertEquals(2, root.treeMiddleware.memberCount, "the two branch leaves only")
            root.middlewares(middlewares[0], middlewares[1])
            assertEquals(listOf(middlewares[0], middlewares[1]), root.treeMiddleware.adaptersOf(root.a).map { it.middleware })
            assertEquals(root.treeMiddleware.installedMiddleware, root.treeMiddleware.adaptersOf(root.b).map { it.middleware })
        }

    @Test
    fun aKeyedStoreCreatedInsideAnotherLeafsActionAttachesWithoutDeadlock() =
        completesWithin(30, "create inside an action under the ring") {
            val root = LkRoot()
            val recording = Recording()
            root.middlewares(recording)
            var k: LkKeyedStore? = null
            root.a action { k = root.keyed.create(1) { LkKeyedStore(it, root) } }
            k!! action { n mutate 1 }
            assertEquals(listOf("started a", "completed a", "started 1", "completed 1"), recording.events.toList())
        }

    @Test
    fun disposeFromInsideItsOwnActionWhileInstallFansOutDoesNotDeadlock() =
        completesWithin(30, "dispose inside an action during install") {
            val root = LkRoot()
            val recording = Recording()
            val k = root.keyed.create(1) { LkKeyedStore(it, root) }
            val inside = CountDownLatch(1)
            val installed = CountDownLatch(1)
            val worker =
                daemon("self-dispose") {
                    k action {
                        inside.countDown()
                        installed.await(5, TimeUnit.SECONDS)
                        dispose()
                    }
                }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            root.middlewares(recording)
            installed.countDown()
            worker.join()
            assertTrue(k.isDisposed)
            assertEquals(2, root.treeMiddleware.memberCount)
            root.a action { }
            assertEquals(listOf("started a", "completed a"), recording.events.toList())
        }

    @Test
    fun aTreeHookCallingRootSnapshotFromUnderALeafLockDoesNotDeadlock() =
        completesWithin(30, "snapshot from a tree hook") {
            val root = LkRoot()
            var seen: Int? = null
            root.middlewares(
                object : TreeMiddleware() {
                    override fun onTransactionCompleted(
                        node: StoreNode,
                        context: Middleware.MiddlewareContext<*>,
                    ) {
                        seen = root.snapshot()[root.b.n]
                    }
                },
            )
            val other = daemon("other") { repeat(200) { root.b action { n mutate it } } }
            repeat(200) { root.a action { n mutate it } }
            other.join()
            assertTrue(seen != null)
        }
}
