package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for issue 12 — `SuspendingBridge<T>.publishAwaited` and the
 * `suspendAction` commit-phase interpose.
 *
 * The defining contract: same `SuspendingKvStore` used to manufacture both a
 * `bridge(...)` (fire-and-forget) and a `suspendingBridge(...)` (await-completion).
 *
 *  - Inside `store.action { ... }`, both behave fire-and-forget — `publish`
 *    returns immediately and the persistence write happens off-thread.
 *  - Inside `store.suspendAction { ... }`, the suspendingBridge's
 *    `publishAwaited` is awaited under `withContext(NonCancellable)` while
 *    the regular bridge still publishes fire-and-forget.
 *
 * Caller picks the action type to pick the persistence guarantee.
 */
private class TwoFieldVault : Store<TwoFieldVault>() {
    val s by state { "init" }
    val n by state { 0 }
}

class SuspendingBridgePublishAwaitedTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
    }

    /**
     * The defining test from the issue's acceptance criteria.
     *
     * Same `SuspendingKvStore`. One state binds a `bridge(...)` (fire-and-forget),
     * the other binds a `suspendingBridge(...)` (await-completion).
     *
     * Inside `suspendAction`, the suspendingBridge's value is fully written to the
     * store BEFORE the action returns. The fire-and-forget bridge is allowed to
     * race — we don't assert on its timing here.
     */
    @Test
    fun suspendActionAwaitsSuspendingBridgeButNotPlainBridge() = runBlocking {
        val store = SlowSuspendingKvStore(delayMs = 50)
        val plain = store.bridge("plain", StringCodec, scope = testScope)
        val awaiting = store.suspendingBridge("awaiting", IntCodec, scope = testScope)

        val v = TwoFieldVault()
        v.action {
            s bridge plain
            n bridge awaiting
        }

        val r = v.suspendAction {
            s mutate "alice"
            n mutate 42
        }
        assertIs<TransactionResult.Success<*>>(r)

        // suspendingBridge: awaited — value is in the store right now, no
        // advanceUntilIdle / launch races involved.
        assertEquals(IntCodec.encode(42), store.get("awaiting"))
    }

    /**
     * Inside `store.action { }` (sync), the suspendingBridge falls back to its
     * default `publish` — `scope.launch { publishAwaited(value) }; return true`.
     * The action returns BEFORE the persistence write completes. We verify by
     * checking that the store does NOT yet have the value immediately after
     * action returns (the slow store buys us the window).
     */
    @Test
    fun syncActionDoesNotAwaitSuspendingBridge() = runBlocking {
        val store = SlowSuspendingKvStore(delayMs = 200)
        val awaiting = store.suspendingBridge("k", IntCodec, scope = testScope)

        val v = TwoFieldVault()
        v.action {
            n bridge awaiting
        }

        // Sync action returns immediately; the suspending publish is still in flight.
        v.action { n mutate 7 }

        // Right now: not yet persisted.
        assertNull(store.get("k"))

        // Eventually: persisted.
        val deadline = withTimeoutOrNull(2_000) {
            while (store.get("k") != IntCodec.encode(7)) {
                delay(20)
            }
        }
        assertTrue(deadline != null, "fire-and-forget eventually persists")
    }

    /**
     * Under `suspendAction`, `publishAwaited` runs in `NonCancellable`. Even if
     * the calling coroutine is cancelled mid-commit (after body return, during
     * the bridge publish phase), the write completes and the store is consistent.
     */
    @Test
    fun publishAwaitedRunsInNonCancellable() = runBlocking {
        val store = SlowSuspendingKvStore(delayMs = 300)
        val awaiting = store.suspendingBridge("k", IntCodec, scope = testScope)

        val v = TwoFieldVault()
        v.action { n bridge awaiting }

        val outerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val job = outerScope.launch {
            v.suspendAction {
                n mutate 99
                started.complete(Unit)
                // Body returns immediately; commit phase is what we want to cancel through.
            }
        }
        started.await()
        // Give the commit phase a moment to start its NonCancellable section.
        delay(30)
        job.cancel()
        // Wait for the cancellation handler to finish — NonCancellable means
        // commit fanout still completes despite the cancel.
        withTimeoutOrNull(2_000) {
            while (store.get("k") != IntCodec.encode(99)) {
                delay(20)
            }
            Unit
        }
        assertEquals(IntCodec.encode(99), store.get("k"))
        outerScope.coroutineContext[Job]?.cancel()
        Unit
    }

    /**
     * `publishAwaited` is invoked sequentially — observers fire first, then
     * persistence completes. Verify by capturing the order in a shared list.
     */
    @Test
    fun observersFireBeforePublishAwaitedCompletes() = runBlocking {
        val sequence = mutableListOf<String>()
        val store = object : SuspendingKvStore {
            private val map = mutableMapOf<String, String>()
            override suspend fun get(key: String): String? = map[key]
            override suspend fun put(key: String, value: String) {
                sequence += "store.put"
                map[key] = value
            }
            override suspend fun remove(key: String) {
                map.remove(key)
            }
            override suspend fun snapshot(): Map<String, String> = map.toMap()
        }
        val awaiting = store.suspendingBridge("k", IntCodec, scope = testScope)
        val v = TwoFieldVault()
        v.action { n bridge awaiting }
        v {
            n.effect { sequence += "observer:$this" }
        }

        v.suspendAction { n mutate 5 }

        // Observer initial fire is "observer:0" (from effect-attach). Then commit
        // fanout: observer fires for the new value, then store.put.
        assertTrue(
            sequence.containsAll(listOf("observer:0", "observer:5", "store.put")),
            "expected all three events; saw $sequence",
        )
        val obsAtFive = sequence.indexOf("observer:5")
        val putAt = sequence.indexOf("store.put")
        assertTrue(obsAtFive < putAt, "observer:5 must fire before store.put — saw $sequence")
    }

    /**
     * Plain `bridge(...)` from a `SuspendingKvStore` retains fire-and-forget
     * semantics under `suspendAction` too — only `SuspendingBridge` triggers
     * the await interpose. This guards against an over-eager `is`-check.
     */
    @Test
    fun suspendActionDoesNotAwaitPlainBridge() = runBlocking {
        val store = SlowSuspendingKvStore(delayMs = 250)
        val plain = store.bridge("k", StringCodec, scope = testScope)

        val v = TwoFieldVault()
        v.action { s bridge plain }

        v.suspendAction { s mutate "fast" }

        // Right now: not yet persisted (plain bridge fires-and-forgets in suspendAction too).
        assertNull(store.get("k"))

        // Eventually persists.
        withTimeoutOrNull(2_000) {
            while (store.get("k") != StringCodec.encode("fast")) {
                delay(20)
            }
        }
        assertEquals(StringCodec.encode("fast"), store.get("k"))
    }
}

