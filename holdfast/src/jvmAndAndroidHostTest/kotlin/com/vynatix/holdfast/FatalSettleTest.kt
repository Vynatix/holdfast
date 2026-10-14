@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, the work that runs after a commit: the post-commit drain
// (decision 3), the settle (decision 4), derived recomputes and the
// whole-store edges (StoreEdges.kt). A fatal error from one piece of work is
// held while the rest still runs — the rest of the drain's batch, every
// queued recompute, every other follower — and is then thrown out of the
// entry that ran the work; never reported, and nothing left open on the
// thread. PostCommitQueue.kt, SettleScope.kt, Derived.kt, DerivedStateSupport.kt,
// StoreEdges.kt.

private class FatalSettleStore : Store<FatalSettleStore>() {
    val x by state { 0 }
    val y by state { 0 }
}

private class FatalSettleHost : Store<FatalSettleHost>()

/** Keeps the inbound callback the store hands it. */
private class FatalInboundBridge : Bridge<Int> {
    var inbound: ((Int) -> Unit)? = null

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable { }
    }

    override fun publish(value: Int): Boolean = true
}

/** A settle task that logs into [log] and throws from the steps given a failure. */
private class FatalSettleTask(
    private val label: String,
    private val log: MutableList<String>,
    private val settleFails: Throwable? = null,
    private val reportFails: Throwable? = null,
) : SettleTask {
    override val settleRank: Int = 0

    override fun settle() {
        log += "$label:settle"
        settleFails?.let { throw it }
    }

    override fun deferPastSettle(report: Boolean) {
        log += "$label:deferred"
    }

    override fun reportUncaught(error: Throwable) {
        log += "$label:report"
        reportFails?.let { throw it }
    }
}

private fun Store<*>.settleReports(): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

/** Nothing an entry or a compute installs on its thread is left behind. */
private fun assertThreadSettled() {
    assertNull(SettleScopes.current(), "no settle scope is left open on this thread")
    assertNull(NoWriteRegion.current(), "no compute frame is left on this thread")
}

