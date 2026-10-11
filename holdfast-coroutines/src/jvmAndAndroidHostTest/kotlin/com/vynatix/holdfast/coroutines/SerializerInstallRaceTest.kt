@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.DerivedState
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.derivedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

private class Tally : Store<Tally>() {
    val count by state { 0 }

    /** Written after a suspension: a read there sees committed values only, so it gets a state of its own. */
    val marks by state { 0 }
}

/**
 * The first `suspendAction`, `suspendAtomic` or hydration decision on a store
 * installs its serializer. A blocking `action` or `atomic` (or a derived
 * recompute's top-level attempt) that read the serializer as not installed
 * yet runs under the store's transaction lock alone; a suspending entry
 * installs its transaction holding only the serializer. They used to overlap:
 * the suspending entry installed its transaction over the blocking one's, the
 * blocking body's writes staged into the suspending transaction, and the
 * blocking action's exit then cleared the suspending transaction — whose
 * body's next `mutate` opened a one-shot action that spun forever on the
 * serializer its own coroutine held (found by `HydrationSerializerContractTest`'s
 * thread dump).
 *
 * Now a suspending entry waits out such a holder, without holding a thread,
 * before it installs anything; and a blocking holder that read the serializer
 * as not installed re-reads it under the transaction lock and, if it was
 * installed meanwhile, takes it first. Every case is watchdogged: the old
 * failure was a spin, not a throw.
 *
 * Covered by reasoning only (`SerializerInstallWindow.kt`), not by a test:
 * the never-blocking `Store.tryTopLevelAction`'s re-check, which answers busy
 * when a serializer was installed between its unlocked read and its lock
 * probe. Nothing runs between those two reads, so no test can park an attempt
 * there without a seam in core; the racing derived recomputes below reach it
 * only by chance.
 */
class SerializerInstallRaceTest {
    @Test fun aFirstSuspendActionWaitsOutABlockingActionThatReadTheSerializerAsNotInstalled() =
        installWatchdog(20, "a first suspendAction behind a lock-only action") {
            val store = Tally()
            val holder = LockOnlyHolder(store) { body -> store.action { body(this) }.getOrThrow() }
            val bodyRan = AtomicBoolean(false)
            val suspending =
                worker("first-suspendAction") {
                    runBlocking(Dispatchers.Default) {
                        store.suspendAction {
                            bodyRan.set(true)
                            count update { it + 10 }
                            // A suspension, then a second write: the one that used to
                            // find its transaction cleared and spin.
                            yield()
                            marks update { it + 10 }
                        }
                    }.getOrThrow()
                }
            holder.assertWaitedOutBy(bodyRan)
            holder.finish()
            suspending.join()
            assertEquals(12, store.count.value, "both transactions committed, neither lost a write")
            assertEquals(10, store.marks.value, "the write after the suspension committed")
            assertNull(store.activeTransaction)
        }

    @Test fun aFirstSuspendAtomicWaitsOutAnAtomicThatReadTheSerializerAsNotInstalled() =
        installWatchdog(20, "a first suspendAtomic behind a lock-only atomic") {
            val store = Tally()
            val peer = Tally()
            val holder = LockOnlyHolder(store) { body -> atomic(store) { body(store) }.getOrThrow() }
            val bodyRan = AtomicBoolean(false)
            val suspending =
                worker("first-suspendAtomic") {
                    runBlocking(Dispatchers.Default) {
                        suspendAtomic(store, peer) {
                            bodyRan.set(true)
                            store { count update { it + 10 } }
                            yield()
                            store { marks update { it + 10 } }
                            peer { count update { it + 1 } }
                        }
                    }.getOrThrow()
                }
            holder.assertWaitedOutBy(bodyRan)
            holder.finish()
            suspending.join()
            assertEquals(12, store.count.value, "both frames committed, neither lost a write")
            assertEquals(10, store.marks.value, "the write after the suspension committed")
            assertEquals(1, peer.count.value)
            assertNull(store.activeTransaction)
            assertNull(peer.activeTransaction)
        }

