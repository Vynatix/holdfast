package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Single-counter store for the churn and observer-throw storms. */
private class ObCounterStore : Store<ObCounterStore>() {
    val count by state { 0 }
}

/** Eventful store whose actions commit one state write plus one event, for the fanout-order oracle. */
private class ObOrderStore : EventfulStore<ObOrderStore, Int>() {
    val seq by state { 0 }
}

/** Two-state store for the fanout write-blackhole characterizations: `a` is observed, `b` is written back. */
private class ObBlackholeStore : Store<ObBlackholeStore>() {
    val a by state { 0 }
    val b by state { 0 }
}

/**
 * Run [body] on a named daemon worker and fail — rather than hang — if it does
 * not finish within [seconds]. A fanout regression typically parks a committer
 * on the store's `transactionLock` (or an observer inside it) forever; without
 * the watchdog that burns the module's 10-minute test-task cap before
 * reporting anything.
 */
private fun obCompletesWithin(
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
    worker.name = "observer-fanout-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — a fanout or transaction-lock thread is stuck")
    }
    thrown.get()?.let { throw it }
}

/** Named daemon thread whose failures land in [failures] instead of dying silently. */
private fun obDaemon(
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
private fun obRethrowFirst(failures: ConcurrentLinkedQueue<Throwable>) {
    val first = failures.peek() ?: return
    throw AssertionError("${failures.size} worker thread(s) failed; first: $first", first)
}

/**
 * Real-thread storms over commit fanout: subscribe/dispose churn against
 * writers, throwing observers vs. the `uncaughtObserverHandler`, the
 * observer -> bridge-publish -> event-drain phase order under multi-writer
 * load, and two deterministic characterizations of the fanout write blackhole
 * (a same-store write from an observer during fanout is silently discarded
 * while everything reports Success).
 *
 * Coordination is via latches and atomics, never sleeps; every test runs
 * under a daemon-thread watchdog so a regression that parks a thread fails
 * fast instead of eating the whole test task.
 */
class ObserverFanoutStressTest {
    /**
     * Four threads churn effect subscriptions (each keeps its newest
     * subscription alive one cycle so fanout deliveries actually reach churned
     * observers, not just initial fires) while two writers commit distinct
     * stamped values. Every value an observer is ever handed must be one a
     * writer stamped into the committed set BEFORE opening its action (or the
     * initial value): fanout delivers post-apply, and the initial fire reads
     * the committed value, so nothing pending, rolled back, or fabricated may
     * ever leak into a callback.
     */
    @Test
    fun `subscription churn against committing writers delivers only committed values and never throws`() =
        obCompletesWithin(25, "observer churn storm") {
            val store = ObCounterStore()
            assertEquals(0, store.count.value, "count delegate must be registered before the race")

            val failures = ConcurrentLinkedQueue<Throwable>()
            store.uncaughtObserverHandler = { failures.add(it) }
            // Stamped BEFORE the committing action opens, so membership is guaranteed
            // by the time fanout (which runs inside the action call) delivers it.
            val committedValues = ConcurrentHashMap.newKeySet<Int>().apply { add(0) }
            val deliveries = ConcurrentLinkedQueue<Int>()
            val successes = AtomicInteger(0)
            val startGate = CountDownLatch(1)

            val churners =
                (0 until 4).map { c ->
                    obDaemon("ob-churner-$c", failures) {
                        check(startGate.await(10, TimeUnit.SECONDS)) { "start gate never released" }
                        var previous: Disposable? = null
                        repeat(400) {
                            val sub = store.count effect { deliveries.add(this) }
                            previous?.dispose()
                            previous = sub
                        }
                        previous?.dispose()
                    }
                }
            val writers =
                (0 until 2).map { w ->
                    obDaemon("ob-churn-writer-$w", failures) {
                        check(startGate.await(10, TimeUnit.SECONDS)) { "start gate never released" }
                        for (i in 1..1_000) {
                            val value = (w + 1) * 1_000_000 + i
                            committedValues.add(value)
                            val result = store action { count mutate value }
                            if (result is TransactionResult.Success) successes.incrementAndGet()
                        }
                    }
                }

            (churners + writers).forEach(Thread::start)
            startGate.countDown()
            (churners + writers).forEach { it.join() }

            obRethrowFirst(failures)
            assertEquals(2_000, successes.get(), "every storm commit must succeed")
            deliveries.forEach { delivered ->
                assertTrue(
                    delivered in committedValues,
                    "an observer was handed $delivered, which no writer ever committed",
                )
            }

            // Liveness sentinel: the store still commits and still serves fresh subscriptions.
            committedValues.add(-1)
            assertIs<TransactionResult.Success<*>>(store action { count mutate -1 }, "sentinel commit")
            val freshSeen = mutableListOf<Int>()
            val probe = store.count effect { freshSeen.add(this) }
            probe.dispose()
            assertEquals(listOf(-1), freshSeen, "a fresh subscription must immediately see the sentinel commit")
            assertEquals(-1, store.count.value)
        }

    /**
     * Ten observers on one state, three of which throw on every committed
     * value. Exception isolation is per-observer (`notifyObservers` catches
     * around each callback and routes to `uncaughtObserverHandler`), so under
     * a 4-writer storm every commit must still fire all ten observers, the
     * handler must see exactly three throws per commit, every action must
     * return Success, and the seven well-behaved observers must end on the
     * final sentinel value. The throwers stay quiet on the initial fire
     * (value 0): initial-fire exceptions propagate to the subscriber, not the
     * handler, by documented contract.
     */
    @Test
    fun `throwing observers never disturb sibling observers the handler count or the commit result`() =
        obCompletesWithin(20, "observer-throw storm") {
            val store = ObCounterStore()
            assertEquals(0, store.count.value, "count delegate must be registered before the race")

            val handlerHits = AtomicInteger(0)
            store.uncaughtObserverHandler = { handlerHits.incrementAndGet() }
            val fireCounts = Array(10) { AtomicInteger(0) }
            val lastSeen = Array(7) { AtomicInteger(Int.MIN_VALUE) }
            val subs = mutableListOf<Disposable>()
            repeat(7) { i ->
                subs +=
                    store.count effect {
                        fireCounts[i].incrementAndGet()
                        lastSeen[i].set(this)
                    }
            }
            repeat(3) { i ->
                subs +=
                    store.count effect {
                        fireCounts[7 + i].incrementAndGet()
                        if (this > 0) throw IllegalStateException("observer ${7 + i} always throws on commits")
                    }
            }

            val failures = ConcurrentLinkedQueue<Throwable>()
            val successes = AtomicInteger(0)
            val ticket = AtomicInteger(0)
            val startGate = CountDownLatch(1)
            val writers =
                (0 until 4).map { w ->
                    obDaemon("ob-throw-writer-$w", failures) {
                        check(startGate.await(10, TimeUnit.SECONDS)) { "start gate never released" }
                        repeat(250) {
                            val next = ticket.incrementAndGet()
                            val result = store action { count mutate next }
                            if (result is TransactionResult.Success) successes.incrementAndGet()
                        }
                    }
                }
            writers.forEach(Thread::start)
            startGate.countDown()
            writers.forEach { it.join() }
            obRethrowFirst(failures)

            assertEquals(1_000, successes.get(), "a throwing observer must never flip an action to Error")

            // Single-threaded sentinel: its fanout completes before the action returns,
            // so the well-behaved observers' last-seen values are deterministic.
            val sentinel = 1_001
            assertIs<TransactionResult.Success<*>>(store action { count mutate sentinel }, "sentinel commit")

            val commits = 1_001
            fireCounts.forEachIndexed { i, count ->
                assertEquals(1 + commits, count.get(), "observer $i must fire once initially and once per commit")
            }
            lastSeen.forEachIndexed { i, seen ->
                assertEquals(sentinel, seen.get(), "well-behaved observer $i must have seen the sentinel value")
            }
            assertEquals(3 * commits, handlerHits.get(), "handler must see exactly the 3 throwers per commit")
            subs.forEach(Disposable::dispose)
        }

    /**
     * Fanout order oracle: one state with an observer AND a bridge, plus one
     * event staged per action. Every commit must trace exactly
     * `observer:k, bridge:k, event:k` with no interleaving between commits —
     * the whole fanout runs under the store's `transactionLock`, and the
     * events collector runs on `Dispatchers.Unconfined`, so the `tryEmit`
     * drain resumes it synchronously on the committing thread.
     */
    @Test
    fun `commit fanout always runs observer then bridge publish then event drain per commit`() =
        obCompletesWithin(20, "fanout order storm") {
            val store = ObOrderStore()
            assertEquals(0, store.seq.value, "seq delegate must be registered before the race")

            val trace: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
            val failures = ConcurrentLinkedQueue<Throwable>()
            store.uncaughtObserverHandler = { failures.add(it) }

            val sub = store.seq effect { trace.add("observer:$this") }
            val orderBridge =
                object : Bridge<Int> {
                    // No replay on attach — a load-on-attach fire would pollute the trace.
                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

                    override fun publish(value: Int): Boolean {
                        trace.add("bridge:$value")
                        return true
                    }
                }
            store { seq bridge orderBridge }

            val collectorScope = CoroutineScope(Dispatchers.Unconfined)
            try {
                // Unconfined runs the collector inline up to its first suspension, so
                // the subscription is active before launch returns; each commit's
                // tryEmit then resumes it synchronously on the committing thread.
                collectorScope.launch {
                    store.events.collect { trace.add("event:$it") }
                }

                val successes = AtomicInteger(0)
                val ticket = AtomicInteger(0)
                val startGate = CountDownLatch(1)
                val writers =
                    (0 until 4).map { w ->
                        obDaemon("ob-order-writer-$w", failures) {
                            check(startGate.await(10, TimeUnit.SECONDS)) { "start gate never released" }
                            repeat(250) {
                                val k = ticket.incrementAndGet()
                                val result =
                                    store action {
                                        seq mutate k
                                        emit(k)
                                    }
                                if (result is TransactionResult.Success) successes.incrementAndGet()
                            }
                        }
                    }
                writers.forEach(Thread::start)
                startGate.countDown()
                writers.forEach { it.join() }
                obRethrowFirst(failures)
                assertEquals(1_000, successes.get(), "every storm commit must succeed")

                val entries = trace.toList()
                assertEquals("observer:0", entries.firstOrNull(), "the effect's initial fire must be the first entry")
                assertEquals(1 + 3 * 1_000, entries.size, "each commit must add exactly 3 trace entries")
                val seen = mutableSetOf<Int>()
                entries.drop(1).chunked(3).forEach { chunk ->
                    val k =
                        chunk.first().substringAfter("observer:", "").toIntOrNull()
                            ?: fail("fanout phases interleaved across commits: $chunk")
                    assertEquals(
                        listOf("observer:$k", "bridge:$k", "event:$k"),
                        chunk,
                        "commit of $k must fan out observer -> bridge publish -> event drain",
                    )
                    seen += k
                }
                assertEquals((1..1_000).toSet(), seen, "every committed value must have fanned out exactly once")
            } finally {
                collectorScope.cancel()
                sub.dispose()
            }
        }

    /**
     * BUG: a same-store bare `mutate` from an observer during commit fanout is
     * silently discarded. `Transaction.commitDispatching` applies and CLEARS
     * `pendingWrites` (pass 1), then runs observer fanout while the
     * transaction's status is still Active (the Committed flip happens only
     * after the event drain) and while `Store._activeTransaction` still points
     * at the committing transaction (restored only in the action's finally).
     * Observers run inline on the committing = owner thread (asserted below),
     * so the observer's `mutate` passes both the owner-thread and Active-status
     * checks and stages into the already-applied, already-cleared buffer —
     * never applied, never rolled back, no error anywhere. During the rest of
     * fanout the phantom value is even visible via read-your-own-writes.
     *
     * EXPECTED (once fixed): the write is applied (or the mutate is rejected
     * loudly). ACTUAL (pinned here): Success everywhere and `b` stays 0.
     * When the kernel routes or rejects fanout-time writes, flip the final
     * assertion to `assertEquals(99, store.b.value)` (or expect a throw).
     */
    @Test
    fun `BUG same-store mutate from an observer during fanout is silently lost`() =
        obCompletesWithin(10, "observer write-back probe (bare mutate)") {
            val store = ObBlackholeStore()
            assertEquals(0, store.a.value, "a delegate must be registered up front")
            assertEquals(0, store.b.value, "b delegate must be registered up front")

            val handlerErrors = ConcurrentLinkedQueue<Throwable>()
            store.uncaughtObserverHandler = { handlerErrors.add(it) }
            val committerThreadId = Thread.currentThread().id
            val observerThreadId = AtomicLong(Long.MIN_VALUE)
            val phantomDuringFanout = AtomicInteger(Int.MIN_VALUE)
            val fired = AtomicBoolean(false)

            val sub =
                store.a effect {
                    // Guarded to the commit fire only: at the initial fire (value 0)
                    // no transaction is active and the mutate would legitimately
                    // commit via a synthesized one-shot action.
                    if (this == 1 && !fired.getAndSet(true)) {
                        observerThreadId.set(Thread.currentThread().id)
                        store { b mutate 99 }
                        phantomDuringFanout.set(store.b.value)
                    }
                }
            val result = store action { a mutate 1 }

            assertIs<TransactionResult.Success<*>>(result, "the outer action reports Success either way")
            assertTrue(fired.get(), "the observer must have fired for the commit of a=1")
            assertEquals(1, store.a.value)
            assertEquals(
                committerThreadId,
                observerThreadId.get(),
                "fanout runs observers inline on the committing thread — the precondition for the owner-thread branch",
            )
            assertEquals(
                99,
                phantomDuringFanout.get(),
                "during fanout the staged write is visible via read-your-own-writes (the phantom)",
            )
            assertTrue(handlerErrors.isEmpty(), "the write vanishes with no error: ${handlerErrors.toList()}")
            // BUG pin — flip to 99 (or to an expected throw) once fanout-time writes are routed or rejected.
            assertEquals(0, store.b.value, "CURRENT: the observer's same-store write is silently discarded")
            sub.dispose()
        }

    /**
     * BUG: the nested-action variant of the same blackhole. An observer that
     * opens `store action { }` during fanout takes the savepoint branch
     * (`ownsActiveTransaction()` is true on the committing thread), and the
     * savepoint's commit merges its pending writes into the parent — the
     * committing transaction whose buffer was already applied and cleared.
     * The inner action returns `TransactionResult.Success` while its write
     * vanishes without a trace.
     *
     * EXPECTED (once fixed): the nested write lands, or the nested action
     * fails loudly. ACTUAL (pinned here): inner Success, outer Success, no
     * handler error, `b` stays 0. Flip the final assertion to
     * `assertEquals(77, store.b.value)` (or expect an error) when fixed.
     */
    @Test
    fun `BUG same-store nested action from an observer during fanout reports Success but commits nothing`() =
        obCompletesWithin(10, "observer write-back probe (nested action)") {
            val store = ObBlackholeStore()
            assertEquals(0, store.a.value, "a delegate must be registered up front")
            assertEquals(0, store.b.value, "b delegate must be registered up front")

            val handlerErrors = ConcurrentLinkedQueue<Throwable>()
            store.uncaughtObserverHandler = { handlerErrors.add(it) }
            val innerResult = AtomicReference<TransactionResult<Unit>?>(null)
            val fired = AtomicBoolean(false)

            val sub =
                store.a effect {
                    if (this == 1 && !fired.getAndSet(true)) {
                        innerResult.set(store action { b mutate 77 })
                    }
                }
            val outer = store action { a mutate 1 }

            assertIs<TransactionResult.Success<*>>(outer, "the outer action must commit")
            assertTrue(fired.get(), "the observer must have fired for the commit of a=1")
            assertIs<TransactionResult.Success<*>>(
                innerResult.get(),
                "the observer's nested action reports Success — the defect's cover story",
            )
            assertTrue(handlerErrors.isEmpty(), "no error surfaces anywhere: ${handlerErrors.toList()}")
            assertEquals(1, store.a.value)
            // BUG pin — flip to 77 (or to an expected error) once fanout-time savepoints stop being merged away.
            assertEquals(0, store.b.value, "CURRENT: the savepoint merged into the already-applied, cleared buffer")
            sub.dispose()
        }
}
