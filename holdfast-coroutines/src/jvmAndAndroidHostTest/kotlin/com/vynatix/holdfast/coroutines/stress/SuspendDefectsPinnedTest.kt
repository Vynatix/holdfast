package com.vynatix.holdfast.coroutines.stress

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class SdPairStore : Store<SdPairStore>() {
    val count by state { 0 }
    val count2 by state { 0 }
}

private data class SdEvent(
    val id: Int,
)

private class SdEventStore : EventfulStore<SdEventStore, SdEvent>() {
    val count by state { 0 }
}

/** Seeded body failure, so a deliberate rollback is distinguishable from any library error. */
private class SdAbort : RuntimeException("seeded abort")

/** Bound on any single coordination step (latch, join, await). Generous: the steps themselves are sub-millisecond. */
private const val SD_STEP_S = 10L

/** Outer watchdog for a whole gated scenario — strictly larger than the sum of the steps it brackets. */
private const val SD_WATCHDOG_S = 30L

/** Unstarted named daemon thread — daemon so a regression cannot outlive the failed test. */
private fun sdThread(
    name: String,
    body: () -> Unit,
): Thread =
    Thread { body() }.apply {
        this.name = name
        isDaemon = true
    }

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. Same watchdog shape as `SerializerContractTest`: a
 * regressed scenario here parks on the serializer mutex or a transaction lock
 * and would otherwise burn the module's 10-minute test-task cap before
 * reporting anything. Daemon, so a wedged worker cannot outlive the failure it
 * caused.
 */
private fun sdCompletesWithin(
    seconds: Long,
    what: String,
    body: () -> Unit,
) {
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    val worker =
        sdThread("sd-defect-probe") {
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
        fail("$what did not complete within ${seconds}s — a transaction lock or serializer mutex is still held")
    }
    thrown.get()?.let { throw it }
}

/** Fail — rather than hang — if [latch] is not released within [SD_STEP_S] seconds. */
private fun sdAwait(
    latch: CountDownLatch,
    what: String,
) {
    if (!latch.await(SD_STEP_S, TimeUnit.SECONDS)) fail("$what did not happen within ${SD_STEP_S}s")
}

/** Join [thread], failing if it is still alive after [SD_STEP_S] seconds — a parked caller, not a slow one. */
private fun sdJoin(
    thread: Thread,
    what: String,
) {
    thread.join(SD_STEP_S * 1_000)
    if (thread.isAlive) fail("$what is still running after ${SD_STEP_S}s")
}

/**
 * Park a suspending body until [gate] opens. Polls with a tiny `delay` so the
 * body stays an ordinary suspend point (never a blocking latch wait inside a
 * transaction), and is bounded so a scenario that never opens its gate fails
 * instead of leaking a coroutine onto the default dispatcher for the rest of
 * the run.
 */
private suspend fun sdHoldUntil(gate: AtomicBoolean) {
    withTimeout(SD_STEP_S * 2_000) {
        while (!gate.get()) {
            delay(1)
        }
    }
}

/**
 * Deterministic characterizations of CONFIRMED open defects on the suspending
 * path. Every test here PASSES TODAY by asserting the current (defective)
 * behavior, so each doubles as a regression alarm for the fix: when it lands,
 * the assertions flagged `OPEN DEFECT pin` in the messages flip and the test
 * must be rewritten to the intended contract described in its KDoc.
 *
 * All coordination is latch/gate based — no sleep decides an outcome — and
 * every step that could park under a regression is bounded, so a hang reports
 * as a failure instead of burning the test-task cap. Each scenario ends with
 * the store either provably live again or disposed, so later tests are never
 * affected.
 *
 * Deliberately NOT here (hang-class, owned by `DeadlockDetectorsTest`): a
 * nested `suspendAction` issued from a CHILD coroutine inside the body. Its
 * different `Job` owner does not trip kotlinx's same-owner error — it parks on
 * the serializer mutex forever, with no exception to pin.
 */