class FatalSettleTest {
    /** PostCommitQueue.kt:150 (decision 3): the batch finishes, the next pass waits for the next holder. */
    @Test fun aDrainedTasksStackOverflowFinishesItsBatchAndLeavesTheRestQueued() {
        val store = FatalSettleStore()
        val reported = store.settleReports()
        val fatal = StackOverflowError()
        val ran = CopyOnWriteArrayList<String>()
        val t3: () -> Unit = { ran += "t3" }
        val t1: () -> Unit = {
            ran += "t1"
            throw fatal
        }
        val t2: () -> Unit = {
            ran += "t2"
            store.handOffPostCommit(t3)
        }

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    postCommit(t1)
                    postCommit(t2)
                }
            }

        assertSame(fatal, thrown, "the action's post-release drain threw it")
        assertEquals(listOf("t1", "t2"), ran.toList(), "the rest of the batch ran; the next pass did not")
        assertEquals(emptyList(), reported.toList())
        assertThreadSettled()
        assertIs<TransactionResult.Success<*>>(store action { x mutate 1 })
        assertEquals(listOf("t1", "t2", "t3"), ran.toList(), "the store's next holder drained what was left")
    }

    /** SettleScope.kt:217 with PostCommitQueue.kt:150: a frame's drain, deferred to its settle, holds it too. */
    @Test fun aFramesDeferredDrainHoldsAStackOverflowUntilTheSettleEnds() {
        val store = FatalSettleStore()
        val reported = store.settleReports()
        val fatal = StackOverflowError()
        val ran = CopyOnWriteArrayList<String>()
        val t1: () -> Unit = {
            ran += "t1"
            throw fatal
        }
        val t2: () -> Unit = { ran += "t2" }

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(store) {
                    store {
                        x mutate 1
                        postCommit(t1)
                        postCommit(t2)
                    }
                }
            }

        assertSame(fatal, thrown, "the frame's settle threw it")
        assertEquals(listOf("t1", "t2"), ran.toList())
        assertEquals(1, store.x.value, "the frame committed")
        assertEquals(emptyList(), reported.toList())
        assertThreadSettled()
        assertNull(store.activeTransaction)
        assertIs<TransactionResult.Success<*>>(atomic(store) { store { x mutate 2 } })
    }

    /** Non-fatal control: an ordinary task failure is still swallowed by the drain. */
    @Test fun anOrdinaryDrainedTaskFailureIsStillSwallowed() {
        val store = FatalSettleStore()
        val ran = CopyOnWriteArrayList<String>()
        val result =
            store action {
                postCommit { error("task failed") }
                postCommit { ran += "t2" }
            }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(listOf("t2"), ran.toList())
    }

    /** PostCommitQueue.kt:256: the drain's cut report throws a handler's fatal error, the cut task stays queued. */
    @Test fun aHandlerThatOverflowsOnTheDrainsCutIsThrownAndTheCutStaysQueued() =
        completesWithin(60, "a task that keeps queuing itself again") {
            val store = FatalSettleStore()
            val handed = CopyOnWriteArrayList<Throwable>()
            val fatal = StackOverflowError()
            store.uncaughtObserverHandler = {
                handed += it
                throw fatal
            }
            var runs = 0
            var stop = false
            lateinit var task: () -> Unit
            task = {
                runs++
                if (!stop) {
                    store.handOffPostCommit(task)
                    store.tryTopLevelAction("hold") { x mutate runs }
                }
            }

            val thrown = assertFailsWith<StackOverflowError> { store.postCommit(task) }

            assertSame(fatal, thrown)
            assertTrue(runs in MAX_SETTLE_RUNS..MAX_SETTLE_RUNS + 3, "the drain cut the loop: $runs runs")
            assertIs<IllegalStateException>(handed.single(), "the cut was handed over once")
            assertThreadSettled()
            stop = true
            val before = runs
            assertIs<TransactionResult.Success<*>>(store action { x mutate 0 })
            assertEquals(before + 1, runs, "the cut task stayed queued for the store's next holder")
            assertEquals(1, handed.size)
        }

    /** SettleScope.kt:217 (decision 4), Derived.kt:332, NoWriteRegion.kt:130: every recompute settles first. */
    @Test fun aRecomputesStackOverflowStillSettlesEveryOtherRecomputeAndClosesTheScope() {
        val store = FatalSettleStore()
        val host = FatalSettleHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        listOf(store, host).forEach { it.uncaughtObserverHandler = { failure -> reported += failure } }
        val fatal = StackOverflowError()
        val armed = AtomicBoolean(false)
        val d1 =
            host.derivedState(store.x) {
                if (armed.get()) throw fatal
                store.x.value
            }
        val d2 = host.derivedState(store.y) { store.y.value }
        armed.set(true)

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    x mutate 1
                    y mutate 2
                }
            }

        assertSame(fatal, thrown, "the compute's fatal error, never reported")
        assertEquals(2, d2.value, "the other recompute settled")
        assertEquals(0, d1.value, "the failed recompute rolled back")
        assertEquals(1, store.x.value)
        assertEquals(2, store.y.value)
        assertEquals(emptyList(), reported.toList())
        assertThreadSettled()
        assertIs<TransactionResult.Success<*>>(host action { }, "this thread can still open an action")
        armed.set(false)
        assertIs<TransactionResult.Success<*>>(store action { x mutate 3 })
        assertEquals(3, d1.value, "a later recompute settles normally")
        d1.dispose()
        d2.dispose()
    }

    /** SettleScope.kt:243: a report that overflows is held, the settle goes on and closes. */
    @Test fun aSettleTaskWhoseReportOverflowsIsThrownOnceTheRestSettled() {
        val fatal = StackOverflowError()
        val log = CopyOnWriteArrayList<String>()
        val t1 = FatalSettleTask("t1", log, settleFails = IllegalStateException("t1"), reportFails = fatal)
        val t2 = FatalSettleTask("t2", log)

        val thrown =
            assertFailsWith<StackOverflowError> {
                settling {
                    val scope = checkNotNull(SettleScopes.current())
                    scope.enqueue(t1)
                    scope.enqueue(t2)
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf("t1:settle", "t1:report", "t2:settle"), log.toList())
        assertThreadSettled()
    }

    /** SettleScope.kt settling: a fatal error the block throws outranks the settle's, which it carries. */
    @Test fun aBlocksFatalErrorOutranksOneTheSettleThrows() {
        val blockFatal = StackOverflowError()
        val settleFatal = OutOfMemoryError()
        val log = CopyOnWriteArrayList<String>()
        val t1 = FatalSettleTask("t1", log, settleFails = settleFatal)

        val thrown =
            assertFailsWith<StackOverflowError> {
                settling {
                    checkNotNull(SettleScopes.current()).enqueue(t1)
                    throw blockFatal
                }
            }

        assertSame(blockFatal, thrown)
        assertEquals(listOf<Throwable>(settleFatal), thrown.suppressed.toList())
        assertEquals(listOf("t1:settle"), log.toList(), "the scope still settled")
        assertThreadSettled()
    }

    /** SettleScope.kt settling: a fatal error the settle throws replaces the block's ordinary failure, carrying it. */
    @Test fun aSettlesFatalErrorReplacesAnOrdinaryBlockFailure() {
        val failure = IllegalStateException("block")
        val settleFatal = StackOverflowError()
        val log = CopyOnWriteArrayList<String>()
        val t1 = FatalSettleTask("t1", log, settleFails = settleFatal)

        val thrown =
            assertFailsWith<StackOverflowError> {
                settling {
                    checkNotNull(SettleScopes.current()).enqueue(t1)
                    throw failure
                }
            }

        assertSame(settleFatal, thrown)
        assertEquals(listOf<Throwable>(failure), thrown.suppressed.toList())
        assertThreadSettled()
    }

    /** SettleScope.kt:217: a task's own fatal error is held too, never handed to its report. */
    @Test fun aSettleTasksStackOverflowIsNeverReported() {
        val fatal = StackOverflowError()
        val log = CopyOnWriteArrayList<String>()
        val t1 = FatalSettleTask("t1", log, settleFails = fatal)
        val t2 = FatalSettleTask("t2", log)

        val thrown =
            assertFailsWith<StackOverflowError> {
                settling {
                    val scope = checkNotNull(SettleScopes.current())
                    scope.enqueue(t1)
                    scope.enqueue(t2)
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf("t1:settle", "t2:settle"), log.toList())
        assertThreadSettled()
    }

    /** Derived.kt:253, StoreEdges.kt:118: a lagging hand-off's overflowing report is thrown after the others. */
    @Test fun aLaggingHandOffWhoseHandlerOverflowsStillTellsTheOtherFollowers() {
        val source = FatalSettleStore()
        val h1 = FatalSettleHost()
        val h2 = FatalSettleHost()
        val fatal = StackOverflowError()
        val h1Handed = CopyOnWriteArrayList<Throwable>()
        h1.uncaughtObserverHandler = {
            h1Handed += it
            throw fatal
        }
        val h2Reported = h2.settleReports()
        var c1 = 0
        var c2 = 0
        val n1 =
            h1.derivedStateOverStores("n1", sourceStores = listOf(source)) {
                c1++
                source.x.value
            }
        val n2 =
            h2.derivedStateOverStores("n2", sourceStores = listOf(source)) {
                c2++
                source.x.value
            }
        val (b1, b2) = c1 to c2

        val thrown =
            assertFailsWith<StackOverflowError> {
                NoWriteRegion.runCompute("probe") { source.tellStoreEdges() }
            }

        assertSame(fatal, thrown)
        assertIs<IllegalStateException>(h1Handed.single(), "h1's lag report reached its handler")
        assertIs<IllegalStateException>(h2Reported.single(), "the other follower was still told")
        assertEquals(b1 to b2, c1 to c2, "nothing recomputed inside the compute")
        assertThreadSettled()
        h1.uncaughtObserverHandler = null
        assertIs<TransactionResult.Success<*>>(h1 action { })
        assertIs<TransactionResult.Success<*>>(h2 action { })
        assertEquals(b1 + 1 to b2 + 1, c1 to c2, "each host's next holder ran the recompute handed to it")
        n1.dispose()
        n2.dispose()
    }

    /** StoreEdges.kt:118, MutableState.kt:370: every follower recomputes before one's fatal error propagates. */
    @Test fun aFollowersStackOverflowOnAnInboundWriteStillRecomputesTheOthers() {
        val source = FatalSettleStore()
        val bridge = FatalInboundBridge()
        source { x bridge bridge }
        val h1 = FatalSettleHost()
        val h2 = FatalSettleHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        listOf(source, h1, h2).forEach { it.uncaughtObserverHandler = { failure -> reported += failure } }
        val fatal = StackOverflowError()
        val armed = AtomicBoolean(false)
        val n1 =
            h1.derivedStateOverStores("n1", sourceStores = listOf(source)) {
                if (armed.get()) throw fatal
                source.x.value
            }
        val n2 = h2.derivedStateOverStores("n2", sourceStores = listOf(source)) { source.x.value }
        armed.set(true)

        val thrown = assertFailsWith<StackOverflowError> { checkNotNull(bridge.inbound)(5) }

        assertSame(fatal, thrown)
        assertEquals(5, source.x.value)
        assertEquals(5, n2.value, "the other follower recomputed")
        assertEquals(0, n1.value)
        assertEquals(emptyList(), reported.toList())
        assertThreadSettled()
        armed.set(false)
        assertIs<TransactionResult.Success<*>>(source action { x mutate 6 })
        assertEquals(6, n1.value)
        n1.dispose()
        n2.dispose()
    }

    /** StoreEdges.kt:134, DerivedStateSupport.kt:258: every follower hears of the dispose before it throws. */
    @Test fun disposingAFollowedStoreTellsEveryFollowerThenThrows() {
        val source = FatalSettleStore()
        val h1 = FatalSettleHost()
        val h2 = FatalSettleHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        listOf(source, h1, h2).forEach { it.uncaughtObserverHandler = { failure -> reported += failure } }
        val fatal = StackOverflowError()
        val armed = AtomicBoolean(false)
        var c2 = 0
        val n1 =
            h1.derivedStateOverStores("n1", sourceStores = listOf(source)) {
                if (armed.get()) throw fatal
                0
            }
        val n2 = h2.derivedStateOverStores("n2", sourceStores = listOf(source)) { ++c2 }
        val before = c2
        armed.set(true)

        val thrown = assertFailsWith<StackOverflowError> { source.dispose() }

        assertSame(fatal, thrown)
        assertTrue(source.isDisposed)
        assertEquals(emptyList(), n1.sourceStores, "the edge is gone")
        assertEquals(emptyList(), n2.sourceStores, "the edge is gone")
        assertEquals(before + 1, c2, "the other follower recomputed without the store")
        assertEquals(emptyList(), reported.toList())
        assertThreadSettled()
        source.dispose()
        armed.set(false)
        n1.dispose()
        n2.dispose()
    }

    /** DerivedStateSupport.kt:258/260: a host handler overflowing while reporting a recompute failure is thrown. */
    @Test fun aHostHandlerThatOverflowsDuringASourceDisposeIsThrownAfterTheOtherFollowers() {
        val source = FatalSettleStore()
        val h1 = FatalSettleHost()
        val h2 = FatalSettleHost()
        val fatal = StackOverflowError()
        val h1Handed = CopyOnWriteArrayList<Throwable>()
        h1.uncaughtObserverHandler = {
            h1Handed += it
            throw fatal
        }
        val h2Reported = h2.settleReports()
        val armed = AtomicBoolean(false)
        val failure = IllegalStateException("compute failed")
        var c2 = 0
        val n1 =
            h1.derivedStateOverStores("n1", sourceStores = listOf(source)) {
                if (armed.get()) throw failure
                0
            }
        val n2 = h2.derivedStateOverStores("n2", sourceStores = listOf(source)) { ++c2 }
        val before = c2
        armed.set(true)

        val thrown = assertFailsWith<StackOverflowError> { source.dispose() }

        assertSame(fatal, thrown)
        assertSame(failure, h1Handed.single(), "the ordinary failure was handed to the host's handler")
        assertEquals(before + 1, c2, "the other follower still recomputed")
        assertEquals(emptyList(), h2Reported.toList())
        assertTrue(source.isDisposed)
        h1.uncaughtObserverHandler = null
        n1.dispose()
        n2.dispose()
    }

    /** Non-fatal control: an ordinary recompute failure during a source's dispose is reported, dispose returns. */
    @Test fun anOrdinaryRecomputeFailureDuringASourceDisposeIsStillReported() {
        val source = FatalSettleStore()
        val host = FatalSettleHost()
        val reported = host.settleReports()
        val armed = AtomicBoolean(false)
        val failure = IllegalStateException("compute failed")
        val node =
            host.derivedStateOverStores("n", sourceStores = listOf(source)) {
                if (armed.get()) throw failure
                0
            }
        armed.set(true)

        source.dispose()

        assertSame(failure, reported.single())
        assertTrue(source.isDisposed)
        node.dispose()
    }
}
