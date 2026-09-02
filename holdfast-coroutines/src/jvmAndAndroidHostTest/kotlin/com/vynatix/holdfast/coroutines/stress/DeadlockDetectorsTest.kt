package com.vynatix.holdfast.coroutines.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.effect
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/** Store A of an ABBA pair. Constructed first in every attempt, so its `lockOrderKey` sorts below [DdStoreB]'s. */
private class DdStoreA : Store<DdStoreA>() {
    val x by state { 0 }
}

/** Store B of an ABBA pair. */
private class DdStoreB : Store<DdStoreB>() {
    val y by state { 0 }
}

private class DdCounterStore : Store<DdCounterStore>() {
    val count by state { 0 }
}

/**
 * Outcome of one deadlock attempt: [WEDGED] when every party sat parked for
 * the whole trailing window (the cycle formed), [COMPLETED] when every party
 * finished inside the completion window (no cycle).
 */
private enum class DdAttempt {
    WEDGED,
    COMPLETED,
}

/** Fresh store pair per attempt; the first wedged attempt short-circuits the loop. */
private const val DD_ATTEMPTS = 20

/** How long the parties get to finish before an attempt is examined for a wedge. */
private const val DD_COMPLETION_WINDOW_MS = 10_000L

/** Thread-state sampling period while waiting out the completion window. */
private const val DD_SAMPLE_MS = 250L

/** Minimum trailing stretch of all-parked samples that counts as a confirmed deadlock. */
private const val DD_PARKED_WINDOW_MS = 2_000L

/** In-cycle rendezvous bound — a party whose peer never shows up proceeds alone. */
private const val DD_RENDEZVOUS_S = 5L

/** Bound on the wait for every party to arm; covers a fully timed-out rendezvous with slack. */
private const val DD_ARM_WINDOW_S = 20L

/** Unstarted named daemon thread — daemon so a wedged party can never keep the test JVM alive. */
private fun ddThread(
    name: String,
    body: () -> Unit,
): Thread =
    Thread { body() }.apply {
        this.name = name
        isDaemon = true
    }

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. Same shape as `SerializerContractTest`'s watchdog.
 * Used by the `@Ignore`d reproducers only: their defects present as a thread
 * spinning in `RUNNABLE` or parked forever, either of which would otherwise
 * burn the module's 10-minute test-task cap before reporting anything.
 */
private fun ddCompletesWithin(
    seconds: Long,
    what: String,
    body: () -> Unit,
) {
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    val worker =
        ddThread("dd-reproducer-probe") {
            try {
                body()
            } catch (e: Throwable) {
                thrown.set(e)
            } finally {
                done.countDown()
            }
        }
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — the open defect still hangs this shape")
    }
    thrown.get()?.let { throw it }
}

/** Arrive at [barrier]; a timeout or a broken barrier is tolerated — the party simply proceeds alone. */
private fun ddRendezvous(barrier: CyclicBarrier) {
    runCatching { barrier.await(DD_RENDEZVOUS_S, TimeUnit.SECONDS) }
}

/**
 * Whether [state] is a parked (not spinning) state. A `StoreLock` waiter parks
 * in `SynchronousMutex`, which on the JVM wraps a `ReentrantLock`, so it shows
 * as `WAITING`; `BLOCKED` and `TIMED_WAITING` are accepted for robustness.
 */
private fun ddIsParked(state: Thread.State): Boolean =
    when (state) {
        Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED -> true
        else -> false
    }

/** Name, state and stack of every party — the diagnostic for an attempt that neither finished nor parked. */
private fun ddDescribe(threads: List<Thread>): String =
    threads.joinToString(separator = "\n") { t ->
        val frames = t.stackTrace.joinToString(separator = "\n") { "    at $it" }
        "${t.name}: ${t.state}\n$frames"
    }

/**
 * Start [threads] and classify the attempt.
 *
 * Every party must first ARM — bump [armed] right before taking the lock that
 * closes the cycle, i.e. after its rendezvous and while already holding its own
 * lock. From then on the parties get [DD_COMPLETION_WINDOW_MS] to finish
 * ([done] reaches zero) while their states are sampled every [DD_SAMPLE_MS].
 * An attempt that neither finished nor spent the trailing [DD_PARKED_WINDOW_MS]
 * fully parked (a party spinning in `RUNNABLE`, say) is an anomaly and fails
 * with a full dump rather than being classified either way.
 */
