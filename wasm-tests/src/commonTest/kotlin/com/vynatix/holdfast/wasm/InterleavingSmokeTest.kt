@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InterleaveCounterStore : Store<InterleaveCounterStore>() {
    val count by state { 0 }
    val label by state { "" }
    val doubled by derivedState(count) { count.value * 2 }
}

private class InterleaveBoom : RuntimeException("boom")

/** Records what [this] reports through its uncaughtObserverHandler instead of logging it. */
private fun Store<*>.interleaveFailures(): MutableList<Throwable> =
    mutableListOf<Throwable>().also { failures -> uncaughtObserverHandler = { failures += it } }

private fun interleaveAssertNothingReported(failures: List<Throwable>) =
    assertTrue(failures.isEmpty(), "reported through uncaughtObserverHandler: ${failures.map { it.message }}")

/**
 * Single-thread interleaving on wasmJs: every coroutine shares thread 0, and
 * the core's thread-local slots (frame marker, settle scope, no-write region)
 * are plain globals that `:holdfast-coroutines` carries across suspensions
 * with `slotBracketingInterceptor`. Under `runTest` the JVM runs every
 * coroutine on the one test thread as well, so it is the control for those
 * interceptor-based carriers.
 */
class InterleavingSmokeTest {
    // Two suspendActions on different stores, interleaving at delays: each
    // carries its own settle scope and settles its own derived state when it
    // ends, never inside the other (b ends at 50 while a is parked until 100).
    @Test
    fun twoSuspendActionsOnDifferentStoresInterleaveAndEachSettlesItsOwn() =
        runTest {
            val a = InterleaveCounterStore()
            val b = InterleaveCounterStore()
            val aFailures = a.interleaveFailures()
            val bFailures = b.interleaveFailures()
            val aSeen = mutableListOf<Int>()
            val bSeen = mutableListOf<Int>()
            val aSub = a.doubled effect { aSeen += this }
            val bSub = b.doubled effect { bSeen += this }
            val jobA =
                launch {
                    a.suspendAction {
                        count mutate 1
                        delay(100)
                        count mutate 2
                    }
                }
            val jobB =
                launch {
                    b.suspendAction {
                        count mutate 10
                        delay(50)
                        count mutate 20
                    }
                }
            advanceTimeBy(75)
            runCurrent()
            assertEquals(listOf(0, 40), bSeen)
            assertEquals(40, b.doubled.value)
            assertEquals(listOf(0), aSeen)
            assertEquals(0, a.doubled.value)
            jobA.join()
            jobB.join()
            assertEquals(listOf(0, 4), aSeen)
            assertEquals(2, a.count.value)
            assertEquals(20, b.count.value)
            aSub.dispose()
            bSub.dispose()
            interleaveAssertNothingReported(aFailures + bFailures)
        }

    // A suspendAtomic parked mid-body keeps its frame marker and settle scope
    // to its own body. Another coroutine's blocking action and suspendAction
    // on a store the frame did not enroll are neither refused as unenrolled
    // (Strict is the default policy) nor joined to the frame's settle: each
    // settles its derived state at its own exit. The frame settles its own
    // participant once it ends.
    @Test
    fun aSuspendAtomicParkedMidBodyDoesNotPoliceOrSettleAnotherCoroutine() =
        runTest {
            val a = InterleaveCounterStore()
            val b = InterleaveCounterStore()
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val frame =
                async {
                    suspendAtomic(a) {
                        a { count mutate 1 }
                        parked.complete(Unit)
                        gate.await()
                        a { count mutate 2 }
                    }
                }
            parked.await()
            assertIs<TransactionResult.Success<*>>(b action { count mutate 5 })
            assertEquals(10, b.doubled.value)
            assertIs<TransactionResult.Success<*>>(b.suspendAction { count mutate 6 })
            assertEquals(12, b.doubled.value)
            gate.complete(Unit)
            assertIs<TransactionResult.Success<*>>(frame.await())
            assertEquals(2, a.count.value)
            assertEquals(4, a.doubled.value)
        }

    // Documented routing (Store.stagesInto, "not isolation"): while a
    // suspendAction holds a store, a bare mutate from any caller stages into
    // its transaction instead of opening a one-shot action, and commits with
    // it: an observer sees nothing until the holder commits, then one change.
    @Test
    fun aBareMutateFromAnotherCoroutineJoinsAParkedSuspendAction() =
        runTest {
            val a = InterleaveCounterStore()
            val committed = mutableListOf<Int>()
            val sub = a.count effect { committed += this }
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val job =
                launch {
                    a.suspendAction {
                        count mutate 1
                        parked.complete(Unit)
                        gate.await()
                        label mutate "done"
                    }
                }
            parked.await()
            a { count mutate 5 }
            assertEquals(listOf(0), committed)
            gate.complete(Unit)
            job.join()
            assertEquals(listOf(0, 5), committed)
            assertEquals("done", a.label.value)
            assertEquals(10, a.doubled.value)
            sub.dispose()
        }

    // A derivedState created on a store that a parked suspendAction holds
    // with a staged write: creating it does not wait for the holder, its
    // initial compute reads the committed value (this coroutine is not the
    // parked body, issue #28), the holder's rollback leaves it there, and it
    // follows the next commit.
    @Test
    fun aDerivedStateCreatedOnAStoreAParkedSuspendActionHoldsReadsTheCommittedValue() =
        runTest {
            val a = InterleaveCounterStore()
            val failures = a.interleaveFailures()
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var result: TransactionResult<Unit>? = null
            val job =
                launch {
                    result =
                        a.suspendAction {
                            count mutate 5
                            parked.complete(Unit)
                            gate.await()
                            throw InterleaveBoom()
                        }
                }
            parked.await()
            val tripled = a.derivedState(a.count) { count.value * 3 }
            assertEquals(0, tripled.value)
            gate.complete(Unit)
            job.join()
            advanceUntilIdle()
            assertIs<InterleaveBoom>(assertIs<TransactionResult.Error>(result).exception)
            assertEquals(0, a.count.value)
            assertEquals(0, tripled.value)
            assertIs<TransactionResult.Success<*>>(a action { count mutate 2 })
            assertEquals(6, tripled.value)
            interleaveAssertNothingReported(failures)
        }

