package com.vynatix.holdfast.coroutines.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.coroutines.asFlow
import com.vynatix.holdfast.coroutines.asStateFlow
import com.vynatix.holdfast.coroutines.first
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.observerCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class AfCounterStore : Store<AfCounterStore>() {
    val n by state { 0 }
}

/**
 * Per-collector recording slot. [seen] is appended only from inside the
 * collector coroutine (SharedFlow collection is sequential) and read only
 * after the collector's job has been joined, so the plain list needs no lock;
 * [lastSeen] is the cross-thread progress signal the main thread polls.
 */
private class AfProbe {
    val seen = mutableListOf<Int>()
    val lastSeen = AtomicInteger(-1)
    val failure = AtomicReference<Throwable?>(null)
}

/** Unstarted named daemon thread — daemon so a regression cannot outlive the failed test. */
private fun afThread(
    name: String,
    body: () -> Unit,
): Thread =
    Thread { body() }.apply {
        this.name = name
        isDaemon = true
    }

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. Same shape as `SerializerContractTest`'s watchdog:
 * a regression on the emission path (a serializer leak, a collector that
 * never receives the terminal value) would otherwise burn the module's
 * 10-minute test-task cap before reporting anything.
 */
private fun afCompletesWithin(
    seconds: Long,
    what: String,
    body: () -> Unit,
) {
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    val worker =
        afThread("af-flow-stress-probe") {
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
        fail("$what did not complete within ${seconds}s — a collector or the commit fanout is stuck")
    }
    thrown.get()?.let { throw it }
}

private fun afAssertStrictlyIncreasing(
    values: List<Int>,
    who: String,
) {
    assertTrue(values.isNotEmpty(), "$who received no values at all — the replay seed guarantees at least one")
    values.zipWithNext().forEachIndexed { index, (previous, next) ->
        assertTrue(
            previous < next,
            "$who must see strictly increasing values (conflation may skip, never reorder or rewind); " +
                "saw $previous then $next at index $index of ${values.size}",
        )
    }
}

/**
 * Non-decreasing variant for collectors that subscribe WHILE commits are in
 * flight. A subscriber racing a commit can legally see one duplicated value:
 * the initial fire inside `effect` reads the just-applied value on the
 * subscribing thread while the same commit's fanout — whose observer snapshot
 * included the fresh registration — emits it again from the committing thread.
 * Both are "the current value at subscribe time"; a rewind is still a bug.
 */
private fun afAssertNonDecreasing(
    values: List<Int>,
    who: String,
) {
    assertTrue(values.isNotEmpty(), "$who received no values at all — the replay seed guarantees at least one")
    values.zipWithNext().forEachIndexed { index, (previous, next) ->
        assertTrue(
            previous <= next,
            "$who must never see values move backwards; saw $previous then $next at index $index of ${values.size}",
        )
    }
}

/**
 * Stress pins for the `asFlow`/`asStateFlow` collector contract
 * (`StateFlows.kt`): lossless-conflated delivery backed by
 * `MutableSharedFlow(replay=1, DROP_OLDEST)` — a collector may miss
 * intermediate values but sees them in commit order and always ends on the
 * latest; a fresh collector's first value is the committed value at subscribe
 * time; the cold entry points fail fast on a disposed store (via the `effect`
 * check); and `dispose()` mid-collect silences collectors without crashing
 * them.
 */
