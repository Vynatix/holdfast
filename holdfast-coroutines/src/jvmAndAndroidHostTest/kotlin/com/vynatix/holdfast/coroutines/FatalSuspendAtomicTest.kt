@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameObserver
import com.vynatix.holdfast.FrameObservers
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// Issue #61, suspendAtomic (SuspendAtomic.kt): a fatal error from a frame
// observer, the body, a participant's middleware, a participant's fanout or the
// unwind itself never becomes a TransactionResult.Error and never reaches an
// uncaughtObserverHandler. Before the commit it fails the frame — every
// participant's error hooks, rollback and onFrameRolledBack still run — and
// propagates; during the commit every applied participant still fans out and
// the observers are told, then it propagates; after a frame committed
// (onFrameCommitted) it propagates without rolling anything back.
//
// What the body throws crosses the frame marker's coroutine boundary, where
// kotlinx.coroutines' debug-mode stack-trace recovery (on under -ea) may hand
// the unwind a copy of it: compared through unrecovered().

/** An `equals` that throws [armed] while it is set: a `distinct` state's apply pass compares with it. */
private class FatalTrap {
    @Volatile var armed: Throwable? = null
}

private class FatalTouchy(
    val n: Int,
    private val trap: FatalTrap,
) {
    override fun equals(other: Any?): Boolean {
        trap.armed?.let { throw it }
        return other is FatalTouchy && other.n == n
    }

    override fun hashCode(): Int = n
}

private class FsatA : Store<FsatA>() {
    val x by state { 0 }
}

private class FsatB(
    trap: FatalTrap = FatalTrap(),
) : Store<FsatB>() {
    val y by state { 0 }
    val t by state { FatalTouchy(0, trap) }
}

/** Records the frames over [watched] (a frame is one when a participant is), and runs an extra step per callback. */
private class FatalFrameRecorder(
    private val watched: Set<Store<*>>,
    private val onStarted: () -> Unit = {},
    private val onCommitted: () -> Unit = {},
    private val onRolledBack: (Throwable) -> Unit = {},
) : FrameObserver {
    private val frames = CopyOnWriteArrayList<String>()
    val events = CopyOnWriteArrayList<String>()
    val rolledBackCauses = CopyOnWriteArrayList<Throwable>()

    override fun onFrameStarted(
        frameId: String,
        participants: List<Store<*>>,
    ) {
        if (participants.none { it in watched }) return
        frames += frameId
        events += "started"
        onStarted()
    }

    override fun onFrameCommitted(frameId: String) {
        if (frameId !in frames) return
        events += "committed"
        onCommitted()
    }

    override fun onFrameRolledBack(
        frameId: String,
        cause: Throwable,
    ) {
        if (frameId !in frames) return
        events += "rolledBack"
        rolledBackCauses += cause
        onRolledBack(cause)
    }
}

/** Run [block] with [observers] registered, in order; they are unregistered whatever it does. */
private inline fun <R> withFrameObservers(
    vararg observers: FrameObserver,
    block: () -> R,
): R {
    observers.forEach(FrameObservers::register)
    try {
        return block()
    } finally {
        observers.forEach(FrameObservers::unregister)
    }
}

/** Every value a state's observer was handed, from its subscription's initial fire on. */
private fun <T : Any> com.vynatix.holdfast.State<T>.recorded(): MutableList<T> {
    val seen = CopyOnWriteArrayList<T>()
    effect { seen += this }
    return seen
}

/** A plain bridge recording what it was published, running [onPublish] first. */
private class FatalRecordingBridge<T : Any>(
    private val onPublish: (T) -> Unit = {},
) : Bridge<T> {
    val published = CopyOnWriteArrayList<T>()

    override fun observe(observer: (T) -> Unit): Disposable = Disposable {}

    override fun publish(value: T): Boolean {
        onPublish(value)
        published += value
        return true
    }
}