    // A derivedState hosted on a store a parked suspendAction holds, over a
    // source on another store that this coroutine commits meanwhile: the
    // recompute finds its host busy (tryTopLevelAction), is handed to the
    // host's post-commit queue, and runs once the holder releases and drains —
    // once, with the committed source value.
    @Test
    fun aDerivedStateHostedOnAParkedStoreCatchesUpWhenTheHolderReleases() =
        runTest {
            val host = InterleaveCounterStore()
            val source = InterleaveCounterStore()
            val hostFailures = host.interleaveFailures()
            val sourceFailures = source.interleaveFailures()
            var computes = 0
            val follow =
                host.derivedState(source.count) {
                    computes++
                    source.count.value + 100
                }
            val seen = mutableListOf<Int>()
            val sub = follow effect { seen += this }
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val job =
                launch {
                    host.suspendAction {
                        label mutate "held"
                        parked.complete(Unit)
                        gate.await()
                    }
                }
            parked.await()
            assertIs<TransactionResult.Success<*>>(source action { count mutate 7 })
            assertEquals(100, follow.value, "the host is held: the recompute waits for its holder")
            gate.complete(Unit)
            job.join()
            advanceUntilIdle()
            assertEquals(107, follow.value)
            assertEquals(listOf(100, 107), seen)
            assertEquals(2, computes, "the initial compute, then one recompute")
            assertEquals("held", host.label.value)
            sub.dispose()
            interleaveAssertNothingReported(hostFailures + sourceFailures)
        }

    // launch(UNDISPATCHED) of a suspendAction from inside a blocking action on
    // the same store, before any suspending entry installed the serializer:
    // the suspendAction installs it and takes it, then its wait-out
    // (awaitLockOnlyHolders) finds the action's transaction lock held, backs
    // off, and proceeds once the action has committed and released it.
    @Test
    fun anUndispatchedSuspendActionFromABlockingActionRunsAfterIt() =
        runTest {
            val store = InterleaveCounterStore()
            val failures = store.interleaveFailures()
            val testScope = this
            var inner: Job? = null
            val outer =
                store action {
                    count mutate 1
                    inner =
                        testScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            store.suspendAction { count update { it + 10 } }
                        }
                    count mutate 2
                }
            assertIs<TransactionResult.Success<*>>(outer)
            assertEquals(2, store.count.value)
            checkNotNull(inner).join()
            assertEquals(12, store.count.value)
            assertEquals(24, store.doubled.value)
            interleaveAssertNothingReported(failures)
        }

    // Same, with the serializer already installed: the blocking action holds
    // it (holdSerialized), so the inner suspendAction suspends on the mutex
    // and runs after the action, on top of its commit.
    @Test
    fun anUndispatchedSuspendActionQueuesBehindABlockingActionHoldingTheSerializer() =
        runTest {
            val store = InterleaveCounterStore()
            val failures = store.interleaveFailures()
            store.suspendAction { count mutate 1 }
            val testScope = this
            var inner: Job? = null
            val outer =
                store action {
                    inner =
                        testScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            store.suspendAction { count update { it * 10 } }
                        }
                    count mutate 3
                }
            assertIs<TransactionResult.Success<*>>(outer)
            assertEquals(3, store.count.value)
            checkNotNull(inner).join()
            assertEquals(30, store.count.value)
            assertEquals(60, store.doubled.value)
            interleaveAssertNothingReported(failures)
        }

    // On wasmJs the settle-scope carrier replaces the dispatcher as the
    // context's interceptor, and must forward the dispatcher's Delay: delay
    // and withTimeoutOrNull inside a suspendAction then run on the test's
    // virtual clock rather than real time.
    @Test
    fun delaysInsideASuspendActionUseVirtualTime() =
        runTest {
            val store = InterleaveCounterStore()
            val start = testScheduler.currentTime
            val result =
                store.suspendAction {
                    delay(10_000)
                    val late = withTimeoutOrNull(5_000) { delay(60_000) }
                    count mutate 1
                    late
                }
            assertIs<TransactionResult.Success<*>>(result)
            assertNull(result.value)
            assertEquals(15_000, testScheduler.currentTime - start)
            assertEquals(2, store.doubled.value)
        }

    // Same inside a suspendAtomic, whose frame-marker carrier wraps the
    // settle-scope one: the dispatcher's Delay must pass through both.
    @Test
    fun delaysInsideASuspendAtomicUseVirtualTime() =
        runTest {
            val a = InterleaveCounterStore()
            val b = InterleaveCounterStore()
            val start = testScheduler.currentTime
            val result =
                suspendAtomic(a, b) {
                    a { count mutate 1 }
                    delay(20_000)
                    withTimeoutOrNull(1_000) { delay(60_000) }
                    b { count mutate 2 }
                }
            assertIs<TransactionResult.Success<*>>(result)
            assertEquals(21_000, testScheduler.currentTime - start)
            assertEquals(2, a.doubled.value)
            assertEquals(4, b.doubled.value)
        }
}
