@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSuspendInFlightException
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private class GuardCounter : Store<GuardCounter>() {
    val count by state { 0L }
}

/**
 * Issue 003 — the escapable serializer gate. A blocking `action { }` (or
 * `atomic`, `reset()`, `restore()`) on a store held by a STUCK suspending
 * operation used to spin forever on the serializer mutex; it now waits a
 * progress-aware budget (shrunk per test through [MutexSerializer]'s internal
 * constructor — never `Store.defaultScope`, which is CAS-once per process)
 * and throws [StoreSuspendInFlightException] when the holder goes quiet for
 * the whole budget. Progress (a staged write, a commit phase starting)
 * restarts the clock; a blocking holder is never budgeted.
 *
 * The instant-throw legs are already covered elsewhere and are deliberately
 * NOT re-tested here: issue #27 (`blockingWaitCanEnd`) refuses any wait
 * beside a parked holder on wasmJs, and issue #31
 * (`refuseInsideOwnSuspendingBody`) refuses a blocking entry from inside the
 * store's own suspending body on every platform — see
 * `wasm-tests`' `BlockingWaitTest` and `OwnBodyBlockingRefusalTest`.
 */
@OptIn(StoreInternalApi::class)
class SuspendGuardBudgetTest {
    /** Install a serializer with a test-shrunk budget on [store]. */
    private fun serializerWithBudget(
        store: Store<*>,
        budget: Duration,
    ): MutexSerializer {
        val serializer =
            MutexSerializer(
                storeName = store::class.simpleName ?: "Store",
                store = store,
                budget = budget,
            )
        store.asyncSerializer = serializer
        return serializer
    }

    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does
     * not finish within [seconds]. Pre-fix (issue 003) the guarded calls spin
     * forever, so the watchdog turns a hang into a red test while the daemon
     * keeps spinning harmlessly.
     */
    private fun completesWithin(
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
        worker.name = "suspend-guard-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — the blocking entry is spinning on the suspend mutex")
        }
        thrown.get()?.let { throw it }
    }

    /** Start a `suspendAction` parked at a suspension point, on a daemon worker. */
    private fun parkedSuspendingAction(
        store: GuardCounter,
        onEntered: () -> Unit = {},
    ): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker =
            Thread {
                runBlocking {
                    store.suspendAction<GuardCounter, Unit> {
                        count mutate 1L
                        onEntered()
                        entered.countDown()
                        release.await()
                    }
                }
            }
        worker.isDaemon = true
        worker.name = "suspend-guard-holder"
        worker.start()
        return entered to release
    }

    @Test
    fun `a cross-thread blocking action during a stuck suspendAction throws after the budget`() {
        val s = GuardCounter()
        serializerWithBudget(s, 150.milliseconds)
        val (entered, release) = parkedSuspendingAction(s)
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the suspending body never started")

        val thrownRef = AtomicReference<Throwable?>(null)
        val startedAt = TimeSource.Monotonic.markNow()
        completesWithin(10, "cross-thread action beside a stuck suspendAction") {
            try {
                s action { count mutate count.value + 10L }
            } catch (e: Throwable) {
                thrownRef.set(e)
            }
        }
        val waited = startedAt.elapsedNow()
        val thrown = thrownRef.get()
        assertNotNull(thrown)
        val budgetThrown = assertIs<StoreSuspendInFlightException>(thrown)
        // Message contract (pinned): store label, holder kind, elapsed wait,
        // configured budget, observable-progress wording, and the migration.
        val message = budgetThrown.message.orEmpty()
        assertTrue("GuardCounter" in message, message)
        assertTrue("suspendAction holding it" in message, message)
        assertTrue("without observable progress" in message, message)
        assertTrue("budget 150 ms" in message, message)
        assertTrue(Regex("after \\d+ ms ").containsMatchIn(message), message)
        assertTrue("suspendAction { … }" in message, message)
        // Elapsed bounded: at least the budget, at most a generous multiple.
        assertTrue(waited >= 150.milliseconds, "threw after only $waited")
        assertTrue(waited <= 5.seconds, "took $waited")

        // The stuck holder is unharmed: release it and the store works again.
        release.countDown()
        completesWithin(10, "post-throw action once the holder released") {
            assertIs<TransactionResult.Success<*>>(s action { count mutate count.value + 100L })
        }
        assertEquals(101L, s.count.value, "the holder's committed 1 plus the post-throw 100; the thrown waiter wrote nothing")
    }

    @Test
    fun `observable progress extends the wait past many budgets`() {
        // A body that stages a write every 100 ms for ~5 s — 5× the budget,
        // with a 10× gap between cadence and budget: a starved CI runner
        // (where delay(100) overshoots well past its nominal wall time, as on
        // GitHub's shared 2-vCPU macos-15 images) must still never leave the
        // holder "quiet" for a whole budget. Each staged write bumps the
        // epoch and restarts any waiter's clock, so the waiter must keep
        // waiting, never throw, and run at the end.
        val s = GuardCounter()
        serializerWithBudget(s, 1.seconds)
        val entered = CountDownLatch(1)
        val worker =
            Thread {
                runBlocking {
                    s.suspendAction<GuardCounter, Unit> {
                        count mutate 1L
                        entered.countDown()
                        repeat(50) {
                            delay(100)
                            count mutate count.value + 1L
                        }
                    }
                }
            }
        worker.isDaemon = true
        worker.name = "suspend-guard-progress-holder"
        worker.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the progressing body never started")

        val before = s.suspendingProgressEpoch
        completesWithin(20, "cross-thread action beside a progressing suspendAction") {
            val r = s action { count mutate count.value + 100L }
            assertIs<TransactionResult.Success<*>>(r)
        }
        val after = s.suspendingProgressEpoch
        // Epoch pinned: one bump per staged write (the entry and commit bumps
        // may land before/after `before`/`after` — 50 is the floor).
        assertTrue(after - before >= 50L, "epoch advanced only ${after - before} for 50 staged writes")
        assertEquals(151L, s.count.value, "1 + 50 from the body, 100 from the waiter that waited 5x its budget")
        worker.join(10_000)
    }

    @Test
    fun `a wait behind a blocking holder is exempt from the budget`() {
        val s = GuardCounter()
        serializerWithBudget(s, 100.milliseconds)
        val holding = CountDownLatch(1)
        val holderDone = CountDownLatch(1)
        val holder =
            Thread {
                s action {
                    count mutate 1L
                    holding.countDown()
                    // Hold the store as a BLOCKING holder for 5x the budget.
                    Thread.sleep(500)
                    count mutate count.value + 1L
                }
                holderDone.countDown()
            }
        holder.isDaemon = true
        holder.name = "suspend-guard-blocking-holder"
        holder.start()
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the blocking holder never entered its action")

        val outcome = AtomicReference<TransactionResult<*>?>(null)
        val waiterDone = CountDownLatch(1)
        val waiter =
            Thread {
                try {
                    outcome.set(s action { count mutate count.value + 10L })
                } finally {
                    waiterDone.countDown()
                }
            }
        waiter.isDaemon = true
        waiter.name = "suspend-guard-blocking-waiter"
        waiter.start()

        assertTrue(holderDone.await(10, TimeUnit.SECONDS), "the blocking holder never finished")
        assertTrue(waiterDone.await(10, TimeUnit.SECONDS), "the blocking waiter never finished — it was budgeted behind a blocking holder")
        assertIs<TransactionResult.Success<*>>(outcome.get())
        assertEquals(12L, s.count.value, "1 + 1 from the holder, 10 from the un-budgeted waiter")
    }

    @Test
    fun `the guard throw escapes action raw, not as a TransactionResult Error`() {
        val s = GuardCounter()
        serializerWithBudget(s, 150.milliseconds)
        val (entered, release) = parkedSuspendingAction(s)
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the suspending body never started")

        completesWithin(10, "raw-escape action beside a stuck suspendAction") {
            // `action` rethrows the guard's exception itself: it comes from the
            // serializer acquire (holdSerialized), before runTransaction's
            // try/catch could fold anything into a TransactionResult.Error.
            val outcome =
                runCatching {
                    s action { count mutate count.value + 10L }
                }
            assertNull(outcome.getOrNull(), "action returned a result instead of throwing")
            assertIs<StoreSuspendInFlightException>(outcome.exceptionOrNull())
        }
        release.countDown()
    }

    @Test
    fun `restore inherits the gate semantics through action`() {
        val s = GuardCounter()
        runBlocking { s.suspendAction<GuardCounter, Unit> { count mutate 7L } }
        val snap = s.snapshot()
        serializerWithBudget(s, 150.milliseconds)
        val (entered, release) = parkedSuspendingAction(s)
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the suspending body never started")

        completesWithin(10, "restore beside a stuck suspendAction") {
            val outcome = runCatching { s.restore(snap) }
            assertIs<StoreSuspendInFlightException>(outcome.exceptionOrNull())
        }
        release.countDown()
    }

    @Test
    fun `a stale suspendingOwner self-heals on the next acquire`() {
        val s = GuardCounter()
        serializerWithBudget(s, 100.milliseconds)
        // Simulate a leaked owner (a torn-down suspending entry that skipped
        // its clear): set directly, as only serializer code would in anger.
        s.suspendingOwner = Any()
        completesWithin(10, "action on a store with a stale suspendingOwner") {
            assertIs<TransactionResult.Success<*>>(s action { count mutate 1L })
        }
        assertNull(s.suspendingOwner, "the acquire self-healed the leaked owner")
    }

    @Test
    fun `a nested blocking action after the serializer is installed still forms a savepoint`() {
        val s = GuardCounter()
        serializerWithBudget(s, 100.milliseconds)
        completesWithin(10, "nested action with the serializer installed") {
            val outer =
                s action {
                    count mutate 1L
                    val inner = s action { count mutate count.value + 1L }
                    assertIs<TransactionResult.Success<*>>(inner)
                }
            assertIs<TransactionResult.Success<*>>(outer)
        }
        assertEquals(2L, s.count.value, "the inner action merged into the outer as a savepoint")
    }

    @Test
    fun `a cross-thread action beside a short suspendAction waits and succeeds`() {
        val s = GuardCounter()
        serializerWithBudget(s, 2.seconds)
        val (entered, release) = parkedSuspendingAction(s)
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the suspending body never started")

        val outcome = AtomicReference<TransactionResult<*>?>(null)
        val waiterDone = CountDownLatch(1)
        val waiter =
            Thread {
                try {
                    // Reaches the spin beside the parked holder immediately;
                    // the holder is released below while this waits.
                    outcome.set(s action { count mutate count.value + 10L })
                } finally {
                    waiterDone.countDown()
                }
            }
        waiter.isDaemon = true
        waiter.name = "suspend-guard-short-waiter"
        waiter.start()
        // Let the waiter reach the spin, then let the holder finish — well
        // within the budget.
        Thread.sleep(100)
        release.countDown()

        assertTrue(waiterDone.await(10, TimeUnit.SECONDS), "the waiter never finished")
        assertIs<TransactionResult.Success<*>>(outcome.get())
        assertEquals(11L, s.count.value, "the waiter waited for the short suspending action and ran after it")
        waiter.join(10_000)
    }
}