/**
 * Issue 30 acceptance: a `distinct = true` state bound to a `SuspendingBridge`
 * does not republish when `suspendAction` re-applies the same value.
 *
 * The sync `store.action { }` path already short-circuits both observer fanout
 * and bridge publish on dedup via [com.vynatix.holdfast.MutableState.applyCommitted]'s
 * single-pass dedup check. The suspending `suspendingCommit` dispatcher must
 * match: if `applyCommittedRaw` returns false (deduped), the (state, value)
 * pair must NOT be enqueued for the bridge publish phase.
 */
private class DistinctVault : Store<DistinctVault>() {
    val s by state(distinct = true) { "init" }
}

/**
 * Probe-style [SuspendingKvStore] (issue 30 acceptance variant) that records
 * exactly how many times `put` was called per key. The `<= 2` style assertion
 * used elsewhere only proves the count is bounded; an `== 1` probe
 * distinguishes "dedup short-circuited the publish" from "dedup happened but
 * publish landed anyway". Distinct from the per-key-aggregate counter in
 * `SuspendingKvBridgeTest.kt` only to avoid file-private name shadowing.
 */
private class DedupCountingSuspendingKvStore : SuspendingKvStore {
    private val mutex = Mutex()
    private val map = mutableMapOf<String, String>()
    val puts = mutableMapOf<String, Int>()

    override suspend fun get(key: String): String? = mutex.withLock { map[key] }

    override suspend fun put(key: String, value: String) = mutex.withLock {
        puts[key] = (puts[key] ?: 0) + 1
        map[key] = value
    }

    override suspend fun remove(key: String) = mutex.withLock {
        map.remove(key)
        Unit
    }
    override suspend fun snapshot(): Map<String, String> = mutex.withLock { map.toMap() }
}

class SuspendingBridgeDedupTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun distinctTrueStateWithSuspendingBridgeUnderSuspendActionDoesNotRepublishOnDedup() = runBlocking {
        val store = DedupCountingSuspendingKvStore()
        val awaiting = store.suspendingBridge("myKey", StringCodec, scope = testScope)

        val v = DistinctVault()
        v.action { s bridge awaiting }

        // First commit: value changes "init" -> "x". Apply succeeds, publish lands.
        v.suspendAction { s mutate "x" }
        // Second commit: "x" -> "x". Dedup short-circuits both observer fanout
        // (already in MutableState.applyCommittedRaw) and the bridge publish
        // (the fix in suspendingCommit's dispatcher).
        v.suspendAction { s mutate "x" }

        // Exact: one put for the first apply, none for the deduped second.
        assertEquals(1, store.puts["myKey"], "expected exactly one put; saw ${store.puts}")
    }
}

/**
 * Suspending KV store with a deliberate per-operation delay. Used to widen
 * the window between "action returns" and "persistence completes" so the
 * `publishAwaited` vs `publish` distinction is observable in tests.
 */
private class SlowSuspendingKvStore(private val delayMs: Long) : SuspendingKvStore {
    private val mutex = Mutex()
    private val map = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = mutex.withLock { map[key] }

    override suspend fun put(key: String, value: String) {
        delay(delayMs)
        mutex.withLock { map[key] = value }
    }

    override suspend fun remove(key: String) {
        mutex.withLock { map.remove(key) }
    }
    override suspend fun snapshot(): Map<String, String> = mutex.withLock { map.toMap() }
}
