package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Two states always committed in lockstep by the storm writers. */
private class IsoPairStore : Store<IsoPairStore>() {
    val a by state { 0 }
    val b by state { 0 }
}

/** Single-state store for the rollback-invisibility and read-your-own-writes runs. */
private class IsoValueStore : Store<IsoValueStore>() {
    val v by state { 0 }
}

/**
 * Run [body] on a named daemon worker and fail — rather than hang — if it does
 * not finish within [seconds]. A transaction-kernel regression typically parks
 * a thread on the store's `transactionLock` forever; without the watchdog that
 * burns the module's 10-minute test-task cap before reporting anything.
 */
private fun isoCompletesWithin(
    seconds: Long,
    what: String,
    body: () -> Unit,
) {
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    val worker =
        Thread {
            try {
                body()
            } catch (e: Throwable) {
                thrown.set(e)
            } finally {
                done.countDown()
            }
        }
    worker.isDaemon = true
    worker.name = "action-isolation-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — a transaction-kernel thread is stuck")
    }
    thrown.get()?.let { throw it }
}

/** Named daemon thread whose failures land in [failures] instead of dying silently. */
private fun isoDaemon(
    name: String,
    failures: ConcurrentLinkedQueue<Throwable>,
    body: () -> Unit,
): Thread {
    val worker =
        Thread {
            try {
                body()
            } catch (e: Throwable) {
                failures.add(e)
            }
        }
    worker.isDaemon = true
    worker.name = name
    return worker
}

/** Surface the first captured worker failure with its original stack as the cause. */
private fun isoRethrowFirst(failures: ConcurrentLinkedQueue<Throwable>) {
    val first = failures.peek() ?: return
    throw AssertionError("${failures.size} worker thread(s) failed; first: $first", first)
}

/**
 * Real-thread storms over the blocking `action` path, pinning the transaction
 * kernel's isolation and atomicity contracts as the current code makes them:
 * lockstep multi-state commits drift-bounded by the single in-flight apply,
 * rollback invisibility, owner-thread read-your-own-writes against
 * committed-only reads elsewhere, and snapshot/restore semantics under
 * concurrent commits.
 *
 * Coordination is via latches and barriers, never sleeps; every test runs
 * under a daemon-thread watchdog so a regression that parks a thread fails
 * fast instead of eating the whole test task.
 */
class ActionIsolationStressTest {
    /**
     * Writers commit `a` and `b` in lockstep (both incremented inside one
     * action); commits are serialized by the store's `transactionLock` and the
     * apply pass writes pending states in stage order, `a` before `b`.
     *
     * Readers cannot sample the pair atomically — each state is applied under
     * its own per-state lock, so a reader between the two applies legitimately
     * sees `a` one ahead of `b`. What IS guaranteed, and pinned here with an
     * a-b-a read protocol: whenever two reads of `a` bracket a read of `b` and
     * return the same value k, the `b` read was in {k - 1, k}. Anything else
     * means a torn commit, a leaked pending write, or an apply-order
     * regression. Exact conservation of both counters holds after the storm.
     */
    @Test
    fun `lockstep pair commits never drift beyond the in-flight apply window for racing readers`() =
        isoCompletesWithin(25, "invariant-pair storm") {
            val store = IsoPairStore()
            assertEquals(0, store.a.value, "delegate for a must be registered before the race")
            assertEquals(0, store.b.value, "delegate for b must be registered before the race")

            val writers = 4
            val iterations = 500
            val failures = ConcurrentLinkedQueue<Throwable>()
            val violations = ConcurrentLinkedQueue<String>()
            val done = AtomicBoolean(false)
            val start = CyclicBarrier(writers + 2)

            val writerThreads =
                List(writers) { w ->
                    isoDaemon("iso-pair-writer-$w", failures) {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(iterations) {
                            val result =
                                store action {
                                    a update { it + 1 }
                                    b update { it + 1 }
                                }
                            if (result is TransactionResult.Error) {
                                violations.add("writer action failed: ${result.exception}")
                            }
                        }
                    }
                }
            val readerThreads =
                List(2) { r ->
                    isoDaemon("iso-pair-reader-$r", failures) {
                        start.await(10, TimeUnit.SECONDS)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        var lastA = 0
                        while (!done.get() && System.nanoTime() < deadline) {
                            val a1 = store.a.value
                            val mid = store.b.value
                            val a2 = store.a.value
                            if (a1 == a2 && (mid < a1 - 1 || mid > a1) && violations.size < 50) {
                                violations.add("stable a=$a1 but b=$mid (allowed ${a1 - 1}..$a1)")
                            }
                            if (a1 < lastA && violations.size < 50) {
                                violations.add("a went backwards: $lastA -> $a1")
                            }
                            lastA = a2
                        }
                    }
                }

            (writerThreads + readerThreads).forEach { it.start() }
            writerThreads.forEach { it.join() }
            done.set(true)
            readerThreads.forEach { it.join() }
            isoRethrowFirst(failures)

            assertTrue(violations.isEmpty(), "isolation violations: ${violations.toList().take(5)}")
            assertEquals(writers * iterations, store.a.value, "every increment of a must be conserved")
            assertEquals(writers * iterations, store.b.value, "every increment of b must be conserved")
        }