class SuspendDefectsPinnedTest {
    /**
     * OPEN DEFECT: ROADMAP 0.2.0 "Savepoint-or-teach for nested `suspendAction`".
     * `suspendAction` locks the store's serializer mutex with
     * `coroutineContext[Job]` as owner. A nested call in the SAME coroutine
     * presents the same owner, and kotlinx `Mutex.lock(owner)` then throws a raw
     * `IllegalStateException("This mutex is already locked by the specified
     * owner")` synchronously — before the inner body ever runs. The outer body
     * sees that as an ordinary exception, so the OUTER transaction rolls back
     * and the caller receives a `TransactionResult.Error` carrying a kotlinx
     * internals message instead of a savepoint (or a teaching exception).
     *
     * Flip when fixed: the inner call must merge as a savepoint (both writes
     * land) or throw a documented contract exception; the outer write must not
     * be lost either way.
     */
    @Test
    fun `nested suspendAction in the same coroutine folds a raw mutex owner error into the outer result`() {
        val store = SdPairStore()
        assertEquals(0, store.count.value)
        assertEquals(0, store.count2.value)
        val innerBodyRan = AtomicBoolean(false)

        sdCompletesWithin(SD_STEP_S, "same-coroutine nested suspendAction") {
            val result =
                runBlocking {
                    store.suspendAction {
                        count mutate 1
                        store.suspendAction {
                            innerBodyRan.set(true)
                            count2 mutate 2
                        }
                        "unreachable"
                    }
                }
            val error = assertIs<TransactionResult.Error>(result, "the outer suspendAction reports the nested failure")
            val ise = assertIs<IllegalStateException>(error.exception, "the raw kotlinx mutex error escapes unwrapped")
            assertTrue(
                ise.message.orEmpty().contains("already locked by the specified owner"),
                "OPEN DEFECT pin: expected kotlinx's same-owner message, got: ${ise.message}",
            )
        }
        assertFalse(innerBodyRan.get(), "the inner body never runs — the error fires at lock time")
        assertEquals(0, store.count.value, "OPEN DEFECT pin: the OUTER write is rolled back with the nested failure")
        assertEquals(0, store.count2.value)

        // The mutex is released on the way out: the store is not bricked.
        sdCompletesWithin(SD_STEP_S, "follow-up suspendAction after the nested failure") {
            val followUp = runBlocking { store.suspendAction { count mutate 9 } }
            assertIs<TransactionResult.Success<*>>(followUp, "serializer must be free again")
        }
        assertEquals(9, store.count.value)
    }

    /**
     * OPEN DEFECT: same ROADMAP item, seen from inside the body. The nested
     * call does NOT return a `TransactionResult.Error` — it THROWS the raw
     * kotlinx error into the outer body. A body that catches it keeps running,
     * and the outer transaction commits with only the outer write; the inner
     * write was never staged anywhere and no rollback was ever signalled for it.
     *
     * Flip when fixed: a nested call must return a result (savepoint) or throw
     * a documented contract exception — never a kotlinx `Mutex` internals error.
     */
    @Test
    fun `nested suspendAction throws the raw mutex error into the outer body instead of returning a result`() {
        val store = SdPairStore()
        assertEquals(0, store.count.value)
        assertEquals(0, store.count2.value)
        val innerThrown = AtomicReference<Throwable?>(null)

        sdCompletesWithin(SD_STEP_S, "nested suspendAction caught inside the outer body") {
            val result =
                runBlocking {
                    store.suspendAction {
                        count mutate 1
                        val inner = runCatching { store.suspendAction { count2 mutate 2 } }
                        innerThrown.set(inner.exceptionOrNull())
                        "outer-committed"
                    }
                }
            val success = assertIs<TransactionResult.Success<*>>(result, "the body swallowed the error, so it commits")
            assertEquals("outer-committed", success.value)
        }
        val thrown = assertNotNull(innerThrown.get(), "OPEN DEFECT pin: the nested call throws instead of returning")
        val ise = assertIs<IllegalStateException>(thrown)
        assertTrue(
            ise.message.orEmpty().contains("already locked by the specified owner"),
            "OPEN DEFECT pin: expected kotlinx's same-owner message, got: ${ise.message}",
        )
        assertEquals(1, store.count.value, "the outer write committed")
        assertEquals(0, store.count2.value, "the inner write was never staged — gone with no rollback signal")
    }