    /**
     * Cancelled while it waits out a lock-only holder of its LATER participant
     * — [peer]'s root installed, [store]'s serializer held — a first
     * `suspendAtomic` uninstalls that root, releases both serializers and
     * leaves the holder's transaction alone; every later entry on both
     * stores still runs, and none of the cancelled frame's writes lands.
     */
    @Test fun aFirstSuspendAtomicCancelledWhileItWaitsOutALockOnlyHolderReleasesEveryParticipant() =
        installWatchdog(20, "a first suspendAtomic cancelled behind a lock-only action") {
            val peer = Tally()
            val store = Tally()
            assertTrue(peer.lockOrderKey < store.lockOrderKey, "peer is the frame's first participant")
            val holder = LockOnlyHolder(store) { body -> store.action { body(this) }.getOrThrow() }
            val bodyRan = AtomicBoolean(false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val frame =
                    scope.launch {
                        suspendAtomic(peer, store) {
                            bodyRan.set(true)
                            store { count update { it + 10 } }
                            peer { count update { it + 10 } }
                        }
                    }
                holder.assertWaitedOutBy(bodyRan)
                assertNotNull(peer.activeTransaction, "the frame installed its first participant's root")
                assertNotNull(peer.suspendingOwner)
                runBlocking { frame.cancelAndJoin() }
                assertSerializerFree(peer)
                assertSerializerFree(store)
                assertNull(peer.activeTransaction, "the cancelled frame uninstalled its root")
                assertNull(peer.suspendingOwner)
                holder.assertStillHolds()
                holder.finish()

                store.action { count update { it + 1 } }.getOrThrow()
                runBlocking {
                    store.suspendAction { count update { it + 1 } }.getOrThrow()
                    suspendAtomic(peer, store) {
                        store { count update { it + 1 } }
                        peer { count update { it + 1 } }
                    }.getOrThrow()
                }
                assertFalse(bodyRan.get(), "the cancelled frame never ran its body")
                assertEquals(5, store.count.value, "the holder's 2 and the later 3, none of the cancelled frame's")
                assertEquals(1, peer.count.value)
                listOf(peer, store).forEach { s ->
                    assertNull(s.activeTransaction)
                    assertNull(s.suspendingOwner)
                }
            } finally {
                scope.cancel()
            }
        }

    /**
     * Cancelled while it waits out a lock-only holder, a first
     * `suspendAction` releases the store's serializer and leaves the
     * holder's transaction alone; a later blocking `action` and a later
     * `suspendAction` both run.
     */
    @Test fun aFirstSuspendActionCancelledWhileItWaitsOutALockOnlyHolderReleasesTheStore() =
        installWatchdog(20, "a first suspendAction cancelled behind a lock-only action") {
            val store = Tally()
            val holder = LockOnlyHolder(store) { body -> store.action { body(this) }.getOrThrow() }
            val bodyRan = AtomicBoolean(false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val waiting =
                    scope.launch {
                        store.suspendAction {
                            bodyRan.set(true)
                            count update { it + 10 }
                        }
                    }
                holder.assertWaitedOutBy(bodyRan)
                runBlocking { waiting.cancelAndJoin() }
                assertSerializerFree(store)
                holder.assertStillHolds()
                holder.finish()

                store.action { count update { it + 1 } }.getOrThrow()
                runBlocking { store.suspendAction { count update { it + 1 } }.getOrThrow() }
                assertFalse(bodyRan.get(), "the cancelled suspendAction never ran its body")
                assertEquals(4, store.count.value, "the holder's 2 and the later 2, none of the cancelled call's")
                assertNull(store.activeTransaction)
                assertNull(store.suspendingOwner)
            } finally {
                scope.cancel()
            }
        }

    /**
     * Fresh stores every round, no serializer installed up front: blocking
     * actions, blocking frames and derived recomputes (top-level attempts on
     * the host) race the first `suspendAction` and `suspendAtomic` on the same
     * stores. Every commit lands, exactly once.
     */
    @Test fun firstSuspendingEntriesRacingBlockingHoldersLoseNoCommitAndNeverSpin() =
        installWatchdog(180, "first suspending entries racing blocking holders") {
            repeat(ROUNDS) { round -> raceOnFreshStores(round) }
        }

    private fun raceOnFreshStores(round: Int) {
        val store = Tally()
        val peer = Tally()
        val host = Tally()
        val mirror: DerivedState<Int> = host.derivedState(store.count) { store.count.value }
        val failures = ConcurrentLinkedQueue<Throwable>()
        listOf(store, peer, host).forEach { s -> s.uncaughtObserverHandler = { failures += it } }
        val start = CyclicBarrier(RACERS)
        val racers =
            listOf(
                racer("blocking-action", start, failures) {
                    repeat(OPS) { store.action { count update { it + 1 } }.getOrThrow() }
                },
                racer("blocking-atomic", start, failures) {
                    repeat(OPS) {
                        atomic(store, peer) {
                            store { count update { it + 1 } }
                            peer { count update { it + 1 } }
                        }.getOrThrow()
                    }
                },
                racer("suspendAction", start, failures) {
                    runBlocking(Dispatchers.Default) {
                        repeat(OPS) {
                            store
                                .suspendAction {
                                    count update { it + 1 }
                                    yield()
                                    marks update { it + 1 }
                                }.getOrThrow()
                            host.suspendAction { count update { it + 1 } }.getOrThrow()
                        }
                    }
                },
                racer("suspendAtomic", start, failures) {
                    runBlocking(Dispatchers.Default) {
                        repeat(OPS) {
                            suspendAtomic(peer, store) {
                                peer { count update { it + 1 } }
                                yield()
                                store { count update { it + 1 } }
                            }.getOrThrow()
                        }
                    }
                },
            )
        racers.forEach { it.join() }
        assertTrue(failures.isEmpty(), "round $round: ${failures.joinToString()}")
        // Per op: action 1, atomic 1, suspendAction 1, suspendAtomic 1.
        assertEquals(4 * OPS, store.count.value, "round $round: every write to store committed once")
        assertEquals(OPS, store.marks.value, "round $round: every write after a suspension committed once")
        assertEquals(2 * OPS, peer.count.value, "round $round: every write to peer committed once")
        assertEquals(OPS, host.count.value, "round $round: every write to host committed once")
        assertEquals(store.count.value, mirror.value, "round $round: the derived state settled on the last commit")
        listOf(store, peer, host).forEach { s ->
            assertNull(s.activeTransaction, "round $round: no transaction left installed")
            assertNull(s.suspendingOwner, "round $round: no suspending owner left installed")
        }
        mirror.dispose()
    }

