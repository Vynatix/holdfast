@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, suspendAction inside a suspendAtomic frame (SuspendAction.kt,
// suspendActionInFrame): the action is a savepoint of its store's frame root.
// A fatal error from its body or one of its middleware hooks unwinds the
// savepoint — error hooks, rollback — and is then THROWN, whatever the frame's
// policy: FramePolicy.TolerateInnerErrors tolerates an inner Error, but a fatal
// error is never one, so it reaches the frame's own unwind, which rolls every
// participant back, and propagates out of suspendAtomic.

private class FifA : Store<FifA>() {
    val x by state { 0 }
}

private class FifB : Store<FifB>() {
    val y by state { 0 }
}

/** Both stores are free again: nothing installed, and a blocking action, a suspendAction and a frame each commit. */
private suspend fun assertBothUsable(
    a: FifA,
    b: FifB,
) {
    assertNothingInstalledOn(a)
    assertNothingInstalledOn(b)
    assertNothingLeftOnThisThread()
    assertIs<TransactionResult.Success<*>>(a action { x mutate 10 })
    assertIs<TransactionResult.Success<*>>(b.suspendAction { y mutate 10 })
    val frame =
        suspendAtomic(a, b) {
            a { x mutate 11 }
            b { y mutate 11 }
        }
    assertIs<TransactionResult.Success<*>>(frame)
    assertEquals(11, a.x.value)
    assertEquals(11, b.y.value)
}

class FatalSuspendActionInFrameTest {
    @Test fun aBodyStackOverflowOfAnInFrameSuspendActionEscapesTolerateInnerErrorsAndRollsTheFrameBack() =
        runBlocking<Unit> {
            val a = FifA()
            val b = FifB()
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val hooks = FatalSyncHooks<FifA>("a", log)
            a.middlewares(hooks)
            val fatal = StackOverflowError()
            var tolerated: TransactionResult<*>? = null

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                        b.suspendAction { y mutate 1 }
                        tolerated =
                            a.suspendAction {
                                x mutate 1
                                throw fatal
                            }
                    }
                }

            assertSame(fatal, thrown, "never tolerated as an inner Error")
            assertNull(tolerated, "the in-frame suspendAction threw instead of returning an Error")
            assertEquals(0, a.x.value, "a rolled back")
            assertEquals(0, b.y.value, "b rolled back: its savepoint had merged into a root the frame rolled back")
            // The frame's own unwind may be handed a copy kotlinx's debug-mode
            // stack-trace recovery made on the way out of the in-frame action.
            assertTrue(
                hooks.errors.isNotEmpty() && hooks.errors.all { it.unrecovered() === fatal },
                "a's middleware saw the error: ${hooks.errors}",
            )
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertBothUsable(a, b)
        }

    @Test fun aNonFatalBodyFailureOfAnInFrameSuspendActionIsStillToleratedAsAnError() =
        runBlocking<Unit> {
            val a = FifA()
            val b = FifB()
            val boom = IllegalStateException("boom")
            var tolerated: TransactionResult<*>? = null

            val frame =
                suspendAtomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                    tolerated =
                        a.suspendAction {
                            x mutate 1
                            throw boom
                        }
                    b.suspendAction { y mutate 1 }
                }

            assertIs<TransactionResult.Success<*>>(frame)
            assertSame(boom, assertIs<TransactionResult.Error>(tolerated).exception)
            assertEquals(0, a.x.value, "the savepoint rolled back")
            assertEquals(1, b.y.value, "the rest of the frame committed")
            assertBothUsable(a, b)
        }

    @Test fun anAsyncErrorHookStackOverflowOfAnInFrameSuspendActionEscapesTheFrame() =
        runBlocking<Unit> {
            val a = FifA()
            val b = FifB()
            val reported = a.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val failing = FatalDualHooks<FifA>("failing", log, onErrorAsync = { throw fatal })
            val outer = FatalSyncHooks<FifA>("outer", log)
            a.middlewares(failing, outer)
            val boom = IllegalStateException("boom")

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, policy = FramePolicy.TolerateInnerErrors) {
                        a.suspendAction {
                            x mutate 1
                            throw boom
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertTrue(thrown.suppressedExceptions.any { it === boom }, "the body's failure rides along as suppressed")
            assertTrue(outer.errors.any { it === boom }, "the outer middleware still saw the savepoint's error")
            assertEquals(0, a.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertBothUsable(a, b)
        }

    @Test fun aStartedHookStackOverflowOfAnInFrameSuspendActionSkipsItsBodyAndLeavesTheStoreFree() =
        runBlocking<Unit> {
            val a = FifA()
            val b = FifB()
            val reported = a.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            // Armed inside the frame body only, so the frame root's own started
            // hooks pass and the in-frame savepoint's are the ones that throw.
            var armed = false
            val inner = FatalSyncHooks<FifA>("inner", log)
            val thrower = FatalSyncHooks<FifA>("thrower", log, onStarted = { if (armed) throw fatal })
            a.middlewares(inner, thrower)
            var ran = false

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a) {
                        armed = true
                        a.suspendAction {
                            ran = true
                            x mutate 1
                        }
                    }
                }
            armed = false

            assertSame(fatal, thrown)
            assertFalse(ran, "the in-frame body never ran")
            assertTrue(
                thrower.errors.isNotEmpty() && thrower.errors.all { it.unrecovered() === fatal },
                "the thrower saw the error: ${thrower.errors}",
            )
            assertEquals(0, a.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertBothUsable(a, b)
        }

    @Test fun aCompletedHookStackOverflowOfAnInFrameSuspendActionNeverMergesItsSavepoint() =
        runBlocking<Unit> {
            val a = FifA()
            val b = FifB()
            val reported = a.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            var armed = true
            val inner = FatalSyncHooks<FifA>("inner", log, onCompleted = { if (armed) throw fatal })
            val outer = FatalSyncHooks<FifA>("outer", log)
            a.middlewares(inner, outer)
            val seen = CopyOnWriteArrayList<Int>()
            a.x effect { seen += this }

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, policy = FramePolicy.TolerateInnerErrors) {
                        a.suspendAction { x mutate 1 }
                    }
                }
            armed = false

            assertSame(fatal, thrown)
            assertEquals(0, a.x.value, "the savepoint never merged, and the frame rolled back")
            assertEquals(listOf(0), seen.toList(), "no commit fired an observer")
            assertTrue("outer:completed" in log, "the outer completed hook still ran: $log")
            assertTrue(outer.errors.any { it === fatal }, "then the outer middleware saw the error")
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertBothUsable(a, b)
        }

    @Test fun anErrorHookStackOverflowDuringAnInFrameCancellationCarriesTheCancellationOutOfTheFrame() =
        runBlocking<Unit> {
            val a = FifA()
            val b = FifB()
            val reported = a.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val failing =
                FatalSyncHooks<FifA>("failing", log, onError = { if (it is CancellationException) throw fatal })
            val outer = FatalSyncHooks<FifA>("outer", log)
            a.middlewares(failing, outer)
            val stop = CancellationException("stop")

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a) {
                        a.suspendAction {
                            x mutate 1
                            throw stop
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertTrue(thrown.suppressedExceptions.any { it === stop }, "the cancellation rides along as suppressed")
            assertTrue(outer.errors.any { it === stop }, "the other middleware's error hook still ran")
            assertEquals(0, a.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertBothUsable(a, b)
        }
}