    /**
     * OPEN DEFECT: ROADMAP 0.2.0 "Savepoint-or-teach for nested `suspendAction`
     * and `suspendAtomic`-inside-`suspendAction` on an overlapping store".
     * `suspendAtomic` resolves its owner as `coroutineContext[Job]` too (a plain
     * `suspendAction` installs no `SuspendAtomicFrame` to inherit from), so
     * `Mutex.lock(owner)` on the store's already-held serializer throws the same
     * raw error at frame entry — before any participant transaction opens — and
     * the enclosing suspendAction rolls back.
     */
    @Test
    fun `suspendAtomic on the enclosing store inside a suspendAction body dies with the raw mutex owner error`() {
        val store = SdPairStore()
        assertEquals(0, store.count.value)
        assertEquals(0, store.count2.value)
        val frameBodyRan = AtomicBoolean(false)

        sdCompletesWithin(SD_STEP_S, "suspendAtomic nested inside suspendAction") {
            val result =
                runBlocking {
                    store.suspendAction {
                        count mutate 1
                        suspendAtomic(store) {
                            frameBodyRan.set(true)
                            store { count2 mutate 2 }
                        }
                        "unreachable"
                    }
                }
            val error = assertIs<TransactionResult.Error>(result, "the enclosing suspendAction reports the failure")
            val ise = assertIs<IllegalStateException>(error.exception, "the raw kotlinx mutex error escapes unwrapped")
            assertTrue(
                ise.message.orEmpty().contains("already locked by the specified owner"),
                "OPEN DEFECT pin: expected kotlinx's same-owner message, got: ${ise.message}",
            )
        }
        assertFalse(frameBodyRan.get(), "the frame body never runs — the error fires at lock time")
        assertEquals(0, store.count.value, "OPEN DEFECT pin: the enclosing write is rolled back too")
        assertEquals(0, store.count2.value)

        sdCompletesWithin(SD_STEP_S, "follow-up suspendAction after the nested frame failure") {
            val followUp = runBlocking { store.suspendAction { count mutate 3 } }
            assertIs<TransactionResult.Success<*>>(followUp, "serializer must be free again")
        }
        assertEquals(3, store.count.value)
    }

    /**
     * OPEN DEFECT: `Store.mutate`'s relaxed `onOwnerCoroutine` check — the
     * "body is running here" marker that ROADMAP 0.2.0 "Fail fast on blocking
     * `action` inside a `suspendAction` body" calls for is exactly what this
     * check lacks. While a `suspendAction` is in flight, `suspendingOwner` is
     * non-null and `mutate` accepts ANY caller as the owner. A plain thread
     * doing `store { count2 mutate 777 }` outside any action therefore stages
     * straight into the suspending transaction's `pendingWrites` — no lock, no
     * one-shot action, no error — and its write shares that transaction's fate.
     * Here the suspending body throws, so the innocent write is rolled back
     * with it, and the thread that issued it never hears about it.
     *
     * Flip when fixed: the interloper must either commit independently (a
     * serialized one-shot action — which also means it PARKS until the
     * suspending action finishes, so the join below fails) or throw an
     * ownership error; `count2` must never silently stay at 0 after a `mutate`
     * that returned normally.
     */
    @Test
    fun `interloper mutate during a rolling-back suspendAction is absorbed and vanishes silently`() {
        val store = SdPairStore()
        assertEquals(0, store.count.value)
        assertEquals(0, store.count2.value)
        val staged = CountDownLatch(1)
        val gate = AtomicBoolean(false)
        val interloperOutcome = AtomicReference<Result<Unit>?>(null)

        sdCompletesWithin(SD_WATCHDOG_S, "interloper mutate during a rolling-back suspendAction") {
            runBlocking {
                val suspending =
                    async(Dispatchers.Default) {
                        store.suspendAction {
                            count mutate 1
                            staged.countDown()
                            sdHoldUntil(gate)
                            throw SdAbort()
                        }
                    }
                try {
                    sdAwait(staged, "suspending body staging its write")
                    val interloper =
                        sdThread("sd-interloper") {
                            interloperOutcome.set(runCatching { store { count2 mutate 777 } })
                        }
                    interloper.start()
                    sdJoin(interloper, "interloper mutate (parked — flip this test if the fix serializes it)")
                } finally {
                    gate.set(true)
                }
                val result = withTimeout(SD_STEP_S * 1_000) { suspending.await() }
                val error = assertIs<TransactionResult.Error>(result, "the seeded abort rolls the suspending body back")
                assertIs<SdAbort>(error.exception)
            }
        }

        val outcome = assertNotNull(interloperOutcome.get(), "the interloper never recorded an outcome")
        assertTrue(
            outcome.isSuccess,
            "OPEN DEFECT pin: the interloper's mutate returned normally, but threw ${outcome.exceptionOrNull()}",
        )
        assertEquals(0, store.count.value, "the suspending write rolled back")
        assertEquals(
            0,
            store.count2.value,
            "OPEN DEFECT pin: the interloper's write was rolled back with the foreign transaction",
        )

        // Liveness sentinel: nothing is left held by either party.
        sdCompletesWithin(SD_STEP_S, "post-scenario blocking action") {
            assertIs<TransactionResult.Success<*>>(store action { count2 mutate 5 })
        }
        assertEquals(5, store.count2.value)
    }