private fun ddObserveAttempt(
    what: String,
    threads: List<Thread>,
    armed: AtomicInteger,
    done: CountDownLatch,
): DdAttempt {
    threads.forEach { it.start() }
    val armDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DD_ARM_WINDOW_S)
    while (armed.get() < threads.size) {
        if (done.count == 0L) return DdAttempt.COMPLETED
        if (System.nanoTime() > armDeadline) {
            fail("$what: parties never armed within ${DD_ARM_WINDOW_S}s\n${ddDescribe(threads)}")
        }
        Thread.sleep(10)
    }
    var parkedStreakMs = 0L
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DD_COMPLETION_WINDOW_MS)
    while (System.nanoTime() < deadline) {
        if (done.await(DD_SAMPLE_MS, TimeUnit.MILLISECONDS)) return DdAttempt.COMPLETED
        parkedStreakMs = if (threads.all { ddIsParked(it.state) }) parkedStreakMs + DD_SAMPLE_MS else 0L
    }
    if (done.count == 0L) return DdAttempt.COMPLETED
    if (parkedStreakMs >= DD_PARKED_WINDOW_MS) return DdAttempt.WEDGED
    fail(
        "$what: parties neither finished within ${DD_COMPLETION_WINDOW_MS}ms nor stayed parked for " +
            "${DD_PARKED_WINDOW_MS}ms — a spin rather than a park?\n${ddDescribe(threads)}",
    )
}

/**
 * One attempt at the observer-write ABBA. A fresh pair per attempt; a wedged
 * pair, together with its two parked daemon threads, is abandoned and never
 * reused. Both observers rendezvous on a barrier BEFORE crossing over, so each
 * is guaranteed to be inside its own store's fanout — holding that store's
 * `transactionLock` — when it takes the other store's lock.
 */
private fun ddObserverWriteAttempt(attempt: Int): DdAttempt {
    val a = DdStoreA()
    val b = DdStoreB()
    assertEquals(0, a.x.value) // register both states before any cross-thread traffic
    assertEquals(0, b.y.value)
    val rendezvous = CyclicBarrier(2)
    val armed = AtomicInteger(0)
    val done = CountDownLatch(2)
    // Each observer reacts to a POSITIVE commit only and writes a NEGATIVE value
    // to the other store, so the cross-writes cannot ping-pong: the peer's
    // observer sees the negative value and stops. The initial fire (0) is a no-op.
    a.x effect {
        val seen = this
        if (seen > 0) {
            ddRendezvous(rendezvous)
            armed.incrementAndGet()
            b action { y mutate -seen }
        }
    }
    b.y effect {
        val seen = this
        if (seen > 0) {
            ddRendezvous(rendezvous)
            armed.incrementAndGet()
            a action { x mutate -seen }
        }
    }
    val committers =
        listOf(
            ddThread("dd-observer-abba-$attempt-A") {
                try {
                    a action { x mutate 1 }
                } finally {
                    done.countDown()
                }
            },
            ddThread("dd-observer-abba-$attempt-B") {
                try {
                    b action { y mutate 1 }
                } finally {
                    done.countDown()
                }
            },
        )
    return ddObserveAttempt("observer-write ABBA attempt $attempt", committers, armed, done)
}

/**
 * One attempt at the action-wrapped `atomic` ABBA. Each party rendezvous
 * INSIDE its own action body — so both hold their own `transactionLock` — and
 * only then opens the frame that needs the other store's lock.
 */
private fun ddActionAtomicAttempt(attempt: Int): DdAttempt {
    val a = DdStoreA()
    val b = DdStoreB()
    assertEquals(0, a.x.value)
    assertEquals(0, b.y.value)
    val rendezvous = CyclicBarrier(2)
    val armed = AtomicInteger(0)
    val done = CountDownLatch(2)
    val parties =
        listOf(
            ddThread("dd-action-atomic-abba-$attempt-A") {
                try {
                    a action {
                        ddRendezvous(rendezvous)
                        armed.incrementAndGet()
                        atomic(a, b) { }
                    }
                } finally {
                    done.countDown()
                }
            },
            ddThread("dd-action-atomic-abba-$attempt-B") {
                try {
                    b action {
                        ddRendezvous(rendezvous)
                        armed.incrementAndGet()
                        atomic(a, b) { }
                    }
                } finally {
                    done.countDown()
                }
            },
        )
    return ddObserveAttempt("action-wrapped atomic ABBA attempt $attempt", parties, armed, done)
}