/** Both stores are free again: nothing installed, and a frame over both and a blocking action each commit. */
private suspend fun assertFramesRunAgain(
    a: FsatA,
    b: FsatB,
) {
    assertNothingInstalledOn(a)
    assertNothingInstalledOn(b)
    assertNothingLeftOnThisThread()
    val frame =
        suspendAtomic(a, b) {
            a { x mutate 100 }
            b { y mutate 100 }
        }
    assertIs<TransactionResult.Success<*>>(frame)
    assertIs<TransactionResult.Success<*>>(a action { x mutate 101 })
    assertEquals(101, a.x.value)
    assertEquals(100, b.y.value)
}

class FatalSuspendAtomicTest {
    @Test fun anOnFrameStartedStackOverflowTellsEveryObserverThenRollsTheFrameBackBeforeAnyMiddleware() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            a.middlewares(FatalSyncHooks("a", log))
            b.middlewares(FatalSyncHooks("b", log))
            val fatal = StackOverflowError()
            val first = FatalFrameRecorder(setOf(a, b), onStarted = { throw fatal })
            val second = FatalFrameRecorder(setOf(a, b))
            var ran = false

            val thrown =
                withFrameObservers(first, second) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            ran = true
                            a { x mutate 1 }
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(listOf("started", "rolledBack"), second.events.toList(), "the second observer was still told")
            assertEquals(listOf("started", "rolledBack"), first.events.toList())
            assertSame(fatal, second.rolledBackCauses.single().unrecovered())
            assertFalse(ran, "the body never ran")
            assertFalse(log.any { it.endsWith(":started") }, "no middleware started: $log")
            assertEquals(0, a.x.value)
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun aBodyStackOverflowRollsEveryParticipantBackThenPropagates() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val hooksA = FatalSyncHooks<FsatA>("a", log)
            val hooksB = FatalSyncHooks<FsatB>("b", log)
            a.middlewares(hooksA)
            b.middlewares(hooksB)
            val seenX = a.x.recorded()
            val seenY = b.y.recorded()
            val observer = FatalFrameRecorder(setOf(a, b))
            val fatal = StackOverflowError()