@OptIn(StoreInternalApi::class)
class AsFlowStressTest {
    /**
     * Monotonic-subsequence pin. 1000 increments are committed through a mix
     * of blocking actions (2 raw threads) and suspendActions (2 coroutines);
     * commit fanout is fully serialized (transactionLock + the primed
     * serializer mutex), so the emission order equals the commit order and
     * every collector — fast or deliberately slow — must observe a strictly
     * increasing subsequence that ends at the final committed value. The
     * collectors subscribe before the storm, so the subscribe-race duplicate
     * documented on [afAssertNonDecreasing] cannot occur here.
     */
    @Test
    fun `collectors under a mixed write storm see strictly increasing values ending at the final commit`() {
        afCompletesWithin(25, "mixed write storm with four collectors") {
            val store = AfCounterStore()
            val counter = store.n
            assertEquals(0, counter.value) // register the state before any cross-thread traffic
            val probes = List(4) { AfProbe() }
            runBlocking {
                assertIs<TransactionResult.Success<*>>(store.suspendAction { }, "serializer prime")
                val collectorJobs =
                    probes.mapIndexed { index, probe ->
                        launch(Dispatchers.Default) {
                            counter.asFlow().collect { v ->
                                probe.seen.add(v)
                                probe.lastSeen.set(v)
                                // Two deliberately slow collectors force conflation on the replay slot.
                                if (index >= 2) delay(1)
                            }
                        }
                    }
                // Every collector must have consumed the replay seed (0) before writers start.
                withTimeout(10_000) {
                    while (probes.any { it.lastSeen.get() < 0 }) delay(5)
                }
                val threadFailure = AtomicReference<Throwable?>(null)
                val startGate = CountDownLatch(1)
                val threads =
                    (0 until 2).map { t ->
                        afThread("af-storm-blocking-$t") {
                            try {
                                startGate.await(20, TimeUnit.SECONDS)
                                repeat(250) {
                                    val r = store action { n update { v -> v + 1 } }
                                    assertIs<TransactionResult.Success<*>>(r)
                                }
                            } catch (e: Throwable) {
                                threadFailure.compareAndSet(null, e)
                            }
                        }
                    }
                threads.forEach { it.start() }
                val writerJobs =
                    (0 until 2).map {
                        async(Dispatchers.Default) {
                            repeat(250) {
                                val r = store.suspendAction { n update { v -> v + 1 } }
                                assertIs<TransactionResult.Success<*>>(r)
                            }
                        }
                    }
                startGate.countDown()
                writerJobs.awaitAll()
                threads.forEach { it.join(15_000) }
                threads.forEach { assertTrue(!it.isAlive, "blocking writer ${it.name} did not finish") }
                threadFailure.get()?.let { throw it }
                assertEquals(1000, counter.value, "1000 serialized increments must all land")
                assertNull(store.activeTransaction, "no transaction may leak past the storm")
                // Lossless-latest: every collector must drain to the final committed value.
                withTimeout(10_000) {
                    while (probes.any { it.lastSeen.get() != 1000 }) delay(5)
                }
                collectorJobs.forEach { it.cancelAndJoin() }
            }
            probes.forEachIndexed { index, probe ->
                afAssertStrictlyIncreasing(probe.seen, "collector $index")
                assertEquals(0, probe.seen.first(), "collector $index subscribed before the storm — seed must be 0")
                assertEquals(1000, probe.seen.last(), "collector $index must end on the final committed value")
            }
            // Cancellation ran each flow's finally: the underlying observers are gone.
            assertEquals(0, counter.observerCount, "every cancelled collector must dispose its observer")
        }
    }

