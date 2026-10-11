@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameInteropException
import com.vynatix.holdfast.FrameLockOrderException
import com.vynatix.holdfast.FrameObserver
import com.vynatix.holdfast.FrameObservers
import com.vynatix.holdfast.KeyedState
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.computed
import com.vynatix.holdfast.coroutines.SuspendingBridge
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.merged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class DerivedKeyedFramesLeft : Store<DerivedKeyedFramesLeft>() {
    val a by state { 0 }
    val a2 by state { 0 }
}

private class DerivedKeyedFramesRight : Store<DerivedKeyedFramesRight>() {
    val b by state { 0 }
}

private class DerivedKeyedFramesHost : Store<DerivedKeyedFramesHost>() {
    val y by state { 0 }
}

private class DerivedKeyedFramesNotes : Store<DerivedKeyedFramesNotes>() {
    val local by state { "l" }
    val remote by state { "r" }
    val shown by merged(local, remote) { l, r -> "$l+$r" }
}

private class DerivedKeyedFramesDocs : Store<DerivedKeyedFramesDocs>() {
    var initializerRuns = 0
    var loop = true
    val tick by state { 0 }
    val docs: KeyedState<String, Int> by keyedState<String, Int> { key ->
        initializerRuns++
        // "x" needs itself while its own entry is being created: a cycle,
        // caught by the latch owner check in InitializerGraph.claim before
        // the latch is touched (every thread id is 0 on wasmJs).
        if (key == "x" && loop) docs["x"].value + 1 else key.length
    }
}

/**
 * Records, in order, the id of every transaction on the store whose body
 * completed (onTransactionCompleted runs just before the commit).
 */
private class DerivedKeyedFramesLog<V : Store<V>> : Middleware<V>() {
    val completed = mutableListOf<String>()

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        completed += context.transaction.id
    }
}

/**
 * A suspending bridge whose publish suspends (a delay), then runs [during] —
 * so the rest of a suspending commit runs on a resumption.
 */
private class DerivedKeyedFramesSlowBridge(
    private val during: () -> Unit,
) : SuspendingBridge<Int> {
    val published = mutableListOf<Int>()

    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean = true

    override suspend fun publishAwaited(value: Int) {
        delay(5)
        published += value
        during()
    }
}

/**
 * wasmJs smoke tests for derivations, keyed state families and frames: legacy
 * `derived` (same-store coalescing, per-fire cross-store recompute, hand-off to
 * a busy host) and `computed`, `derivedState`/`merged` settling once per
 * outermost entry, keyed entries and evictions (including the D16 deferred
 * eviction), and `atomic`/`suspendAtomic` frames. Every test runs on the JVM
 * too, as the control: all of it is single-threaded, so the two must agree.
 */
class DerivedKeyedFramesSmokeTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
        disposables.clear()
    }

    // Legacy derived, sources on its own store: postCommit's identity dedup
    // coalesces two changed sources into one recompute. computed, by
    // contrast, runs on every read and never before one.
    @Test
    fun legacyDerivedOnItsOwnStoreRecomputesOncePerCommit() {
        val store = DerivedKeyedFramesLeft()
        var computes = 0
        val (sum, d) =
            store.derived(store.a, store.a2) {
                computes++
                a.value + a2.value
            }
        disposables += d
        var computedReads = 0
        val live: State<Int> =
            store.computed {
                computedReads++
                a.value * 10
            }
        val seen = mutableListOf<Int>()
        disposables += sum effect { seen += this }

        store action {
            a mutate 1
            a2 mutate 2
        }

        assertEquals(3, sum.value)
        assertEquals(2, computes, "the initial compute, then one recompute for the commit")
        assertEquals(listOf(0, 3), seen)
        assertEquals(0, computedReads, "computed never runs until read")
        assertEquals(10, live.value)
        assertEquals(10, live.value)
        assertEquals(2, computedReads, "computed runs on every read")
    }

    // Legacy derived, sources on another, idle store: it recomputes inline in
    // the source's fanout, once per changed source (the host has no
    // transaction for postCommit to queue it behind).
    @Test
    fun legacyDerivedOnAnIdleOtherStoreRecomputesOncePerChangedSource() {
        val source = DerivedKeyedFramesLeft()
        val host = DerivedKeyedFramesHost()
        var computes = 0
        val (sum, d) =
            host.derived(source.a, source.a2) {
                computes++
                source.a.value + source.a2.value
            }
        disposables += d
        val seen = mutableListOf<Int>()
        disposables += sum effect { seen += this }
        seen.clear()

        source action {
            a mutate 1
            a2 mutate 2
        }

        assertEquals(3, computes, "the initial compute plus one per changed source")
        assertEquals(listOf(3, 3), seen)
        assertEquals(3, sum.value)
    }

    // A source commit nested inside an action on the derived's host: the host
    // is busy, so postCommit queues the recompute on it, and the host's action
    // drains it after releasing the host.
    @Test
    fun legacyDerivedHandsItsRecomputeToABusyHostThatRunsItOnRelease() {
        val source = DerivedKeyedFramesRight()
        val host = DerivedKeyedFramesHost()
        val log = DerivedKeyedFramesLog<DerivedKeyedFramesHost>()
        host.middlewares(log)
        var computes = 0
        val (copy, d) =
            host.derived(source.b) {
                computes++
                source.b.value
            }
        disposables += d
        var inside: Int? = null

        val r =
            host action {
                source.action { b mutate 5 }.getOrThrow()
                inside = copy.value
                y mutate 1
            }

        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(0, inside, "the host was busy: no recompute inside its action")
        assertEquals(5, copy.value, "the host's action ran the queued recompute once it released the host")
        assertEquals(2, computes)
        assertEquals(2, log.completed.size, "the host's action, then the recompute: ${log.completed}")
        assertTrue(log.completed.last().startsWith("__derived_"), "${log.completed}")
    }

    // derivedState settles once per OUTERMOST entry, however many nested
    // actions on however many stores changed its sources; it never shows a torn pair.
    @Test
    fun derivedStateSettlesOncePerOutermostAction() {
        val left = DerivedKeyedFramesLeft()
        val right = DerivedKeyedFramesRight()
        val host = DerivedKeyedFramesHost()
        val outer = DerivedKeyedFramesHost()
        val log = DerivedKeyedFramesLog<DerivedKeyedFramesHost>()
        host.middlewares(log)
        var computes = 0
        val pair =
            host.derivedState(left.a, right.b) {
                computes++
                left.a.value to right.b.value
            }
        disposables += pair
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += pair effect { seen += this }
        var inside: Pair<Int, Int>? = null

        outer
            .action {
                left.action { a mutate 1 }.getOrThrow()
                right.action { b mutate 1 }.getOrThrow()
                left.action { a mutate 2 }.getOrThrow()
                inside = pair.value
                y mutate 1
            }.getOrThrow()

        assertEquals(0 to 0, inside, "nothing settles before the outermost entry exits")
        assertEquals(2 to 1, pair.value)
        assertEquals(2, computes, "the initial compute, then one recompute for the whole entry")
        assertEquals(1, log.completed.size, "one recompute transaction on the host")
        assertEquals(listOf(0 to 0, 2 to 1), seen)
        assertNull(SettleScopes.current(), "no scope outlives its entry")
    }

    // A chain of derived states settles in rank order in one pass: each
    // recomputes once, the downstream one from the upstream one's new value.
    // merged combines two declared states and refuses writes.
    @Test
    fun aDerivedChainSettlesInRankOrderAndMergedRefusesWrites() {
        val source = DerivedKeyedFramesLeft()
        val host = DerivedKeyedFramesHost()
        var firstComputes = 0
        var secondComputes = 0
        val doubled =
            host.derivedState(source.a) {
                firstComputes++
                source.a.value * 2
            }
        val total =
            host.derivedState(doubled, source.a) {
                secondComputes++
                doubled.value + source.a.value
            }
        disposables += listOf(total, doubled)
        val seen = mutableListOf<Int>()
        disposables += total effect { seen += this }

        source action { a mutate 3 }

        assertEquals(6, doubled.value)
        assertEquals(9, total.value)
        assertEquals(2, firstComputes)
        assertEquals(2, secondComputes, "the downstream state recomputed once, after its upstream")
        assertEquals(listOf(0, 9), seen, "never from the upstream's previous value")

        val notes = DerivedKeyedFramesNotes()
        assertEquals("l+r", notes.shown.value)
        notes action {
            local mutate "L"
            remote mutate "R"
        }
        assertEquals("L+R", notes.shown.value)
        val refused = assertFailsWith<IllegalStateException> { notes { shown mutate "x" } }
        assertContains(refused.message.orEmpty(), "derived state")
        assertEquals("L+R", notes.shown.value)
    }

    // A recompute's failures go to the host's uncaughtObserverHandler, never
    // into the source's commit: a throwing compute, and a compute that writes
    // (refused by its ComputingFrame). Either rolls the recompute back.
    @Test
    fun aFailingOrWritingRecomputeIsReportedAndKeepsItsLastValue() {
        val source = DerivedKeyedFramesLeft()
        val host = DerivedKeyedFramesHost()
        val reported = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        val throwing =
            host.derivedState(source.a) {
                check(source.a.value != 2) { "no twos" }
                source.a.value
            }
        disposables += throwing

        val first = source action { a mutate 2 }
        assertIs<TransactionResult.Success<Unit>>(first, "the source commit is unaffected")
        assertEquals(0, throwing.value, "a failed recompute keeps the previous value")
        assertEquals("no twos", reported.single().message)
        source action { a mutate 3 }
        assertEquals(3, throwing.value, "the next source commit recomputes normally")

        reported.clear()
        val writing =
            host.derivedState(source.a2) {
                if (source.a2.value == 1) action { y mutate 99 }
                source.a2.value
            }
        disposables += writing

        source action { a2 mutate 1 }

        assertEquals(0, writing.value, "the recompute that wrote rolled back")
        assertEquals(0, host.y.value, "its write never landed")
        val refused = assertIs<IllegalStateException>(reported.single(), "$reported")
        assertContains(refused.message.orEmpty(), "the compute of")
        source action { a2 mutate 2 }
        assertEquals(2, writing.value)
    }

    // A keyed entry is created once and reused while it lives; an eviction is
    // staged like a write — rolled back with its action, applied with its
    // commit — and leaves a stale handle that refuses writes.
    @Test
    fun keyedEntriesLiveUntilACommittedEvictionAndThenAreStaleHandles() {
        val store = DerivedKeyedFramesDocs()
        val entry = store.docs["abc"]
        assertSame(entry, store.docs["abc"])
        assertEquals(3, entry.value)
        assertEquals(1, store.initializerRuns)
        val seen = mutableListOf<Int>()
        disposables += entry effect { seen += this }
        var liveInside: Boolean? = null

        val rolledBack =
            store action {
                docs["abc"] mutate 7
                docs.evict("abc")
                liveInside = "abc" in docs
                error("roll back")
            }
        assertEquals("roll back", assertIs<TransactionResult.Error>(rolledBack).exception.message)
        assertEquals(false, liveInside, "the action reads its own staged eviction")
        assertTrue("abc" in store.docs, "a rollback discards the eviction")
        assertSame(entry, store.docs["abc"])
        assertEquals(3, entry.value, "and the write")

        store action { docs["abc"] mutate 8 }
        store action { docs.evict("abc") }

        assertFalse("abc" in store.docs)
        assertEquals(8, entry.value, "a stale handle keeps its last value")
        val stale = assertFailsWith<IllegalStateException> { store { entry mutate 9 } }
        assertContains(stale.message.orEmpty(), "stale handle")
        val fresh = store.docs["abc"]
        assertNotSame(entry, fresh)
        assertEquals(3, fresh.value)
        assertEquals(2, store.initializerRuns)
        assertEquals(listOf(3, 8), seen, "the eviction shut the entry's observer down without a last notification")
    }

    // An entry's initializer that needs its own entry is a cycle: the cycle
    // message reaches the caller (not a hang, and not a "Mutex already
    // unlocked" from the latch release, issue #26), and the key stays retryable.
    @Test
    fun aKeyedInitializerCycleThrowsAndStaysRetryable() {
        val store = DerivedKeyedFramesDocs()
        val first = assertFailsWith<IllegalStateException> { store.docs["x"] }
        assertContains(first.message.orEmpty(), "cycle")
        assertFalse("x" in store.docs, "a throwing initializer creates nothing")
        assertFailsWith<IllegalStateException> { store.docs["x"] }
        store.loop = false
        assertEquals(1, store.docs["x"].value)
        assertEquals(2, store.docs["zz"].value)
    }

    // D16: an eviction from the store's own commit fanout defers to a
    // transaction of its own (id Evict) through tryTopLevelAction.
    @Test
    fun anEvictionFromTheStoresOwnFanoutIsDeferredToAnEvictTransaction() {
        val store = DerivedKeyedFramesDocs()
        store.docs["a"]
        store.docs["b"]
        val log = DerivedKeyedFramesLog<DerivedKeyedFramesDocs>()
        store.middlewares(log)
        val during = mutableListOf<Boolean>()
        disposables +=
            store.tick effect {
                if (this == 1) {
                    store.docs.evict("a")
                    during += "a" in store.docs
                }
            }
        val reported = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { reported += it }

        val r = store action { tick mutate 1 }

        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(listOf(true), during, "inside the fanout the entry is still live")
        assertEquals(setOf("b"), store.docs.entries.keys, "evicted once the commit released the store")
        assertEquals(2, log.completed.size, "${log.completed}")
        assertEquals("Evict", log.completed.last())
        assertTrue(reported.isEmpty(), "$reported")
    }

    // atomic(left, right): all-or-nothing; an observer of one participant sees
    // the other applied and may not write it; FrameObserver brackets each
    // frame, including one that a lock-order violation fails.
    @Test
    fun anAtomicFrameIsAllOrNothingAndItsObserversSeeEveryParticipantApplied() {
        val left = DerivedKeyedFramesLeft()
        val right = DerivedKeyedFramesRight()
        val events = mutableListOf<String>()
        val frames =
            object : FrameObserver {
                override fun onFrameStarted(
                    frameId: String,
                    participants: List<Store<*>>,
                ) {
                    events += "started:${participants.size}:$frameId"
                }

                override fun onFrameCommitted(frameId: String) {
                    events += "committed:$frameId"
                }

                override fun onFrameRolledBack(
                    frameId: String,
                    cause: Throwable,
                ) {
                    events += "rolledBack:${cause.message}:$frameId"
                }
            }
        var rightSeenFromLeft: Int? = null
        var nested: TransactionResult<Unit>? = null
        disposables +=
            left.a effect {
                if (this == 1) {
                    rightSeenFromLeft = right.b.value
                    nested = right.action { b mutate 99 }
                }
            }
        FrameObservers.register(frames)
        try {
            val failed =
                atomic(left, right) {
                    left.action { a mutate 5 }
                    right.action { b mutate 5 }
                    error("abort")
                }
            assertIs<TransactionResult.Error>(failed)
            assertEquals(0, left.a.value)
            assertEquals(0, right.b.value)

            val committed =
                atomic(left, right) {
                    left.action { a mutate 1 }
                    right.action { b mutate 1 }
                    "done"
                }
            assertEquals("done", committed.getOrThrow())
            // A nested frame may only introduce stores that sort above every
            // store the enclosing frame holds (FrameMarkers, a global on wasmJs).
            assertFailsWith<FrameLockOrderException> { atomic(right) { atomic(left) { } } }
        } finally {
            FrameObservers.unregister(frames)
        }

        assertEquals(1, left.a.value)
        assertEquals(1, right.b.value)
        assertEquals(1, rightSeenFromLeft, "every participant applied before any fanned out")
        assertIs<TransactionResult.Error>(nested, "a participant's fanout may not write another participant")
        assertEquals(6, events.size, "$events")
        val firstId = events[0].substringAfterLast(":")
        val secondId = events[2].substringAfterLast(":")
        assertTrue(firstId.startsWith("atomic-") && firstId != secondId, "$events")
        assertEquals(listOf("started:2:$firstId", "rolledBack:abort:$firstId"), events.subList(0, 2))
        assertEquals(listOf("started:2:$secondId", "committed:$secondId"), events.subList(2, 4))
        assertTrue(events[4].startsWith("started:1:") && events[5].startsWith("rolledBack:"), "$events")
    }

    // A cross-store derivedState recomputes once per frame, never torn; a
    // legacy derived hosted on a participant gets its recompute from the
    // frame's deferred drain once the frame has released the store.
    @Test
    fun aFrameRecomputesCrossStoreDerivationsOnceAfterItReleases() {
        val left = DerivedKeyedFramesLeft()
        val right = DerivedKeyedFramesRight()
        val host = DerivedKeyedFramesHost()
        var computes = 0
        var sourceHeld = false
        val pair =
            host.derivedState(left.a, right.b) {
                computes++
                if (left.activeTransaction != null || right.activeTransaction != null) sourceHeld = true
                left.a.value to right.b.value
            }
        disposables += pair
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += pair effect { seen += this }
        var legacyComputes = 0
        val (onLeft, d) =
            left.derived(right.b) {
                legacyComputes++
                right.b.value * 100
            }
        disposables += d
        var legacyInside: Int? = null

        atomic(left, right) {
            left.action { a mutate 1 }
            right.action { b mutate 1 }
            legacyInside = onLeft.value
        }.getOrThrow()

        assertEquals(1 to 1, pair.value)
        assertEquals(2, computes)
        assertEquals(listOf(0 to 0, 1 to 1), seen)
        assertFalse(sourceHeld, "the recompute ran once the frame had released both stores")
        assertEquals(0, legacyInside)
        assertEquals(100, onLeft.value)
        assertEquals(2, legacyComputes)
    }

    // The wasmJs model: two coroutines on one thread. A parked suspendAction
    // keeps its settle scope (SettleAmbientContext) to itself, another
    // coroutine's entry settles at its own exit, and the parked one settles
    // its own commit once it resumes.
    @Test
    fun aParkedSuspendActionKeepsItsSettleScopeToItself() =
        runTest {
            val left = DerivedKeyedFramesLeft()
            val right = DerivedKeyedFramesRight()
            val host = DerivedKeyedFramesHost()
            val pair = host.derivedState(left.a, right.b) { left.a.value to right.b.value }
            val seen = mutableListOf<Pair<Int, Int>>()
            disposables += listOf(pair effect { seen += this }, pair)
            val parked = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var parkedScope: Any? = null
            var scopeAfterResume: Any? = null
            var scopeSeenByOther: Any? = "unset"
            var afterOtherEntry: Pair<Int, Int>? = null
            var fresh: Pair<Int, Int>? = null

            val first =
                launch {
                    left
                        .suspendAction {
                            a mutate 1
                            parkedScope = SettleScopes.current()
                            parked.complete(Unit)
                            resume.await()
                            scopeAfterResume = SettleScopes.current()
                        }.getOrThrow()
                }
            val second =
                launch {
                    parked.await()
                    scopeSeenByOther = SettleScopes.current()
                    right.action { b mutate 1 }.getOrThrow()
                    afterOtherEntry = pair.value
                    // Created while the parked action holds an uncommitted write
                    // of left.a. Today its initial compute reads that write (the
                    // parked action's owner thread is this one, issue #28), so
                    // createDerivedState queues a catch-up recompute from
                    // committed values, which runs at once: no scope is open here.
                    val created = host.derivedState(left.a, right.b) { left.a.value to right.b.value }
                    fresh = created.value
                    created.dispose()
                    resume.complete(Unit)
                }
            first.join()
            second.join()

            assertNotNull(parkedScope, "the parked action had a scope of its own")
            assertSame(parkedScope, scopeAfterResume, "its scope is installed again on resumption")
            assertNull(scopeSeenByOther, "the parked action's scope is not installed for another coroutine")
            assertEquals(0 to 1, afterOtherEntry, "settled at the other entry's exit, without the parked write")
            assertEquals(0 to 1, fresh, "a derived state created meanwhile reads committed values")
            assertEquals(1 to 1, pair.value, "the parked action settled its own commit once it resumed")
            assertEquals(listOf(0 to 0, 0 to 1, 1 to 1), seen)
            assertNull(SettleScopes.current())
        }

    // Blocking actions inside a suspendAction body, each after a suspension
    // point, join the suspending entry's scope: one recompute for the entry.
    @Test
    fun blockingActionsAfterASuspensionPointSettleWithTheSuspendAction() =
        runTest {
            val left = DerivedKeyedFramesLeft()
            val right = DerivedKeyedFramesRight()
            val outer = DerivedKeyedFramesHost()
            val host = DerivedKeyedFramesHost()
            var computes = 0
            val pair =
                host.derivedState(left.a, right.b) {
                    computes++
                    left.a.value to right.b.value
                }
            val seen = mutableListOf<Pair<Int, Int>>()
            disposables += listOf(pair effect { seen += this }, pair)
            var inside: Pair<Int, Int>? = null

            outer
                .suspendAction {
                    yield()
                    left.action { a mutate 2 }.getOrThrow()
                    delay(10)
                    right.action { b mutate 2 }.getOrThrow()
                    inside = pair.value
                }.getOrThrow()

            assertEquals(0 to 0, inside)
            assertEquals(2 to 2, pair.value)
            assertEquals(2, computes, "the initial compute, then one recompute for the whole entry")
            assertEquals(listOf(0 to 0, 2 to 2), seen)
        }

    // A legacy derived whose host a parked suspendAction holds: the source's
    // commit queues the recompute on the host (postCommit sees the suspending
    // transaction), and that holder runs it after it releases.
    @Test
    fun legacyDerivedWaitsForAHostHeldByAParkedSuspendAction() =
        runTest {
            val source = DerivedKeyedFramesRight()
            val host = DerivedKeyedFramesHost()
            var computes = 0
            val (copy, d) =
                host.derived(source.b) {
                    computes++
                    source.b.value
                }
            disposables += d
            val parked = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var whileParked: Int? = null

            val holder =
                launch {
                    host
                        .suspendAction {
                            y mutate 1
                            parked.complete(Unit)
                            resume.await()
                        }.getOrThrow()
                }
            launch {
                parked.await()
                source.action { b mutate 7 }.getOrThrow()
                whileParked = copy.value
                resume.complete(Unit)
            }.join()
            holder.join()

            assertEquals(0, whileParked, "the host was held: the recompute waited for its holder")
            assertEquals(7, copy.value, "the holder ran it after releasing the host")
            assertEquals(1, host.y.value)
            assertEquals(2, computes)
        }

    // D16 out of a suspending commit: the deferred eviction goes through
    // tryTopLevelAction, so when the next holder (a coroutine on this very
    // thread, queued on the store's serializer) has the store by then, the
    // eviction is handed to it rather than waited for: that holder can only
    // run on this thread once the wait returns. Whichever holder runs the
    // eviction, the outcome is the same.
    @Test
    fun anEvictionDeferredOutOfASuspendActionIsHandedToTheNextHolder() =
        runTest {
            val store = DerivedKeyedFramesDocs()
            store.docs["a"]
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            disposables += store.tick effect { if (this == 1) store.docs.evict("a") }
            val holding = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            val first =
                async {
                    store.suspendAction {
                        holding.complete(Unit)
                        gate.await()
                        tick mutate 1
                    }
                }
            holding.await()
            val second = async { store.suspendAction { tick mutate 2 } }
            yield() // let the second action queue on the serializer
            gate.complete(Unit)

            assertIs<TransactionResult.Success<Unit>>(first.await())
            assertIs<TransactionResult.Success<Unit>>(second.await())
            assertFalse("a" in store.docs, "evicted once the holders released the store")
            assertEquals(2, store.tick.value)
            assertTrue(reported.isEmpty(), "$reported")
        }

    // suspendAtomic: a cross-store derivedState recomputes once per frame even
    // with a suspension point between the participants' writes, and after a
    // resumption the frame marker (FrameMarkerContext) still polices the body:
    // a write to an unenrolled store, and a blocking action on a participant.
    @Test
    fun aSuspendAtomicFrameSettlesOnceAndPolicesEnrollmentAfterADelay() =
        runTest {
            val left = DerivedKeyedFramesLeft()
            val right = DerivedKeyedFramesRight()
            val host = DerivedKeyedFramesHost()
            var computes = 0
            val pair =
                host.derivedState(left.a, right.b) {
                    computes++
                    left.a.value to right.b.value
                }
            val seen = mutableListOf<Pair<Int, Int>>()
            disposables += listOf(pair effect { seen += this }, pair)

            suspendAtomic(left, right) {
                left { a mutate 1 }
                delay(10)
                right { b mutate 1 }
            }.getOrThrow()

            assertEquals(1 to 1, pair.value)
            assertEquals(2, computes)
            assertEquals(listOf(0 to 0, 1 to 1), seen)

            assertFailsWith<UnenrolledStoreException> {
                suspendAtomic(left) {
                    left { a mutate 2 }
                    delay(10)
                    host.action { y mutate 1 }
                }
            }
            assertFailsWith<FrameInteropException> {
                suspendAtomic(left) {
                    yield()
                    left.action { a mutate 3 }
                }
            }
            assertEquals(1, left.a.value, "both frames rolled back")
            assertEquals(0, host.y.value)
            assertEquals(1 to 1, pair.value)
        }

    // Keyed evictions inside atomic frames: an enrolled store's eviction rolls
    // back and commits with the frame; an unenrolled store's is refused.
    @Test
    fun keyedEvictionsFollowTheirFrameAndAnUnenrolledOneIsRefused() {
        val store = DerivedKeyedFramesDocs()
        val other = DerivedKeyedFramesHost()
        val entry = store.docs["a"]

        val rolledBack =
            atomic(store, other) {
                store.action { docs.evict("a") }
                other.action { y mutate 1 }
                error("abort")
            }
        assertEquals("abort", assertIs<TransactionResult.Error>(rolledBack).exception.message)
        assertSame(entry, store.docs.getOrNull("a"), "the frame's rollback discarded the eviction")
        assertEquals(0, other.y.value)

        assertFailsWith<UnenrolledStoreException> { atomic(other) { store.docs.evict("a") } }
        assertTrue("a" in store.docs)

        atomic(store, other) {
            store.action { docs.evict("a") }
            other.action { y mutate 2 }
        }.getOrThrow()
        assertFalse("a" in store.docs)
        assertEquals(2, other.y.value)
        val stale = assertFailsWith<IllegalStateException> { store { entry mutate 5 } }
        assertContains(stale.message.orEmpty(), "stale handle")
    }

    // A suspendAtomic's commit: every participant applies before any fans out,
    // and from anywhere inside the commit — an observer, or a suspending publish
    // after it resumed (the fanout marker's carrier) — a blocking action on any
    // participant returns an Error instead of waiting for the frame forever.
    @Test
    fun aSuspendAtomicCommitRefusesParticipantWritesAcrossASuspendingPublish() =
        runTest {
            val left = DerivedKeyedFramesLeft()
            val right = DerivedKeyedFramesRight()
            val nested = mutableListOf<String>()
            var rightSeenFromLeft: Int? = null
            var leftSeenFromRight: Int? = null
            val slow =
                DerivedKeyedFramesSlowBridge {
                    nested += "publish:" + (right.action { b mutate 77 })::class.simpleName
                }
            left { a bridge slow }
            disposables +=
                left.a effect {
                    if (this == 1) {
                        rightSeenFromLeft = right.b.value
                        nested += "left:" + (right.action { b mutate 99 })::class.simpleName
                    }
                }
            disposables +=
                right.b effect {
                    if (this == 1) {
                        leftSeenFromRight = left.a.value
                        nested += "right:" + (left.action { a mutate 99 })::class.simpleName
                    }
                }
            val events = mutableListOf<String>()
            val frames =
                object : FrameObserver {
                    override fun onFrameStarted(
                        frameId: String,
                        participants: List<Store<*>>,
                    ) {
                        events += "started:${participants.size}:${frameId.substringBefore('-')}"
                    }

                    override fun onFrameCommitted(frameId: String) {
                        events += "committed:${frameId.substringBefore('-')}"
                    }

                    override fun onFrameRolledBack(
                        frameId: String,
                        cause: Throwable,
                    ) {
                        events += "rolledBack:$cause"
                    }
                }
            FrameObservers.register(frames)
            try {
                suspendAtomic(left, right) {
                    left { a mutate 1 }
                    yield()
                    right { b mutate 1 }
                }.getOrThrow()
            } finally {
                FrameObservers.unregister(frames)
            }

            assertEquals(1, left.a.value)
            assertEquals(1, right.b.value)
            assertEquals(1, rightSeenFromLeft)
            assertEquals(1, leftSeenFromRight)
            assertEquals(listOf(1), slow.published)
            assertEquals(listOf("left:Error", "publish:Error", "right:Error"), nested)
            assertEquals(listOf("started:2:suspendAtomic", "committed:suspendAtomic"), events)
        }

    // A suspendAtomic nested in a suspendAction (after a suspension point)
    // leaves the post-commit work of the stores whose roots it opened — a
    // legacy derived hosted on a participant — to the outer entry's settle.
    @Test
    fun aSuspendAtomicNestedInASuspendActionDefersItsPostCommitWorkToTheOuterSettle() =
        runTest {
            val left = DerivedKeyedFramesLeft()
            val right = DerivedKeyedFramesRight()
            val outer = DerivedKeyedFramesHost()
            val (doubled, d) = left.derived(left.a) { a.value * 2 }
            disposables += d
            var inside = -1
            var scopeInside: Any? = null

            outer
                .suspendAction {
                    yield()
                    scopeInside = SettleScopes.current()
                    suspendAtomic(left, right) {
                        delay(1)
                        left { a mutate 1 }
                    }.getOrThrow()
                    inside = doubled.value
                }.getOrThrow()

            assertNotNull(scopeInside, "the outer entry's scope is installed after a resumption")
            assertEquals(0, inside, "the frame's drain waits for the outermost entry")
            assertEquals(2, doubled.value, "and runs when that entry settles")
        }

    // A feedback loop — an observer of a derived state writing its source on
    // another store — is cut after 1,000 recomputes in one settle, reported
    // through the host's handler, and its next recompute waits in the host's
    // post-commit queue for the host's next holder.
    @Test
    fun aDerivedStateFeedbackLoopIsCutReportedAndResumedByTheHostsNextHolder() {
        val source = DerivedKeyedFramesRight()
        val host = DerivedKeyedFramesHost()
        val reported = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        val mirror = host.derivedState(source.b) { source.b.value }
        disposables += mirror
        disposables += mirror effect { if (this in 1 until 1_200) source.action { b mutate this@effect + 1 } }

        source action { b mutate 1 }

        assertEquals(1, reported.size, "$reported")
        assertContains(reported.single().message.orEmpty(), "recomputed 1000 times in one settle")
        assertEquals(1_001, source.b.value)
        assertEquals(1_000, mirror.value, "the cut recompute waits in the host's queue")

        host action { y mutate 1 }

        assertEquals(1_200, source.b.value)
        assertEquals(1_200, mirror.value, "the host's next holder ran it, and the loop ran out")
        assertEquals(1, reported.size)
    }
}