            val thrown =
                withFrameObservers(observer) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            a { x mutate 1 }
                            b { y mutate 2 }
                            throw fatal
                        }
                    }
                }

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertEquals(0, a.x.value)
            assertEquals(0, b.y.value)
            assertEquals(listOf(0), seenX.toList(), "no state observer fired")
            assertEquals(listOf(0), seenY.toList())
            assertSame(fatal, hooksA.errors.single().unrecovered())
            assertSame(fatal, hooksB.errors.single().unrecovered())
            assertEquals(listOf("started", "rolledBack"), observer.events.toList())
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun aNestedFrameBodyStackOverflowDiscardsBothFramesWrites() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reported = b.recordedReports()
            val fatal = StackOverflowError()

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, b) {
                        a { x mutate 1 }
                        suspendAtomic(b) {
                            b { y mutate 2 }
                            throw fatal
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(0, a.x.value)
            assertEquals(0, b.y.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertFramesRunAgain(a, b)
        }

    @Test fun aStartedHookStackOverflowRollsTheFrameBackBeforeTheBody() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            var armed = true
            val hooksB = FatalSyncHooks<FsatB>("b", log, onStarted = { if (armed) throw fatal })
            b.middlewares(hooksB)
            var ran = false

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, b) {
                        ran = true
                        a { x mutate 1 }
                    }
                }
            armed = false

            assertSame(fatal, thrown)
            assertFalse(ran, "the body never ran")
            assertEquals(0, a.x.value)
            assertSame(fatal, hooksB.errors.single().unrecovered())
            assertFramesRunAgain(a, b)
        }

    @Test fun anErrorHookStackOverflowDuringTheUnwindStillRollsBackTheOtherParticipants() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB() // later lockOrderKey: unwound first
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val hooksA = FatalSyncHooks<FsatA>("a", log)
            val hooksB = FatalSyncHooks<FsatB>("b", log, onError = { throw fatal })
            a.middlewares(hooksA)
            b.middlewares(hooksB)
            val observer = FatalFrameRecorder(setOf(a, b))
            val boom = IllegalStateException("boom")

            val thrown =
                withFrameObservers(observer) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            a { x mutate 1 }
                            b { y mutate 2 }
                            throw boom
                        }
                    }
                }

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertTrue(
                thrown.suppressedExceptions.any { it.unrecovered() === boom },
                "the body's failure rides along as suppressed",
            )
            assertEquals(listOf("b:error", "a:error"), log.filter { it.endsWith(":error") }, "b first, then a still")
            assertSame(boom, hooksA.errors.single().unrecovered())
            assertEquals(0, a.x.value, "a was still rolled back")
            assertEquals(0, b.y.value)
            assertSame(
                boom,
                observer.rolledBackCauses.single().unrecovered(),
                "the observers were told, with the frame's cause",
            )
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun anErrorHookStackOverflowDuringACancellationUnwindCarriesTheCancellation() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val hooksA = FatalSyncHooks<FsatA>("a", log)
            b.middlewares(FatalSyncHooks("b", log, onError = { throw fatal }))
            a.middlewares(hooksA)
            val stop = CancellationException("stop")

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, b) {
                        a { x mutate 1 }
                        throw stop
                    }
                }

            assertSame(fatal, thrown)
            assertTrue(
                thrown.suppressedExceptions.any { it.unrecovered() === stop },
                "the cancellation rides along as suppressed",
            )
            assertSame(stop, hooksA.errors.single().unrecovered())
            assertEquals(0, a.x.value)
            assertFramesRunAgain(a, b)
        }

    @Test fun anOnFrameRolledBackStackOverflowStillTellsTheOtherObservers() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val fatal = StackOverflowError()
            val first = FatalFrameRecorder(setOf(a, b), onRolledBack = { throw fatal })
            val second = FatalFrameRecorder(setOf(a, b))
            val boom = IllegalStateException("boom")

            val thrown =
                withFrameObservers(first, second) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            a { x mutate 1 }
                            throw boom
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertTrue(thrown.suppressedExceptions.any { it.unrecovered() === boom })
            assertSame(boom, second.rolledBackCauses.single().unrecovered(), "the second observer was still told")
            assertEquals(0, a.x.value)
            assertFramesRunAgain(a, b)
        }

    @Test fun aCompletedHookStackOverflowRollsEveryParticipantBackAndNothingCommits() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            var armed = true
            val hooksA = FatalSyncHooks<FsatA>("a", log)
            val hooksB = FatalSyncHooks<FsatB>("b", log, onCompleted = { if (armed) throw fatal })
            a.middlewares(hooksA)
            b.middlewares(hooksB)
            val seenX = a.x.recorded()
            val seenY = b.y.recorded()
            val observer = FatalFrameRecorder(setOf(a, b))

            val thrown =
                withFrameObservers(observer) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            a { x mutate 1 }
                            b { y mutate 2 }
                        }
                    }
                }
            armed = false

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertEquals(0, a.x.value, "nothing committed")
            assertEquals(0, b.y.value)
            assertEquals(listOf(0), seenX.toList(), "no state observer fired")
            assertEquals(listOf(0), seenY.toList())
            assertSame(fatal, hooksA.errors.single().unrecovered(), "both participants' middleware saw the error")
            assertSame(fatal, hooksB.errors.single().unrecovered())
            assertEquals(listOf("started", "rolledBack"), observer.events.toList(), "rolled back, never committed")
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun aFanoutStackOverflowLetsTheLaterParticipantsFanOutWhileEveryCommitStands() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val fatal = StackOverflowError()
            a.x effect { if (this == 1) throw fatal }
            val seenY = b.y.recorded()
            val bridgeY = FatalRecordingBridge<Int>()
            b { y bridge bridgeY }
            val observer = FatalFrameRecorder(setOf(a, b))

            val thrown =
                withFrameObservers(observer) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            a { x mutate 1 }
                            b { y mutate 2 }
                        }
                    }
                }

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertEquals(1, a.x.value, "a's commit stands")
            assertEquals(2, b.y.value, "b's commit stands")
            assertEquals(listOf(0, 2), seenY.toList(), "b fanned out after a's fanout failed")
            assertEquals(listOf(2), bridgeY.published.toList(), "and published")
            assertEquals(listOf("started", "rolledBack"), observer.events.toList(), "no onFrameCommitted")
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun anApplyStackOverflowLetsTheAppliedParticipantsFanOutThenPropagates() =
        runBlocking<Unit> {
            val trap = FatalTrap()
            val a = FsatA()
            val b = FsatB(trap)
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val fatal = StackOverflowError()
            val seenX = a.x.recorded()

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, b) {
                        a { x mutate 1 }
                        b { t mutate FatalTouchy(1, trap) }
                        // b's apply compares its distinct state's values: armed now.
                        trap.armed = fatal
                    }
                }
            trap.armed = null

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertEquals(1, a.x.value, "a applied before b failed, and stands")
            assertEquals(listOf(0, 1), seenX.toList(), "a fanned out")
            assertEquals(0, b.t.value.n, "b never applied")
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun aBridgeStackOverflowOutranksALaterParticipantsPublishTimeout() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reportedA = a.recordedReports()
            val fatal = StackOverflowError()
            var armed = true
            a { x bridge FatalRecordingBridge { if (armed) throw fatal } }
            b {
                y bridge
                    object : SuspendingBridge<Int> {
                        override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

                        override fun publish(value: Int): Boolean = true

                        override suspend fun publishAwaited(value: Int) {
                            if (armed) withTimeout(10.milliseconds) { awaitCancellation() }
                        }
                    }
            }

            val thrown =
                assertPropagates<StackOverflowError> {
                    suspendAtomic(a, b) {
                        a { x mutate 1 }
                        b { y mutate 2 }
                    }
                }
            armed = false

            assertSame(fatal, thrown, "the fatal error outranks the cancellation")
            assertTrue(
                thrown.suppressedExceptions.any { it is TimeoutCancellationException },
                "the timed-out publish rides along as suppressed: ${thrown.suppressedExceptions}",
            )
            assertEquals(1, a.x.value)
            assertEquals(2, b.y.value)
            assertTrue(reportedA.isEmpty(), "never reported: $reportedA")
            assertFramesRunAgain(a, b)
        }

    @Test fun anOnFrameCommittedStackOverflowPropagatesFromAFrameThatCommitted() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val reportedA = a.recordedReports()
            val reportedB = b.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            a.middlewares(FatalSyncHooks("a", log))
            b.middlewares(FatalSyncHooks("b", log))
            val seenX = a.x.recorded()
            val seenY = b.y.recorded()
            val fatal = StackOverflowError()
            val first = FatalFrameRecorder(setOf(a, b), onCommitted = { throw fatal })
            val second = FatalFrameRecorder(setOf(a, b))

            val thrown =
                withFrameObservers(first, second) {
                    assertPropagates<StackOverflowError> {
                        suspendAtomic(a, b) {
                            a { x mutate 1 }
                            b { y mutate 2 }
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(1, a.x.value, "committed")
            assertEquals(2, b.y.value)
            assertEquals(listOf(0, 1), seenX.toList())
            assertEquals(listOf(0, 2), seenY.toList())
            assertEquals(listOf("started", "committed"), second.events.toList(), "told, and never rolled back")
            assertEquals(listOf("started", "committed"), first.events.toList())
            assertEquals(
                listOf("a:started", "b:started", "a:completed", "b:completed"),
                log.toList(),
                "every middleware completed, none errored",
            )
            assertTrue(reportedA.isEmpty() && reportedB.isEmpty(), "never reported: $reportedA $reportedB")
            assertFramesRunAgain(a, b)
        }

    @Test fun aNonFatalOnFrameCommittedFailureStillLeavesTheFrameASuccess() =
        runBlocking<Unit> {
            val a = FsatA()
            val b = FsatB()
            val first = FatalFrameRecorder(setOf(a, b), onCommitted = { error("observer") })
            val second = FatalFrameRecorder(setOf(a, b))

            val frame =
                withFrameObservers(first, second) {
                    suspendAtomic(a, b) {
                        a { x mutate 1 }
                        b { y mutate 2 }
                    }
                }

            assertIs<TransactionResult.Success<*>>(frame)
            assertEquals(listOf("started", "committed"), second.events.toList())
            assertFramesRunAgain(a, b)
        }
}