    /**
     * OPEN DEFECT: same relaxed `onOwnerCoroutine` check, committing variant.
     * The interloper's `mutate` returns normally while its write is NOT
     * committed: a third thread reading `count2` right after the call returned
     * still sees the initial value and the `effect` subscriber has not fired.
     * The write becomes visible only when the FOREIGN suspending transaction
     * commits, published as part of that transaction's fanout. `mutate` outside
     * an action is documented as "an implicit single-shot transaction …
     * observers see only the committed value"; here the caller gets neither a
     * commit nor a signal.
     *
     * Flip when fixed: the write must be committed (or rejected) by the time
     * the interloper's call returns.
     */
    @Test
    fun `interloper mutate during a committing suspendAction returns before its write is committed`() {
        val store = SdPairStore()
        assertEquals(0, store.count.value)
        assertEquals(0, store.count2.value)
        val fires = CopyOnWriteArrayList<Int>()
        val subscription = store.count2 effect { fires.add(this) }
        assertEquals(listOf(0), fires.toList())
        val staged = CountDownLatch(1)
        val gate = AtomicBoolean(false)
        val interloperOutcome = AtomicReference<Result<Unit>?>(null)
        val observedAfterReturn = AtomicInteger(Int.MIN_VALUE)
        val firesAfterReturn = AtomicReference<List<Int>>(emptyList())

        try {
            sdCompletesWithin(SD_WATCHDOG_S, "interloper mutate during a committing suspendAction") {
                runBlocking {
                    val suspending =
                        async(Dispatchers.Default) {
                            store.suspendAction {
                                count mutate 1
                                staged.countDown()
                                sdHoldUntil(gate)
                                "committed"
                            }
                        }
                    try {
                        sdAwait(staged, "suspending body staging its write")
                        val interloper =
                            sdThread("sd-interloper") {
                                interloperOutcome.set(runCatching { store { count2 mutate 777 } })
                            }
                        interloper.start()
                        sdJoin(interloper, "interloper mutate (parked — flip this test if the fix serializes it)")
                        val reader = sdThread("sd-reader") { observedAfterReturn.set(store.count2.value) }
                        reader.start()
                        sdJoin(reader, "committed-value reader")
                        firesAfterReturn.set(fires.toList())
                    } finally {
                        gate.set(true)
                    }
                    val result = withTimeout(SD_STEP_S * 1_000) { suspending.await() }
                    val success = assertIs<TransactionResult.Success<*>>(result, "the suspending action commits")
                    assertEquals("committed", success.value)
                }
            }
        } finally {
            subscription.dispose()
        }

        val outcome = assertNotNull(interloperOutcome.get(), "the interloper never recorded an outcome")
        assertTrue(
            outcome.isSuccess,
            "OPEN DEFECT pin: the interloper's mutate returned normally, but threw ${outcome.exceptionOrNull()}",
        )
        assertEquals(
            0,
            observedAfterReturn.get(),
            "OPEN DEFECT pin: the mutate had returned, yet a non-owner reader still saw the initial value",
        )
        assertEquals(listOf(0), firesAfterReturn.get(), "OPEN DEFECT pin: no observer fired for the 'completed' write")
        assertEquals(1, store.count.value)
        assertEquals(777, store.count2.value, "the interloper's write landed only with the foreign commit")
        assertEquals(
            listOf(0, 777),
            fires.toList(),
            "published by the suspending transaction's fanout, not by the caller",
        )
    }

