package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Eventful
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.EventfulSupport
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Single Int-state, Int-event store (default 16-slot SUSPEND buffer) for the ordering/rollback/volume storms. */
private class EvStampStore : EventfulStore<EvStampStore, Int>() {
    val current by state { 0 }
}

/**
 * Eventful store with a deliberately tiny event buffer so the sync drain's
 * silent-drop window is reachable with a handful of commits instead of
 * seventeen.
 */
private class EvTinyBufferStore : EventfulStore<EvTinyBufferStore, Int>(extraBufferCapacity = 4) {
    val current by state { 0 }
}

/** [EventfulSupport]-delegating host, mirroring the composition recipe on the support's own KDoc. */
private class EvSupportStore private constructor(
    val support: EventfulSupport<Int>,
) : Store<EvSupportStore>(),
    Eventful<Int> by support {
    constructor() : this(EventfulSupport())

    init {
        support.bindStore(this)
    }

    val current by state { 0 }
}

/**
 * Run [body] on a named daemon worker and fail — rather than hang — if it does
 * not finish within [seconds]. An event-pipeline regression typically parks a
 * committer inside the drain (or a collector on a latch that never opens);
 * without the watchdog that burns the module's 10-minute test-task cap before
 * reporting anything.
 */
private fun evCompletesWithin(
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
    worker.name = "event-stress-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — a drain, collector, or transaction-lock thread is stuck")
    }
    thrown.get()?.let { throw it }
}