    /**
     * Collector swarm churn: 80 subscribe/collect/cancel cycles run against a
     * 600-commit suspendAction storm. Every partial sequence must be monotonic
     * (non-decreasing — churn collectors subscribe mid-storm, see
     * [afAssertNonDecreasing]), no cycle may throw, a long-lived bystander
     * collector must stay strictly increasing and end on the final value, and
     * once everything is cancelled the observer count must return to zero —
     * no subscription may leak under churn.
     */
    @Test
    fun `collector churn during a write storm stays monotonic and disposes every observer`() {
        afCompletesWithin(25, "collector churn under a suspendAction storm") {
            val store = AfCounterStore()
            val counter = store.n
            assertEquals(0, counter.value)
            val longLived = AfProbe()
            runBlocking {
                assertIs<TransactionResult.Success<*>>(store.suspendAction { }, "serializer prime")
                val longLivedJob =
                    launch(Dispatchers.Default) {
                        counter.asFlow().collect { v ->
                            longLived.seen.add(v)
                            longLived.lastSeen.set(v)
                        }
                    }
                withTimeout(10_000) {
                    while (longLived.lastSeen.get() < 0) delay(5)
                }
                val writerJobs =
                    (0 until 2).map {
                        async(Dispatchers.Default) {
                            repeat(300) {
                                val r = store.suspendAction { n update { v -> v + 1 } }
                                assertIs<TransactionResult.Success<*>>(r)
                            }
                        }
                    }
                val churnJobs =
                    (0 until 4).map { worker ->
                        async(Dispatchers.Default) {
                            repeat(20) { cycle ->
                                val received = mutableListOf<Int>()
                                val sawValue = AtomicInteger(-1)
                                val collectorJob =
                                    launch {
                                        counter.asFlow().collect { v ->
                                            received.add(v)
                                            sawValue.set(v)
                                        }
                                    }
                                // The replay seed guarantees at least one value regardless of
                                // writer progress, so this wait is deterministic.
                                withTimeout(10_000) {
                                    while (sawValue.get() < 0) delay(1)
                                }
                                collectorJob.cancelAndJoin()
                                afAssertNonDecreasing(received, "churn collector $worker-$cycle")
                            }
                        }
                    }
                writerJobs.awaitAll()
                churnJobs.awaitAll()
                assertEquals(600, counter.value)
                withTimeout(10_000) {
                    while (longLived.lastSeen.get() != 600) delay(5)
                }
                longLivedJob.cancelAndJoin()
                afAssertStrictlyIncreasing(longLived.seen, "long-lived collector")
                assertEquals(600, longLived.seen.last())
                assertEquals(0, counter.observerCount, "80 churn cycles must not leak a single observer")
                // Post-chaos liveness: the store still commits and a fresh collector still sees it.
                assertIs<TransactionResult.Success<*>>(store action { n update { v -> v + 1 } })
                assertEquals(601, withTimeout(5_000) { counter.asFlow().first() })
            }
        }
    }

    /**
     * Initial-value pin. `asFlow` seeds its replay slot with the value at
     * subscribe time, so a brand-new collector's first emission is the
     * current committed value; `asStateFlow(scope)`'s `value` is the
     * committed value at call time and a subscriber then tracks commits.
     */
    @Test
    fun `a fresh collector receives the current committed value first`() {
        afCompletesWithin(15, "fresh-collector initial emissions") {
            val store = AfCounterStore()
            val counter = store.n
            assertEquals(0, counter.value)
            assertIs<TransactionResult.Success<*>>(store action { n mutate 7 })
            runBlocking {
                withTimeout(10_000) {
                    assertEquals(7, counter.asFlow().first(), "first emission must be the committed value")
                    assertEquals(7, counter.first { it >= 7 }, "the predicate form must resolve against the seed too")
                    val scope = CoroutineScope(Dispatchers.Default)
                    try {
                        val stateFlow = counter.asStateFlow(scope = scope)
                        assertEquals(7, stateFlow.value, "asStateFlow initial value is the committed value at call time")
                        assertIs<TransactionResult.Success<*>>(store action { n mutate 8 })
                        assertEquals(8, stateFlow.first { it == 8 }, "a subscriber must then observe the new commit")
                    } finally {
                        scope.cancel()
                    }
                }
            }
        }
    }

