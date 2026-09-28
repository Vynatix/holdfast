@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class HandoffSources : Store<HandoffSources>() {
    val a by state { 0 }
    val b by state { 0 }
}

private class HandoffDerivedHost : Store<HandoffDerivedHost>() {
    val y by state { 0 }
}

private class HandoffDraft : Store<HandoffDraft>() {
    val draft by state { "" }
    val server by state { "" }
}

/**
 * A serializer that parks its next blocking acquirer right after it takes the
 * permit: a store held with no transaction installed yet, as while a
 * coroutine just handed its mutex has not resumed.
 */
private class ParkNextSerializer : Store.AsyncSerializer {
    private val permit = Semaphore(1)
    private val parkNext = AtomicBoolean(true)
    val parked = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun blockingAcquire() {
        permit.acquire()
        if (parkNext.compareAndSet(true, false)) {
            parked.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    override fun blockingRelease() = permit.release()

    override fun tryBlockingAcquire(): Boolean = permit.tryAcquire()
}

/**
 * A [DerivedState] never makes a source commit wait for its host (issue #20,
 * R6, on PR 1's hand-off): a busy host gets the recompute handed to it, and
 * the derived state converges once the host's holder releases. Its sources
 * committing concurrently from several threads always converge to the
 * last committed values, without a deadlock.
 */
class DerivedStateHandoffTest {
    @Test
    fun `a source commit never waits for the busy host of a derived state`() {
        val sources = HandoffSources()
        val host = HandoffDerivedHost()
        val serializer = ParkNextSerializer()
        host.asyncSerializer = serializer
        val doubled = host.derivedState(sources.a) { sources.a.value * 2 }
        try {
            val hostAction = daemon("busy-host-action") { host action { y mutate 1 } }
            assertTrue(serializer.parked.await(10, TimeUnit.SECONDS), "the host action should hold the serializer")

            completesWithin(5, "a source commit while its derived state's host is busy") {
                sources action { a mutate 5 }
            }
            assertEquals(0, doubled.value, "the host is still busy, so the recompute waits for it")

            serializer.release.countDown()
            hostAction.join(10_000)
            assertFalse(hostAction.isAlive, "the host action should finish once released")
            assertEquals(10, doubled.value, "the host's holder ran the handed-off recompute once it released")
        } finally {
            serializer.release.countDown()
            doubled.dispose()
        }
    }

    @Test
    fun `a host parked in an action recomputes the derived state when that action ends`() {
        val sources = HandoffSources()
        val host = HandoffDerivedHost()
        val sum = host.derivedState(sources.a, sources.b) { sources.a.value + sources.b.value }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val hostAction =
                daemon("parked-host-action") {
                    host action {
                        entered.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        y mutate 1
                    }
                }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the host action should be running")

            completesWithin(5, "a source commit while its derived state's host is in an action") {
                sources action {
                    a mutate 3
                    b mutate 4
                }
            }
            assertEquals(0, sum.value)

            release.countDown()
            hostAction.join(10_000)
            assertFalse(hostAction.isAlive)
            assertEquals(7, sum.value)
        } finally {
            release.countDown()
            sum.dispose()
        }
    }

    /**
     * Stress: two threads commit to sources on two stores while a third keeps
     * the host busy; whatever interleaving, the derived state ends at the
     * value its compute gives for the last committed sources.
     */
    @Test
    fun `concurrent source commits on two stores converge without a deadlock`() {
        val left = HandoffSources()
        val right = HandoffSources()
        val host = HandoffDerivedHost()
        val sum = host.derivedState(left.a, right.a) { left.a.value + right.a.value }
        val rounds = 500
        val start = CyclicBarrier(3)
        try {
            completesWithin(60, "concurrent commits to a derived state's sources") {
                val writers =
                    listOf(
                        daemon("left-writer") {
                            start.await()
                            repeat(rounds) { i -> left action { a mutate i + 1 } }
                        },
                        daemon("right-writer") {
                            start.await()
                            repeat(rounds) { i -> right action { a mutate (i + 1) * 1000 } }
                        },
                        daemon("host-holder") {
                            start.await()
                            repeat(rounds) { i -> host action { y mutate i } }
                        },
                    )
                writers.forEach { it.join(55_000) }
                assertTrue(writers.none { it.isAlive }, "every writer should finish")
            }
            assertEquals(rounds + rounds * 1000, left.a.value + right.a.value)
            assertEquals(left.a.value + right.a.value, sum.value, "the derived state converged to the last commits")
        } finally {
            sum.dispose()
        }
    }

    /**
     * A source commit that lands while the initial value is being computed —
     * after the compute read the source — is recomputed once the derived state
     * is created, not lost: the derived state subscribes before it computes.
     */
    @Test
    fun `a source commit landing during the initial compute is recomputed`() {
        val sources = HandoffSources()
        val host = HandoffDerivedHost()
        val computed = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val writer =
            daemon("commit-during-initial-compute") {
                computed.await(10, TimeUnit.SECONDS)
                sources action { a mutate 1 }
                committed.countDown()
            }
        val doubled =
            host.derivedState(sources.a) {
                val read = sources.a.value
                computed.countDown()
                committed.await(10, TimeUnit.SECONDS)
                read * 2
            }
        try {
            writer.join(10_000)
            assertFalse(writer.isAlive)
            assertEquals(2, doubled.value, "the commit that landed during the initial compute was recomputed")
        } finally {
            doubled.dispose()
        }
    }

    /** The same race for a `merged` state created at runtime. */
    @Test
    fun `a remote commit landing during a merged state's initial merge is recomputed`() {
        val store = HandoffDraft()
        val merging = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val writer =
            daemon("adopt-during-initial-merge") {
                merging.await(10, TimeUnit.SECONDS)
                store action { server mutate "synced" }
                committed.countDown()
            }
        val shown =
            store.merged(store.draft, store.server) { d, s ->
                merging.countDown()
                committed.await(10, TimeUnit.SECONDS)
                d.ifEmpty { s }
            }
        try {
            writer.join(10_000)
            assertFalse(writer.isAlive)
            assertEquals("synced", shown.value)
        } finally {
            shown.dispose()
        }
    }

    /**
     * A source value that arrives outside a commit (`observeFrom`) recomputes
     * the derived state at once on its idle host, even while another thread
     * holds the source's store in an action.
     */
    @Test
    fun `an inbound source value recomputes at once while another thread holds the source store`() {
        val sources = HandoffSources()
        val host = HandoffDerivedHost()
        val doubled = host.derivedState(sources.a) { sources.a.value * 2 }
        var push: (Int) -> Unit = {}
        val inbound =
            sources {
                a observeFrom
                    Observable { observer ->
                        push = observer
                        Disposable { }
                    }
            }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder =
                daemon("source-store-holder") {
                    sources action {
                        entered.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the holder's action should be running")

            completesWithin(5, "an inbound source value while the source store is held") { push(5) }
            assertEquals(10, doubled.value, "recomputed on the idle host while the source store is still held")

            release.countDown()
            holder.join(10_000)
            assertFalse(holder.isAlive)
            assertEquals(10, doubled.value)
        } finally {
            release.countDown()
            inbound.dispose()
            doubled.dispose()
        }
    }
}