    /**
     * OPEN DEFECT: ROADMAP 0.2.0 "disposed checks on … `suspendAction`" /
     * 0.4.0 "`suspendAction` disposed-store check matches blocking `action`".
     * `dispose()` promises that "any pending writes can never be applied"
     * (`Store.dispose`), but it only synchronizes with BLOCKING actions: it
     * takes `transactionLock` briefly, which a `suspendAction` never holds, and
     * it does not wait for the serializer. A suspending body that has already
     * staged a write, parks, and returns after `dispose()` completed therefore
     * commits anyway — `TransactionResult.Success` — applying the write to a
     * `MutableState` that dispose had already shut down (observers and bridges
     * gone, so the pre-dispose `effect` never hears about it) while `isDisposed`
     * is `true` and the delegate itself refuses to be read.
     *
     * Flip when fixed: the commit must be refused (an `Error`, or dispose must
     * wait for the in-flight suspending action) and the orphaned state must keep
     * its pre-dispose value.
     */
    @Test
    fun `suspendAction commits its staged write after dispose returned`() {
        val store = SdPairStore()
        val countState: State<Int> = store.count
        assertEquals(0, countState.value)
        val fires = CopyOnWriteArrayList<Int>()
        // Cleared by store.dispose() below, so there is nothing to release afterwards.
        store.count effect { fires.add(this) }
        assertEquals(listOf(0), fires.toList())
        val staged = CountDownLatch(1)
        val gate = AtomicBoolean(false)

        sdCompletesWithin(SD_WATCHDOG_S, "dispose racing an in-flight suspendAction") {
            runBlocking {
                val suspending =
                    async(Dispatchers.Default) {
                        store.suspendAction {
                            count mutate 42
                            staged.countDown()
                            sdHoldUntil(gate)
                            "done"
                        }
                    }
                try {
                    sdAwait(staged, "suspending body staging its write")
                    val disposer = sdThread("sd-disposer") { store.dispose() }
                    disposer.start()
                    sdJoin(disposer, "dispose() (flip this test if the fix makes it wait for the suspending action)")
                    assertTrue(store.isDisposed, "dispose() returned, so the store is terminally disposed")
                } finally {
                    gate.set(true)
                }
                val result = withTimeout(SD_STEP_S * 1_000) { suspending.await() }
                val success =
                    assertIs<TransactionResult.Success<*>>(
                        result,
                        "OPEN DEFECT pin: the suspending path escapes dispose's no-apply guarantee",
                    )
                assertEquals("done", success.value)
            }
        }

        assertTrue(store.isDisposed)
        assertEquals(
            42,
            countState.value,
            "OPEN DEFECT pin: the write was applied to a state dispose had already shut down",
        )
        assertEquals(
            listOf(0),
            fires.toList(),
            "the pre-dispose observer was cleared by dispose, so the commit fanned out to nobody",
        )
        assertFailsWith<IllegalStateException>("the delegate is gated; only a retained State reference reads it") {
            store.count.value
        }
    }