    /**
     * Disposed-store cold-start pin. Building the cold flow is legal, but
     * collecting it must fail fast: the flow body registers an `effect`,
     * and `effect` checks `owningStore.isDisposed` before subscribing
     * (Effect.kt) — so `first`, the predicate form, and `collect` all throw
     * `IllegalStateException("store disposed")` before delivering anything.
     * The state delegate itself also throws once the store is disposed, which
     * is why the `State` reference is captured up front.
     */
    @Test
    fun `cold flow APIs on a disposed store fail fast with store disposed`() {
        afCompletesWithin(15, "disposed-store cold-start checks") {
            val store = AfCounterStore()
            val counter = store.n
            assertIs<TransactionResult.Success<*>>(store action { n mutate 5 })
            store.dispose()
            store.dispose() // idempotent — the second call must not throw
            val cold = counter.asFlow() // cold construction is legal; only collection touches the store
            runBlocking {
                withTimeout(10_000) {
                    val fromFirst = assertFailsWith<IllegalStateException> { cold.first() }
                    assertTrue(
                        fromFirst.message?.contains("disposed") == true,
                        "first() must surface the disposed check, got: ${fromFirst.message}",
                    )
                    val fromPredicate = assertFailsWith<IllegalStateException> { counter.first { it == 5 } }
                    assertTrue(
                        fromPredicate.message?.contains("disposed") == true,
                        "first(predicate) must surface the disposed check, got: ${fromPredicate.message}",
                    )
                    val fromCollect = assertFailsWith<IllegalStateException> { cold.collect { } }
                    assertTrue(
                        fromCollect.message?.contains("disposed") == true,
                        "collect must surface the disposed check, got: ${fromCollect.message}",
                    )
                }
            }
            // The delegate read is a state-registry access and throws too.
            assertFailsWith<IllegalStateException> { store.n }
        }
    }

    /**
     * Dispose mid-collect pin. `dispose()` shuts every state down silently:
     * the collectors' observers are dropped without any notification, so
     * active collectors go silent — suspended in `emitAll` on a shared flow
     * nothing will ever emit to again. They must not crash, must not receive
     * phantom values, must remain individually cancellable by their own
     * scope, and the write path must be closed (`action` throws).
     */
    @Test
    fun `dispose during active collection silences collectors without crashing them`() {
        afCompletesWithin(20, "dispose against three active collectors") {
            val store = AfCounterStore()
            val counter = store.n
            assertEquals(0, counter.value)
            val probes = List(3) { AfProbe() }
            runBlocking {
                val collectorJobs =
                    probes.map { probe ->
                        launch(Dispatchers.Default) {
                            try {
                                counter.asFlow().collect { v ->
                                    probe.seen.add(v)
                                    probe.lastSeen.set(v)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                probe.failure.set(e)
                            }
                        }
                    }
                withTimeout(10_000) {
                    while (probes.any { it.lastSeen.get() < 0 }) delay(5)
                }
                repeat(10) {
                    assertIs<TransactionResult.Success<*>>(store action { n update { v -> v + 1 } })
                }
                // All collectors must have drained to 10 BEFORE dispose, so no fanout is in flight.
                withTimeout(10_000) {
                    while (probes.any { it.lastSeen.get() != 10 }) delay(5)
                }
                store.dispose()
                assertEquals(0, counter.observerCount, "dispose must silently drop every collector's observer")
                store.dispose() // idempotent while collectors are still suspended
                // Observation grace: a crash or a phantom post-dispose emission would surface here.
                delay(200)
                probes.forEachIndexed { index, probe ->
                    assertNull(probe.failure.get(), "collector $index must not crash when the store is disposed")
                    assertEquals(10, probe.lastSeen.get(), "collector $index must not receive values after dispose")
                }
                collectorJobs.forEachIndexed { index, job ->
                    assertTrue(job.isActive, "collector $index goes silent, not complete — its own scope ends it")
                }
                assertFailsWith<IllegalStateException> { store action { n mutate 99 } }
                // The silenced collectors remain promptly cancellable.
                collectorJobs.forEach { it.cancel() }
                withTimeout(10_000) { collectorJobs.forEach { it.join() } }
            }
            probes.forEachIndexed { index, probe ->
                afAssertStrictlyIncreasing(probe.seen, "silenced collector $index")
                assertEquals(10, probe.seen.last(), "collector $index must have ended on the last pre-dispose commit")
            }
        }
    }
}
