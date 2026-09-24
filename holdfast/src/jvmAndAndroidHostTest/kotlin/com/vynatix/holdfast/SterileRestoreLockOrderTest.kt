@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #20, R3: a sterile restore re-runs Remote initializers inside its
// action, under its store's locks. An initializer that reads another store's
// state must not deadlock against frames that hold both stores, nor against a
// sterile restore of the other store whose Remote initializer reads back.

/** [feed]'s initializer reads [OrderPeer.x]: a cross-store read under the restore's locks. */
private class OrderHome : Store<OrderHome>() {
    lateinit var peer: OrderPeer
    val pins by state(tags = setOf(StateTag.UserAuthored)) { 0 }
    val feed by state(tags = setOf(StateTag.Remote)) { "peer=${peer.x.value}" }
}

/** [echo]'s initializer reads [OrderHome.pins]: the reverse edge. */
private class OrderPeer : Store<OrderPeer>() {
    lateinit var home: OrderHome
    val x by state { 0 }
    val echo by state(tags = setOf(StateTag.Remote)) { home.pins.value }
}

class SterileRestoreLockOrderTest {
    private val rounds = 1500

    @Test fun sterileRestoresNeverDeadlockAgainstFramesAndEachOther() {
        val home = OrderHome()
        val peer = OrderPeer()
        home.peer = peer
        peer.home = home
        home.feed.value
        peer.echo.value
        // A derived hosted on `home` whose source lives on `peer`: its recompute
        // is handed between the two stores while frames and restores run.
        val (mirrored, disposable) = home.derived(peer.x) { peer.x.value * 2 }
        val homeSnapshot = home.snapshot()
        val peerSnapshot = peer.snapshot()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CyclicBarrier(4)

        fun worker(
            name: String,
            body: (Int) -> TransactionResult<*>,
        ): Thread =
            daemon(name) {
                runCatching {
                    start.await(10, TimeUnit.SECONDS)
                    repeat(rounds) { i -> body(i).getOrThrow() }
                }.onFailure { failures += it }
            }

        completesWithin(60, "sterile restores racing frames on two stores") {
            val threads =
                listOf(
                    worker("sterile-home") { home.restore(homeSnapshot, RestorePolicy.BestEffort, sterile = true) },
                    worker("sterile-peer") { peer.restore(peerSnapshot, RestorePolicy.BestEffort, sterile = true) },
                    worker("frame") { i ->
                        atomic(peer, home) {
                            home { pins mutate i }
                            peer { x mutate i }
                        }
                    },
                    worker("actions") { i ->
                        home.snapshot()
                        peer action { x mutate -i }
                    },
                )
            threads.forEach { it.join() }
        }
        try {
            assertTrue(failures.isEmpty(), "every restore, frame and action succeeded: ${failures.toList()}")
            home.restore(homeSnapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
            assertEquals("peer=${peer.x.value}", home.feed.value, "a final sterile restore resets feed from the peer")
            assertEquals(peer.x.value * 2, mirrored.value, "the cross-store derived converged")
        } finally {
            disposable.dispose()
        }
    }
}