    /**
     * OPEN DEFECT: same ROADMAP items, no race required. Unlike blocking
     * `action`, `suspendAction` has no entry check, so on an already-disposed
     * store it installs the serializer, opens a transaction and RUNS THE BODY.
     * Only a write inside the body is refused — by the state delegate's own
     * check — and even that surfaces as an ignorable `TransactionResult.Error`
     * rather than the up-front `IllegalStateException("store disposed")` the
     * blocking path throws.
     */
    @Test
    fun `suspendAction on a disposed store still runs its body while action refuses up front`() {
        val store = SdPairStore()
        assertEquals(0, store.count.value)
        store.dispose()
        assertTrue(store.isDisposed)

        val blocking = assertFailsWith<IllegalStateException> { store action { "never" } }
        assertEquals("store disposed", blocking.message)

        val bodyRan = AtomicBoolean(false)
        sdCompletesWithin(SD_STEP_S, "suspendAction on a disposed store") {
            val readOnly =
                runBlocking {
                    store.suspendAction {
                        bodyRan.set(true)
                        "ran"
                    }
                }
            val success = assertIs<TransactionResult.Success<*>>(readOnly, "OPEN DEFECT pin: no entry check")
            assertEquals("ran", success.value)

            val writing = runBlocking { store.suspendAction { count mutate 1 } }
            val error = assertIs<TransactionResult.Error>(writing, "the write is refused only once the body reaches it")
            val ise = assertIs<IllegalStateException>(error.exception)
            assertEquals("store disposed", ise.message)
        }
        assertTrue(bodyRan.get(), "OPEN DEFECT pin: the body executed on a disposed store")
    }

    /**
     * OPEN DEFECT: ROADMAP 0.2.0 "owner check on `emit()`" / 0.4.0 "`emit()`
     * gains the ownership check `mutate` has". `EventfulStore.emit` reads the
     * volatile `activeTransaction` and stages onto it with no owner check at
     * all. While thread T1's blocking action is parked mid-body, thread T2's
     * `store.emit(...)` — outside any action, which the contract says must
     * throw — returns normally and the event rides T1's transaction: nothing is
     * delivered while T1 is parked, and then T1's commit delivers T2's event.
     *
     * Flip when fixed: T2's emit must throw the off-action
     * `IllegalStateException` (or commit on its own), never attach to T1.
     */
    @Test
    fun `emit from a non-owner thread rides the in-flight action and is delivered by its commit`() {
        val store = SdEventStore()
        assertEquals(0, store.count.value)
        val received = CopyOnWriteArrayList<SdEvent>()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val ownerResult = AtomicReference<TransactionResult<*>?>(null)
        val emitOutcome = AtomicReference<Result<Unit>?>(null)
        val receivedAfterEmit = AtomicInteger(-1)

        sdCompletesWithin(SD_WATCHDOG_S, "non-owner emit during a committing action") {
            runBlocking {
                val subscribed = CompletableDeferred<Unit>()
                val collector =
                    launch(Dispatchers.Default) {
                        store.events
                            .onSubscription { subscribed.complete(Unit) }
                            .collect { received += it }
                    }
                try {
                    withTimeout(SD_STEP_S * 1_000) { subscribed.await() }
                    val owner =
                        sdThread("sd-owner") {
                            ownerResult.set(
                                store action {
                                    count mutate 1
                                    entered.countDown()
                                    // Opened by the coordinating thread below, never by another action.
                                    gate.await(SD_STEP_S * 2, TimeUnit.SECONDS)
                                    "committed"
                                },
                            )
                        }
                    owner.start()
                    try {
                        sdAwait(entered, "owner action entering its body")
                        val emitter =
                            sdThread("sd-emitter") {
                                emitOutcome.set(runCatching { store.emit(SdEvent(777)) })
                            }
                        emitter.start()
                        sdJoin(emitter, "non-owner emit")
                        receivedAfterEmit.set(received.size)
                    } finally {
                        gate.countDown()
                    }
                    sdJoin(owner, "owner action")
                    withTimeout(SD_STEP_S * 1_000) {
                        while (received.isEmpty()) {
                            delay(5)
                        }
                    }
                } finally {
                    collector.cancelAndJoin()
                }
            }
        }

        val outcome = assertNotNull(emitOutcome.get(), "the emitter never recorded an outcome")
        assertTrue(
            outcome.isSuccess,
            "OPEN DEFECT pin: off-action emit returned normally, but threw ${outcome.exceptionOrNull()}",
        )
        assertEquals(0, receivedAfterEmit.get(), "nothing is delivered while the foreign action is still parked")
        assertIs<TransactionResult.Success<*>>(assertNotNull(ownerResult.get()), "T1's action commits")
        assertEquals(
            listOf(SdEvent(777)),
            received.toList(),
            "OPEN DEFECT pin: T2's event was delivered by T1's commit",
        )
    }