    /**
     * A blocking holder of [store] that read its serializer as not installed:
     * [enter] runs a body on a thread of its own, which writes, parks until
     * [finish] and writes again, holding the transaction lock alone.
     */
    private class LockOnlyHolder(
        private val store: Tally,
        enter: (body: Tally.() -> Unit) -> Unit,
    ) {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val transaction = AtomicReference<Transaction?>()
        private val thread =
            worker("lock-only-holder") {
                enter {
                    count update { it + 1 }
                    transaction.set(activeTransaction)
                    entered.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    count update { it + 1 }
                }
            }

        init {
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the holder entered its body")
            assertNull(store.asyncSerializer, "the holder read the serializer as not installed")
        }

        /**
         * Once a suspending entry has installed the store's serializer and
         * taken it, give it [SETTLE_MS] to reach the store: it must leave the
         * holder's transaction installed and run no body, holding no thread
         * on the transaction lock.
         */
        fun assertWaitedOutBy(bodyRan: AtomicBoolean) {
            awaitSerializerHeld(store)
            Thread.sleep(SETTLE_MS)
            assertStillHolds()
            assertFalse(bodyRan.get(), "the suspending body waits for the holder")
            assertTrue(noThreadWaitsOnATransactionLock(), "the suspending entry waits without holding a thread")
        }

        /** The holder's transaction is still the store's, with no suspending owner over it. */
        fun assertStillHolds() {
            assertSame(transaction.get(), store.activeTransaction, "the holder's transaction is still the store's")
            assertNull(store.suspendingOwner, "no suspending owner installed over the holder")
        }

        fun finish() {
            release.countDown()
            thread.join()
        }
    }

    private companion object {
        const val ROUNDS = 200
        const val OPS = 10
        const val RACERS = 4
        const val SETTLE_MS = 100L
        const val WAIT_SECONDS = 10L

        /** Start [body] on a named daemon thread; what it throws fails the watchdogged test through [failures]. */
        fun racer(
            name: String,
            start: CyclicBarrier,
            failures: MutableCollection<Throwable>,
            body: () -> Unit,
        ): Thread =
            worker(name) {
                try {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    body()
                } catch (e: Throwable) {
                    failures += e
                }
            }

        fun worker(
            name: String,
            body: () -> Unit,
        ): Thread =
            Thread(body, name).apply {
                isDaemon = true
                start()
            }

        /** Wait until [store]'s serializer is installed and held. */
        fun awaitSerializerHeld(store: Store<*>) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while ((store.asyncSerializer as? MutexSerializer)?.mutex?.isLocked != true) {
                if (System.nanoTime() > deadline) fail("no suspending entry took the store's serializer")
                Thread.sleep(1)
            }
        }

        /** [store]'s serializer is installed and nobody holds it. */
        fun assertSerializerFree(store: Store<*>) {
            val serializer = assertNotNull(store.asyncSerializer as? MutexSerializer, "the serializer is installed")
            assertFalse(serializer.mutex.isLocked, "nobody holds the serializer")
        }

        /** Whether no thread is parked acquiring a store's transaction lock. */
        fun noThreadWaitsOnATransactionLock(): Boolean =
            Thread.getAllStackTraces().values.none { frames ->
                frames.any { it.className.endsWith("StoreLock") && it.methodName == "acquire" }
            }

        /**
         * Run [body] on a daemon worker and fail, with every thread's stack,
         * rather than hang if it does not finish within [seconds].
         */
        fun installWatchdog(
            seconds: Long,
            what: String,
            body: () -> Unit,
        ) {
            val done = CountDownLatch(1)
            val thrown = AtomicReference<Throwable?>(null)
            worker("install-race-probe") {
                try {
                    body()
                } catch (e: Throwable) {
                    thrown.set(e)
                } finally {
                    done.countDown()
                }
            }
            if (!done.await(seconds, TimeUnit.SECONDS)) {
                val dump =
                    Thread.getAllStackTraces().entries.joinToString("\n") { (thread, frames) ->
                        "\"${thread.name}\" ${thread.state}\n" + frames.joinToString("\n") { "    at $it" }
                    }
                fail("$what did not complete within ${seconds}s — deadlocked or spinning. Threads:\n$dump")
            }
            thrown.get()?.let { throw it }
        }
    }
}