    /**
     * Writers alternate committing unique positive values with staging a
     * negative sentinel and throwing. Rollback only clears the transaction's
     * pending buffer — it never applies — so no reader on any thread may ever
     * observe a negative value; every throwing body must surface as
     * [TransactionResult.Error] and every clean body as Success, exactly.
     */
    @Test
    fun `rolled-back sentinel writes are never observable and error accounting is exact`() =
        isoCompletesWithin(20, "rollback-invisibility storm") {
            val store = IsoValueStore()
            assertEquals(0, store.v.value, "delegate for v must be registered before the race")

            val writers = 4
            val iterations = 400
            val failures = ConcurrentLinkedQueue<Throwable>()
            val negatives = ConcurrentLinkedQueue<Int>()
            val successes = AtomicInteger(0)
            val rollbacks = AtomicInteger(0)
            val unexpected = AtomicInteger(0)
            val done = AtomicBoolean(false)
            val start = CyclicBarrier(writers + 2)

            val writerThreads =
                List(writers) { w ->
                    isoDaemon("iso-rollback-writer-$w", failures) {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(iterations) { i ->
                            val value = w * 100_000 + i + 1
                            if (i % 2 == 0) {
                                val result = store action { v mutate value }
                                if (result is TransactionResult.Success<*>) {
                                    successes.incrementAndGet()
                                } else {
                                    unexpected.incrementAndGet()
                                }
                            } else {
                                val result =
                                    store action {
                                        v mutate -value
                                        error("deliberate rollback of $value")
                                    }
                                if (result is TransactionResult.Error) {
                                    rollbacks.incrementAndGet()
                                } else {
                                    unexpected.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            val readerThreads =
                List(2) { r ->
                    isoDaemon("iso-rollback-reader-$r", failures) {
                        start.await(10, TimeUnit.SECONDS)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (!done.get() && System.nanoTime() < deadline) {
                            val seen = store.v.value
                            if (seen < 0 && negatives.size < 100) negatives.add(seen)
                        }
                    }
                }

            (writerThreads + readerThreads).forEach { it.start() }
            writerThreads.forEach { it.join() }
            done.set(true)
            readerThreads.forEach { it.join() }
            isoRethrowFirst(failures)

            assertTrue(negatives.isEmpty(), "readers observed rolled-back sentinels: ${negatives.toList().take(5)}")
            assertEquals(0, unexpected.get(), "result types must match body outcome exactly")
            assertEquals(writers * iterations / 2, successes.get(), "committed-action count must be exact")
            assertEquals(writers * iterations / 2, rollbacks.get(), "rolled-back-action count must be exact")
            assertNull(store.activeTransaction, "activeTransaction must be null once the storm is over")

            val sentinel = store action { v mutate 7 }
            assertIs<TransactionResult.Success<*>>(sentinel, "store must still accept a commit after the storm")
            assertEquals(7, store.v.value, "the post-storm sentinel commit must be visible")
        }

    /**
     * Read-your-own-writes against isolation, deterministically interleaved:
     * the owner thread stages a write and must see it immediately, while a
     * reader thread sampling strictly inside the staged-but-uncommitted window
     * (fenced by latches on both sides) must see only the previously committed
     * value. After commit the new value must be visible everywhere.
     *
     * The in-body latch wait is safe: the reader only READS (per-state lock,
     * never the store's `transactionLock`), so it cannot be blocked by the
     * action that is waiting on it.
     */
    @Test
    fun `owner thread reads its own staged write while other threads read the committed value`() =
        isoCompletesWithin(15, "read-your-own-writes handshake") {
            val store = IsoValueStore()
            store action { v mutate 1 }
            assertEquals(1, store.v.value)

            val failures = ConcurrentLinkedQueue<Throwable>()
            val staged = CountDownLatch(1)
            val samplingDone = CountDownLatch(1)
            val samples = ConcurrentLinkedQueue<Int>()

            val reader =
                isoDaemon("iso-ryow-reader", failures) {
                    check(staged.await(10, TimeUnit.SECONDS)) { "writer never staged its value" }
                    repeat(200) { samples.add(store.v.value) }
                    samplingDone.countDown()
                }
            reader.start()

            var ownerView = -1
            var samplingFinishedBeforeCommit = false
            val result =
                store action {
                    v mutate 2
                    ownerView = v.value
                    staged.countDown()
                    samplingFinishedBeforeCommit = samplingDone.await(10, TimeUnit.SECONDS)
                }
            reader.join()
            isoRethrowFirst(failures)

            assertTrue(samplingFinishedBeforeCommit, "reader did not finish sampling inside the pending window")
            assertIs<TransactionResult.Success<*>>(result, "the staged action must commit")
            assertEquals(2, ownerView, "owner thread must read its own staged write before commit")
            assertEquals(200, samples.size, "reader must have completed every sample")
            assertTrue(
                samples.all { it == 1 },
                "reader observed uncommitted values: ${samples.filter { it != 1 }.distinct()}",
            )
            assertEquals(2, store.v.value, "the staged write must be visible everywhere after commit")
        }

    /**
     * `snapshot()` reads each state's raw committed value under per-state locks
     * only — never the store's `transactionLock` (Snapshot.kt) — so this pins
     * exactly what that code guarantees and no more:
     *
     *  - staged-but-uncommitted writes are invisible to a concurrent snapshot
     *    (pending values live in the transaction buffer, not in the state);
     *  - under a lockstep-increment storm a snapshot's `b` may lag its `a` by
     *    at most the one commit whose apply is in flight (apply order follows
     *    stage order: `a` before `b`; snapshot iterates registration order:
     *    `a` first), and may legitimately exceed `a` when commits land between
     *    the two per-state reads — strict a == b equality mid-storm is NOT
     *    part of the current contract and is deliberately not asserted;
     *  - a quiesced snapshot is exact, and `restore()` round-trips it in one
     *    atomic action.
     */
    @Test
    fun `snapshots skip pending writes, lag at most one apply mid-storm, and restore round-trips exactly`() =
        isoCompletesWithin(25, "snapshot-restore storm") {
            val store = IsoPairStore()
            assertEquals(0, store.a.value, "register a first so snapshot iterates it first")
            assertEquals(0, store.b.value, "register b second")
            store action {
                a mutate 5
                b mutate 5
            }

            val failures = ConcurrentLinkedQueue<Throwable>()

            // Phase 1: a snapshot taken while another thread's action holds staged
            // writes must contain only committed values.
            val stagedLatch = CountDownLatch(1)
            val releaseLatch = CountDownLatch(1)
            val stager =
                isoDaemon("iso-snap-stager", failures) {
                    val result =
                        store action {
                            a mutate 999_999
                            b mutate 999_999
                            stagedLatch.countDown()
                            // Satisfied by the main thread, which only snapshots
                            // (no action), so this wait cannot deadlock.
                            check(releaseLatch.await(10, TimeUnit.SECONDS)) { "stager was never released" }
                        }
                    check(result is TransactionResult.Success<*>) { "stager action failed: $result" }
                }
            stager.start()
            assertTrue(stagedLatch.await(10, TimeUnit.SECONDS), "stager never signalled its staged writes")
            val duringPending = store.snapshot()
            releaseLatch.countDown()
            stager.join()
            isoRethrowFirst(failures)
            assertEquals(5, duringPending.rawValues["a"], "snapshot must not capture a staged (uncommitted) a")
            assertEquals(5, duringPending.rawValues["b"], "snapshot must not capture a staged (uncommitted) b")
            val afterCommit = store.snapshot()
            assertEquals(999_999, afterCommit.rawValues["a"], "snapshot after commit must see the committed a")
            assertEquals(999_999, afterCommit.rawValues["b"], "snapshot after commit must see the committed b")

            // Phase 2: snapshot storm against lockstep increment commits.
            store action {
                a mutate 0
                b mutate 0
            }
            val writers = 4
            val iterations = 400
            val total = writers * iterations
            val violations = ConcurrentLinkedQueue<String>()
            val done = AtomicBoolean(false)
            val start = CyclicBarrier(writers + 2)

            val writerThreads =
                List(writers) { w ->
                    isoDaemon("iso-snap-writer-$w", failures) {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(iterations) {
                            val result =
                                store action {
                                    a update { it + 1 }
                                    b update { it + 1 }
                                }
                            if (result is TransactionResult.Error) {
                                violations.add("writer action failed: ${result.exception}")
                            }
                        }
                    }
                }
            val snapshotThreads =
                List(2) { s ->
                    isoDaemon("iso-snapshotter-$s", failures) {
                        start.await(10, TimeUnit.SECONDS)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (!done.get() && System.nanoTime() < deadline) {
                            val snap = store.snapshot()
                            val aRaw = snap.rawValues["a"] as Int
                            val bRaw = snap.rawValues["b"] as Int
                            if ((aRaw !in 0..total || bRaw !in 0..total) && violations.size < 50) {
                                violations.add("snapshot captured a phantom value: a=$aRaw b=$bRaw")
                            }
                            if (bRaw < aRaw - 1 && violations.size < 50) {
                                violations.add("snapshot tore beyond the apply window: a=$aRaw b=$bRaw")
                            }
                        }
                    }
                }

            (writerThreads + snapshotThreads).forEach { it.start() }
            writerThreads.forEach { it.join() }
            done.set(true)
            snapshotThreads.forEach { it.join() }
            isoRethrowFirst(failures)
            assertTrue(violations.isEmpty(), "snapshot violations: ${violations.toList().take(5)}")

            // Phase 3: quiesced snapshot is exact, and restore round-trips it.
            val settled = store.snapshot()
            assertEquals(total, settled.rawValues["a"], "quiesced snapshot must hold the exact final a")
            assertEquals(total, settled.rawValues["b"], "quiesced snapshot must hold the exact final b")

            store action {
                a mutate total + 17
                b mutate total + 17
            }
            val restored = store.restore(settled)
            assertIs<TransactionResult.Success<*>>(restored, "restore of a consistent snapshot must commit")
            assertEquals(total, store.a.value, "restore must return a to the snapshot value")
            assertEquals(total, store.b.value, "restore must return b to the snapshot value")
        }
}