    /**
     * OPEN DEFECT: same `emit()` item, rollback side. T2's event attached to
     * T1's transaction, so T1's rollback discards it — an emit that was never
     * inside any action is lost with no signal to T2. A sentinel event
     * committed afterwards proves the ordering: the collector sees the sentinel
     * and never the orphan. Once no transaction is active, the same off-action
     * emit throws the documented `IllegalStateException`, which shows the
     * contract exists and is bypassed only while someone else's transaction is
     * in flight.
     */
    @Test
    fun `emit from a non-owner thread is lost when the in-flight action rolls back`() {
        val store = SdEventStore()
        assertEquals(0, store.count.value)
        val received = CopyOnWriteArrayList<SdEvent>()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val ownerResult = AtomicReference<TransactionResult<*>?>(null)
        val emitOutcome = AtomicReference<Result<Unit>?>(null)

        sdCompletesWithin(SD_WATCHDOG_S, "non-owner emit during a rolling-back action") {
            runBlocking {
                val subscribed = CompletableDeferred<Unit>()
                val collector =
                    launch(Dispatchers.Default) {
                        store.events
                            .onSubscription { subscribed.complete(Unit) }
                            .collect { received += it }
                    }
                try {
                    withTimeout(SD_STEP_S * 1_000) { subscribed.await() }
                    val owner =
                        sdThread("sd-owner") {
                            ownerResult.set(
                                store action {
                                    count mutate 1
                                    entered.countDown()
                                    // Opened by the coordinating thread below, never by another action.
                                    gate.await(SD_STEP_S * 2, TimeUnit.SECONDS)
                                    throw SdAbort()
                                },
                            )
                        }
                    owner.start()
                    try {
                        sdAwait(entered, "owner action entering its body")
                        val emitter =
                            sdThread("sd-emitter") {
                                emitOutcome.set(runCatching { store.emit(SdEvent(777)) })
                            }
                        emitter.start()
                        sdJoin(emitter, "non-owner emit")
                    } finally {
                        gate.countDown()
                    }
                    sdJoin(owner, "owner action")
                    val error = assertIs<TransactionResult.Error>(assertNotNull(ownerResult.get()), "T1 rolled back")
                    assertIs<SdAbort>(error.exception)

                    // Contrast: with no transaction in flight the documented contract holds.
                    val offAction = assertFailsWith<IllegalStateException> { store.emit(SdEvent(1)) }
                    assertTrue(
                        offAction.message.orEmpty().contains("outside of an action"),
                        "off-action emit message, got: ${offAction.message}",
                    )

                    // Sentinel: delivered in order, so if 777 had survived it would precede -1.
                    val sentinel = store action { emit(SdEvent(-1)) }
                    assertIs<TransactionResult.Success<*>>(sentinel, "sentinel commit")
                    withTimeout(SD_STEP_S * 1_000) {
                        while (received.none { it.id == -1 }) {
                            delay(5)
                        }
                    }
                } finally {
                    collector.cancelAndJoin()
                }
            }
        }

        val outcome = assertNotNull(emitOutcome.get(), "the emitter never recorded an outcome")
        assertTrue(
            outcome.isSuccess,
            "OPEN DEFECT pin: off-action emit returned normally, but threw ${outcome.exceptionOrNull()}",
        )
        assertEquals(
            listOf(SdEvent(-1)),
            received.toList(),
            "OPEN DEFECT pin: the orphan event was rolled back with T1",
        )
    }
}