/**
 * Defect detectors for the deadlock-class open issues named in the README's
 * "Known issues" and in ROADMAP 0.2.0.
 *
 * Two kinds of test live here:
 *  - **Active detectors** for PARK-class deadlocks. A parked daemon thread costs
 *    nothing, so these run in every build. Each PASSES today by confirming that
 *    the documented lock cycle still forms — both parties observed parked for
 *    more than [DD_PARKED_WINDOW_MS] after [DD_COMPLETION_WINDOW_MS] — and
 *    FAILS with "defect appears fixed — update this test" once the parties
 *    complete, at which point it must be rewritten as a pin of the fixed
 *    behaviour.
 *  - **`@Ignore`d reproducers** for SPIN/hang-class defects. Enabling one today
 *    burns a core (the `MutexSerializer.blockingAcquire` spin) or parks a
 *    thread forever; each is watchdogged so that, once enabled, it fails
 *    instead of hanging.
 *
 * Every test builds fresh stores; wedged stores and their threads stay local
 * to the attempt that wedged them and are never reused, so JUnit's unspecified
 * method order cannot matter. Nothing here touches `Store.defaultScope` or the
 * process-global `FrameObservers` registry.
 *
 * Deliberately NOT included: a blocking `atomic(a, b)` from a raw thread
 * against an in-flight `suspendAction` on `b`. The digests give no crisp
 * two-party PARK cycle for it — the raw thread spins in
 * `MutexSerializer.blockingAcquire` (SPIN-class, same mechanism as the
 * `@Ignore`d nested-action reproducer) and only becomes a true deadlock on a
 * constrained dispatcher where the spinner is the thread the suspending body
 * needs to resume on. That is the nested-action spin with extra steps, so it
 * is left to the reproducer below rather than duplicated.
 */
class DeadlockDetectorsTest {
    /**
     * OPEN DEFECT (README "Known issues"; `MutableState.notifyObservers` KDoc):
     * `Store.action` holds the store's `transactionLock` across the whole commit
     * fanout (`Store.runBlockingActionUnderLock` wraps body, middleware and
     * `Transaction.commit`), so an observer that writes another store INLINE
     * closes a cycle that `atomic`'s `lockOrderKey` ordering never sees.
     *
     * Cycle:
     *  - T1: `a action { x mutate 1 }` → holds A.transactionLock → commit fanout
     *    → A.x observer → `b action { … }` → `ownsActiveTransaction()` on B is
     *    false (B's active transaction belongs to T2) → `StoreLock.acquire` on
     *    B.transactionLock → parks in `SynchronousMutex.lock` (WAITING).
     *  - T2: `b action { y mutate 1 }` → holds B.transactionLock → B.y observer
     *    → `a action { … }` → parks on A.transactionLock.
     *
     * The in-observer rendezvous guarantees the overlap, so attempt 1 wedges
     * deterministically; the [DD_ATTEMPTS] loop is belt and braces per the
     * detector protocol. Once every attempt completes, this fails as fixed.
     */
    @Test
    fun `detector cross-store observer writes still deadlock ABBA on transactionLock`() {
        repeat(DD_ATTEMPTS) { attempt ->
            if (ddObserverWriteAttempt(attempt) == DdAttempt.WEDGED) return
        }
        fail(
            "defect appears fixed — update this test: inline cross-store observer writes completed in all " +
                "$DD_ATTEMPTS attempts (README known issue: observer writing a second store can deadlock)",
        )
    }