/** Named daemon thread whose failures land in [failures] instead of dying silently. */
private fun evDaemon(
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
private fun evRethrowFirst(failures: ConcurrentLinkedQueue<Throwable>) {
    val first = failures.peek() ?: return
    throw AssertionError("${failures.size} worker thread(s) failed; first: $first", first)
}

/**
 * The transactional event pipeline on the BLOCKING commit path. The events
 * `SharedFlow` is configured with `replay = 0`, `extraBufferCapacity` (default
 * 16) and `BufferOverflow.SUSPEND` — advertised as lossless — but the sync
 * drain in `Transaction.commitDispatching` is a bare `tryEmit` per event whose
 * boolean result is ignored.
 *
 * Pins: stage-order delivery strictly after the state apply, rollback discard
 * with a live collector, and exact event conservation under a 4-writer storm
 * whose synchronously-draining collector keeps the load inside the buffer's
 * lossless bounds. Characterizations (assertions to flip when fixed): the
 * silent tryEmit drop past the buffer, and `emit` from a non-owner thread
 * riding a foreign transaction. Coordination is via latches and deferreds,
 * never bare sleeps on the success path; every test runs under a daemon-thread
 * watchdog.
 */
class EventStressTest {
    /**
     * One writer, three events staged per action after the state write. The
     * `Dispatchers.Unconfined` collector is resumed synchronously inside each
     * commit's drain (phase 3, on the committing thread), so at event-receipt
     * time the commit's state value MUST already be applied — the drain runs
     * after apply, and the transaction's pending buffer is already cleared, so
     * even the owner-thread read-your-own-writes path serves the committed
     * value. Events must arrive exactly in stage order with none dropped.
     */
    @Test
    fun `events drain in stage order and only after the state value is committed`() =
        evCompletesWithin(15, "stage-order probe") {
            val store = EvStampStore()
            assertEquals(0, store.current.value, "current delegate must be registered up front")

            val received = ConcurrentLinkedQueue<Pair<Int, Int>>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                // Unconfined runs the collector inline up to its first suspension, so the
                // subscription is active before launch returns; each commit's tryEmit then
                // resumes it synchronously on the committing thread, inside the drain.
                scope.launch {
                    store.events.collect { event ->
                        received.add(event to store.current.value)
                    }
                }

                for (k in 1..50) {
                    val result =
                        store action {
                            current mutate k
                            emit(k * 100 + 1)
                            emit(k * 100 + 2)
                            emit(k * 100 + 3)
                        }
                    assertIs<TransactionResult.Success<*>>(result, "commit $k must succeed")
                }

                val events = received.map { it.first }
                val expected = (1..50).flatMap { k -> (1..3).map { i -> k * 100 + i } }
                assertEquals(expected, events, "events must arrive exactly in stage order with none dropped")
                received.forEach { (event, stateAtReceipt) ->
                    assertEquals(
                        event / 100,
                        stateAtReceipt,
                        "event $event was delivered before its commit's state write was applied",
                    )
                }
            } finally {
                scope.cancel()
            }
        }

    /**
     * Alternating committing and failing actions, each staging one event. The
     * Unconfined collector receives synchronously inside each commit's drain,
     * so by the time the loop ends there is nothing in flight: the discard
     * assertion is exact, with no grace window. Rollback never reaches the
     * drain (`Transaction.rollback` clears `pendingEvents` without emitting),
     * so only the even, committed events may appear — in commit order.
     */
    @Test
    fun `rolled back actions never leak their staged events`() =
        evCompletesWithin(15, "rollback-discard probe") {
            val store = EvStampStore()
            assertEquals(0, store.current.value, "current delegate must be registered up front")

            val received = ConcurrentLinkedQueue<Int>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                scope.launch { store.events.collect { received.add(it) } }

                for (k in 1..40) {
                    val result =
                        store action {
                            current mutate k
                            emit(k)
                            if (k % 2 == 1) error("deliberate rollback of $k")
                        }
                    if (k % 2 == 1) {
                        assertIs<TransactionResult.Error>(result, "odd action $k must roll back")
                    } else {
                        assertIs<TransactionResult.Success<*>>(result, "even action $k must commit")
                    }
                }

                assertEquals(
                    (2..40 step 2).toList(),
                    received.toList(),
                    "only committed events may reach the collector, in commit order",
                )
                assertEquals(40, store.current.value, "the last committed write must stand")
            } finally {
                scope.cancel()
            }
        }

    /**
     * Volume pin, kept inside the sync drain's lossless bounds by construction:
     * the Unconfined collector is resumed inside each commit's `tryEmit`, on
     * the committing thread and under the store's `transactionLock`, so every
     * event is consumed before the next commit can start and the 16-slot
     * buffer never holds more than one event — `tryEmit` cannot fail. Four
     * writers x 250 commits x 1 stamped event: exactly 1000 events, and each
     * writer's subsequence drains gap-free in its own commit order.
     */
    @Test
    fun `four writers against a synchronously draining collector lose no events`() =
        evCompletesWithin(25, "multi-writer conservation storm") {
            val store = EvStampStore()
            assertEquals(0, store.current.value, "current delegate must be registered up front")

            val received = ConcurrentLinkedQueue<Int>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val successes = AtomicInteger(0)
            val startGate = CountDownLatch(1)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                scope.launch { store.events.collect { received.add(it) } }

                val writers =
                    (0 until 4).map { w ->
                        evDaemon("ev-conservation-writer-$w", failures) {
                            check(startGate.await(10, TimeUnit.SECONDS)) { "start gate never released" }
                            for (i in 1..250) {
                                val result =
                                    store action {
                                        current update { it + 1 }
                                        emit(w * 100_000 + i)
                                    }
                                if (result is TransactionResult.Success) successes.incrementAndGet()
                            }
                        }
                    }
                writers.forEach(Thread::start)
                startGate.countDown()
                writers.forEach { it.join() }
                evRethrowFirst(failures)

                assertEquals(1_000, successes.get(), "every storm commit must succeed")
                assertEquals(1_000, store.current.value, "no committed increment may be lost")
                val events = received.toList()
                assertEquals(1_000, events.size, "every staged event must drain exactly once — none dropped or duplicated")
                (0 until 4).forEach { w ->
                    val sequence = events.filter { it / 100_000 == w }.map { it % 100_000 }
                    assertEquals(
                        (1..250).toList(),
                        sequence,
                        "writer $w's events must drain gap-free in its own commit order",
                    )
                }
            } finally {
                scope.cancel()
            }
        }

    /**
     * BUG: the sync commit drain ignores `tryEmit`'s result, so with a
     * subscribed-but-suspended collector every event past the buffer capacity
     * is silently dropped while the action still reports Success — violating
     * the documented SUSPEND/lossless contract (`Eventful.events`: "no event
     * is dropped"; the fallback is admitted at Transaction.kt's drain).
     *
     * Deterministic script: the collector takes probe event 0 (its SharedFlow
     * cursor advances BEFORE the body runs) and parks inside the body on a
     * gate, so exactly `extraBufferCapacity = 4` further events fit. Eight
     * more commits each stage one event: 1..4 buffer, 5..8 vanish — no error,
     * no handler, Success everywhere, state fully applied.
     *
     * EXPECTED (once fixed): all of 0..8 delivered (or the commit fails
     * loudly). ACTUAL (pinned here): exactly 0..4. Flip the final assertion
     * to `(0..8).toList()` when the sync path honors back-pressure.
     */
    @Test
    fun `BUG sync drain silently drops events past the buffer while the collector is suspended`() =
        evCompletesWithin(30, "buffer-overflow drop probe") {
            runBlocking {
                val store = EvTinyBufferStore()
                assertEquals(0, store.current.value, "current delegate must be registered up front")

                val received = ConcurrentLinkedQueue<Int>()
                val subscribed = CompletableDeferred<Unit>()
                val firstTaken = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val drained = CompletableDeferred<Unit>()
                val collector =
                    launch(Dispatchers.Default) {
                        store.events
                            .onSubscription { subscribed.complete(Unit) }
                            .collect { event ->
                                received.add(event)
                                if (event == 0) {
                                    firstTaken.complete(Unit)
                                    gate.await()
                                }
                                if (received.size == 5) drained.complete(Unit)
                            }
                    }
                withTimeout(10_000) { subscribed.await() }

                assertIs<TransactionResult.Success<*>>(
                    store action {
                        current mutate 0
                        emit(0)
                    },
                    "probe commit must succeed",
                )
                withTimeout(10_000) { firstTaken.await() }

                // The collector is now inside event 0's body; its cursor is already past
                // the probe, so the flow buffers at most 4 further events while it parks.
                for (k in 1..8) {
                    assertIs<TransactionResult.Success<*>>(
                        store action {
                            current mutate k
                            emit(k)
                        },
                        "burst commit $k must still report Success even while its event is dropped",
                    )
                }
                assertEquals(8, store.current.value, "every burst write must be applied — only events are lost")

                gate.complete(Unit)
                withTimeout(10_000) { drained.await() }
                // Grace for any (wrongly assumed impossible) late delivery of 5..8.
                delay(200)
                collector.cancel()

                assertEquals(
                    (0..4).toList(),
                    received.toList(),
                    "CURRENT: events 5..8 are silently dropped by the ignored tryEmit — flip to (0..8) when fixed",
                )
            }
        }

    /**
     * Off-action `emit` throws [IllegalStateException] on both surfaces —
     * events must be transactional so rollback can discard them — and the
     * failed call leaves nothing behind: no delivery, no corruption, and the
     * next real action stages and drains normally. [EventfulSupport] adds two
     * gates of its own: `emit` before `bindStore` throws, and a second
     * `bindStore` throws.
     */
    @Test
    fun `emit outside an action throws on both surfaces and nothing reaches the flow`() =
        evCompletesWithin(15, "off-action emit probe") {
            val store = EvStampStore()
            assertEquals(0, store.current.value, "current delegate must be registered up front")

            val received = ConcurrentLinkedQueue<Int>()
            val supportReceived = ConcurrentLinkedQueue<Int>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                scope.launch { store.events.collect { received.add(it) } }

                val offAction = assertFailsWith<IllegalStateException> { store.emit(7) }
                assertTrue(
                    offAction.message.orEmpty().contains("outside of an action"),
                    "the failure must name the off-action cause; was: ${offAction.message}",
                )
                assertTrue(received.isEmpty(), "a rejected emit must never reach the flow: ${received.toList()}")

                val next =
                    store action {
                        current mutate 1
                        emit(7)
                    }
                assertIs<TransactionResult.Success<*>>(next, "the rejected emit must not poison later actions")
                assertEquals(listOf(7), received.toList(), "the committed event must drain normally")

                val unbound = EventfulSupport<Int>()
                val beforeBind = assertFailsWith<IllegalStateException> { unbound.emit(1) }
                assertTrue(
                    beforeBind.message.orEmpty().contains("before bindStore"),
                    "the failure must name the missing binding; was: ${beforeBind.message}",
                )

                val supportStore = EvSupportStore()
                assertEquals(0, supportStore.current.value, "support host delegate must be registered up front")
                val rebound = assertFailsWith<IllegalStateException> { supportStore.support.bindStore(supportStore) }
                assertTrue(
                    rebound.message.orEmpty().contains("at most once"),
                    "double bind must be rejected; was: ${rebound.message}",
                )
                val supportOffAction = assertFailsWith<IllegalStateException> { supportStore.emit(9) }
                assertTrue(
                    supportOffAction.message.orEmpty().contains("outside of an action"),
                    "the support failure must name the off-action cause; was: ${supportOffAction.message}",
                )

                scope.launch { supportStore.events.collect { supportReceived.add(it) } }
                val supportCommit =
                    supportStore action {
                        current mutate 1
                        emit(9)
                    }
                assertIs<TransactionResult.Success<*>>(supportCommit, "the support host must stage and commit")
                assertEquals(listOf(9), supportReceived.toList(), "the support host's event must drain on commit")
            } finally {
                scope.cancel()
            }
        }

    /**
     * BUG: `emit` checks only that SOME transaction is active — there is no
     * `ownerThreadId` comparison (contrast `mutate`) — so a non-owner thread's
     * emit is staged onto the foreign in-flight transaction and shares its
     * fate: delivered if the foreign action commits, silently discarded if it
     * rolls back. The emitter gets no error either way.
     *
     * Deterministic script: the owner parks inside its action on a latch that
     * the test thread releases only AFTER its foreign `emit` — safe, because
     * plain `emit` never touches the transactionLock. Commit leg: the foreign
     * event drains ahead of the owner's own (stage order). Rollback leg: the
     * foreign event vanishes, proven gone (not late) by the sentinel that the
     * ordered SharedFlow delivers afterwards.
     *
     * EXPECTED (once fixed): the non-owner emit throws IllegalStateException
     * (or is attributed to the emitter). ACTUAL (pinned here): it silently
     * rides the owner's transaction. Rework both legs when the owner check
     * lands — the bare `store.emit` calls below will then throw.
     */
    @Test
    fun `BUG emit from a non-owner thread rides the owner transaction through commit and rollback`() =
        evCompletesWithin(20, "foreign emit probe") {
            val store = EvStampStore()
            assertEquals(0, store.current.value, "current delegate must be registered up front")

            val received = ConcurrentLinkedQueue<Int>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                scope.launch { store.events.collect { received.add(it) } }

                // Commit leg: foreign emit lands on the owner's txn and drains with it.
                val commitOpened = CountDownLatch(1)
                val commitForeignStaged = CountDownLatch(1)
                val committer =
                    evDaemon("ev-foreign-commit-owner", failures) {
                        val result =
                            store action {
                                current mutate 1
                                commitOpened.countDown()
                                // Released by a plain emit() from the test thread, which takes
                                // no store lock — waiting here cannot deadlock the test.
                                check(commitForeignStaged.await(10, TimeUnit.SECONDS)) { "foreign emit never signalled" }
                                emit(11)
                            }
                        check(result is TransactionResult.Success) { "owner action must commit: $result" }
                    }
                committer.start()
                check(commitOpened.await(10, TimeUnit.SECONDS)) { "owner action never opened" }
                store.emit(999) // CURRENT: non-owner emit is accepted and staged onto the owner's txn.
                commitForeignStaged.countDown()
                committer.join()
                evRethrowFirst(failures)
                assertEquals(
                    listOf(999, 11),
                    received.toList(),
                    "CURRENT: the foreign event rides the owner's commit, ahead of the owner's own event",
                )
                received.clear()

                // Rollback leg: the same misattributed event is discarded with the owner's txn.
                val rollbackOpened = CountDownLatch(1)
                val rollbackForeignStaged = CountDownLatch(1)
                val rollbacker =
                    evDaemon("ev-foreign-rollback-owner", failures) {
                        val result =
                            store action {
                                current mutate 2
                                rollbackOpened.countDown()
                                check(rollbackForeignStaged.await(10, TimeUnit.SECONDS)) { "foreign emit never signalled" }
                                emit(22)
                                error("deliberate rollback")
                            }
                        check(result is TransactionResult.Error) { "owner action must roll back: $result" }
                    }
                rollbacker.start()
                check(rollbackOpened.await(10, TimeUnit.SECONDS)) { "owner action never opened" }
                store.emit(888) // CURRENT: accepted, staged onto the doomed txn, discarded with it.
                rollbackForeignStaged.countDown()
                rollbacker.join()
                evRethrowFirst(failures)
                assertTrue(
                    received.isEmpty(),
                    "CURRENT: the foreign event is silently discarded with the owner's rollback: ${received.toList()}",
                )

                // Sentinel: the ordered flow would have delivered 888 before 33 had it survived.
                val sentinel =
                    store action {
                        current mutate 3
                        emit(33)
                    }
                assertIs<TransactionResult.Success<*>>(sentinel, "sentinel commit must succeed")
                assertEquals(listOf(33), received.toList(), "only the sentinel event may arrive — 888 is gone for good")
            } finally {
                scope.cancel()
            }
        }
}