    /**
     * OPEN DEFECT (verified against `Atomic.acquireAndRun` and `Frame.verifyFrameNesting`):
     * a plain `action` installs no `FrameMarker`, so `verifyFrameNesting` returns
     * at once for an `atomic` nested inside it and the lock-order guard never
     * runs. Each party therefore already holds one participant's
     * `transactionLock` — outside any global order — when the frame acquires
     * the sorted participant list.
     *
     * Cycle (A constructed before B, so the frame's sorted order is [A, B]; the
     * cycle forms identically for the opposite key order, because each thread
     * holds a lock taken OUTSIDE the sort):
     *  - T1: `a action { atomic(a, b) { } }` → holds A.transactionLock. In the
     *    frame, A: `internalOwnsActiveTransaction()` is true → serializer
     *    skipped → `runUnderLock` re-enters A → savepoint root. B:
     *    `internalOwnsActiveTransaction()` is false (B's active transaction is
     *    T2's) → `runUnderLock` → parks on B.transactionLock.
     *  - T2: `b action { atomic(a, b) { } }` → holds B.transactionLock. In the
     *    frame, A comes first in sort order: not owned by T2 → `runUnderLock` →
     *    parks on A.transactionLock.
     *
     * A fix (extending the lock-order guard to action-enclosed frames, or
     * rejecting the nesting with a `FrameLockOrderException`) makes at least one
     * party throw out of its action body — the action folds it into an `Error`
     * result, releases its lock, and both parties complete.
     */
    @Test
    fun `detector atomic nested in a plain action with inverted per-thread order still deadlocks ABBA`() {
        repeat(DD_ATTEMPTS) { attempt ->
            if (ddActionAtomicAttempt(attempt) == DdAttempt.WEDGED) return
        }
        fail(
            "defect appears fixed — update this test: action-wrapped atomic(a, b) with inverted per-thread " +
                "lock order completed in all $DD_ATTEMPTS attempts (verifyFrameNesting now covers plain actions?)",
        )
    }

    /**
     * OPEN DEFECT (README "Known issues"; ROADMAP 0.2.0 "Fail fast on blocking
     * `action` inside a `suspendAction` body"): the suspending body holds the
     * store's `MutexSerializer` mutex for its whole duration
     * (`suspendActionUnderMutex`), and `Store.action` inside it cannot take the
     * nested fast path — `ownsActiveTransaction()` is disqualified while
     * `suspendingOwner != null` — so it calls `blockingAcquire`, an unbounded
     * `tryLock` + `threadYield` loop, on the very thread that must finish the
     * body to release the mutex. SPIN-class: the thread stays `RUNNABLE` at
     * 100% of one core forever, invisible to thread dumps and deadlock
     * detectors.
     *
     * Enabled after the fix, this expects the nested call to complete promptly
     * (a teaching exception folded into the outer result, or savepoint
     * semantics — the assertion only pins liveness, not which one) and the
     * serializer to be released for a follow-up blocking action. Until then it
     * would fail-not-hang via the watchdog while its probe thread keeps spinning.
     */
    @Ignore("OPEN DEFECT: spins a core forever; enable when fail-fast guard lands (ROADMAP 0.2.0)")
    @Test
    fun `ignored blocking action inside suspendAction body on the same store must fail fast not spin`() {
        val store = DdCounterStore()
        assertEquals(0, store.count.value)
        ddCompletesWithin(10, "blocking action nested inside a suspendAction body") {
            runBlocking {
                store.suspendAction { store action { count mutate 1 } }
            }
        }
        ddCompletesWithin(10, "follow-up blocking action after the nested call") {
            assertIs<TransactionResult.Success<*>>(store action { count mutate 2 })
        }
    }

    /**
     * OPEN DEFECT (ROADMAP 0.2.0 "Savepoint-or-teach for nested `suspendAction`"):
     * `suspendAction` locks the serializer mutex with `coroutineContext[Job]` as
     * owner. A nested `suspendAction` on the same store from a CHILD coroutine
     * carries a different `Job`, so `Mutex.lock(childJob)` does not hit
     * kotlinx's same-owner error — it simply suspends until the mutex is
     * released, which never happens because the outer body is awaiting the
     * child. HANG-class: the `runBlocking` event loop goes idle and the thread
     * parks forever (WAITING), with no spin and no exception. (The same-`Job`
     * variant instead dies with a raw "already locked by the specified owner"
     * `IllegalStateException` that rolls the OUTER body back.)
     *
     * Enabled after the fix, this expects the nested call to complete (savepoint
     * merge or a teaching exception — liveness only) and the serializer to be
     * released for a follow-up blocking action.
     */
    @Ignore("OPEN DEFECT: parks forever on the serializer mutex; enable when savepoint-or-teach lands (ROADMAP 0.2.0)")
    @Test
    fun `ignored child-coroutine suspendAction inside suspendAction body must not park forever`() {
        val store = DdCounterStore()
        assertEquals(0, store.count.value)
        ddCompletesWithin(10, "child-coroutine suspendAction nested inside a suspendAction body") {
            runBlocking {
                store.suspendAction {
                    coroutineScope {
                        async { store.suspendAction { count mutate 1 } }.await()
                    }
                }
            }
        }
        ddCompletesWithin(10, "follow-up blocking action after the nested call") {
            assertIs<TransactionResult.Success<*>>(store action { count mutate 2 })
        }
    }
}
