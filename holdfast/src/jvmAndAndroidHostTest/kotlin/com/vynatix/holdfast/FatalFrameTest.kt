@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, atomic frames (Atomic.kt executeBody, FrameCommit.kt commitFrame,
// Store.kt's FrameMiddlewareSession.fireError): a fatal error anywhere in a
// frame — the body, a middleware hook, a participant's apply or fanout, a
// FrameObserver callback — is never folded into an Error. The frame first
// finishes what keeps its participants consistent: every open root rolls back
// after its error hooks, every applied participant fans out, every frame
// observer is told; then the first fatal error is thrown.

private class FatalFrameX : Store<FatalFrameX>() {
    val a by state { 0 }
}

private class FatalFrameY : Store<FatalFrameY>() {
    val equalsFails = AtomicReference<Throwable?>(null)
    val a by state { 0 }
    val t by state { FatalFrameTouchy(0, equalsFails) }
}

/** A value whose equals throws what [equalsFails] holds while it is set. */
private class FatalFrameTouchy(
    val n: Int,
    private val equalsFails: AtomicReference<Throwable?>,
) {
    override fun equals(other: Any?): Boolean {
        equalsFails.get()?.let { throw it }
        return other is FatalFrameTouchy && other.n == n
    }

    override fun hashCode(): Int = n
}

/** Logs `label:hook` (with the error's identity) into a log shared across stores. */
private class FatalFrameHooks<V : Store<V>>(
    private val label: String,
    private val log: MutableList<String>,
    private val errors: MutableList<Throwable> = mutableListOf(),
    private val onCompleted: () -> Unit = {},
    private val onError: () -> Unit = {},
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "$label:started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        log += "$label:completed"
        onCompleted()
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        log += "$label:error"
        errors += error
        onError()
    }
}

/** Records every callback as `label:event`, and throws from the one [failsOn] names. */
private class FatalRecordingFrameObserver(
    private val label: String,
    private val log: MutableList<String>,
    private val failsOn: String? = null,
    private val failure: Throwable? = null,
) : FrameObserver {
    val causes = mutableListOf<Throwable>()

    override fun onFrameStarted(
        frameId: String,
        participants: List<Store<*>>,
    ) = record("started")

    override fun onFrameCommitted(frameId: String) = record("committed")

    override fun onFrameRolledBack(
        frameId: String,
        cause: Throwable,
    ) {
        causes += cause
        record("rolledBack")
    }

    private fun record(event: String) {
        log += "$label:$event"
        if (event == failsOn) throw checkNotNull(failure)
    }
}

/** What reaches any of [stores]' uncaughtObserverHandlers from now on. */
private fun reportsOf(vararg stores: Store<*>): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    stores.forEach { it.uncaughtObserverHandler = { failure -> seen += failure } }
    return seen
}

class FatalFrameTest {
    private val observers = mutableListOf<FrameObserver>()

    @AfterTest fun unregisterObservers() {
        observers.forEach(FrameObservers::unregister)
    }

    private fun register(vararg observer: FrameObserver) {
        observer.forEach {
            observers += it
            FrameObservers.register(it)
        }
    }

    /** Atomic.kt:341/356 and :180: every root unwinds in reverse lock order, every observer hears of it. */
    @Test fun aFrameBodyStackOverflowUnwindsEveryRootThenPropagates() {
        val x = FatalFrameX()
        val y = FatalFrameY()
        val reported = reportsOf(x, y)
        val log = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        x.middlewares(FatalFrameHooks("x", log, errors))
        y.middlewares(FatalFrameHooks("y", log, errors))
        val frameLog = mutableListOf<String>()
        val observer = FatalRecordingFrameObserver("o", frameLog)
        register(observer)
        val fatal = StackOverflowError()

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x, y) {
                    x { a mutate 1 }
                    y { a mutate 1 }
                    throw fatal
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf("x:started", "y:started", "y:error", "x:error"), log, "reverse lock order unwind")
        assertTrue(errors.all { it === fatal } && errors.size == 2)
        assertEquals(listOf("o:started", "o:rolledBack"), frameLog)
        assertSame(fatal, observer.causes.single())
        assertEquals(0, x.a.value)
        assertEquals(0, y.a.value)
        assertNull(x.activeTransaction)
        assertNull(y.activeTransaction)
        assertNull(FrameMarkers.current(), "the frame marker was restored")
        assertNull(SettleScopes.current())
        assertEquals(emptyList(), reported.toList())
        assertIs<TransactionResult.Success<*>>(atomic(x, y) { x { a mutate 2 } })
        assertIs<TransactionResult.Success<*>>(y action { a mutate 3 }, "a store outside any frame is not refused")
        assertEquals(2, x.a.value)
        assertEquals(3, y.a.value)
    }

    /** Atomic.kt:180: a frame with no write still restores the active slot and the marker. */
    @Test fun aStackOverflowLeavesNoFrameMarkerBehind() {
        val x = FatalFrameX()
        val outsider = FatalFrameY()
        val fatal = StackOverflowError()

        assertSame(fatal, assertFailsWith<StackOverflowError> { atomic(x) { throw fatal } })

        assertNull(x.activeTransaction)
        assertNull(FrameMarkers.current())
        assertIs<TransactionResult.Success<*>>(outsider action { a mutate 1 }, "not refused as unenrolled")
        assertIs<TransactionResult.Success<*>>(x action { a mutate 1 })
    }

    /** Store.kt:1901: every error hook of the frame runs, then the fatal error one threw wins. */
    @Test fun anErrorHooksStackOverflowStillRunsTheOtherHooksAndRollsBack() {
        val x = FatalFrameX()
        val y = FatalFrameY()
        val log = mutableListOf<String>()
        val fatal = StackOverflowError()
        x.middlewares(FatalFrameHooks("x1", log, onError = { throw fatal }), FatalFrameHooks("x2", log))
        y.middlewares(FatalFrameHooks("y3", log))
        val failure = IllegalStateException("body failed")

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x, y) {
                    y { a mutate 1 }
                    throw failure
                }
            }

        assertSame(fatal, thrown)
        assertTrue(thrown.suppressed.any { it === failure }, "the frame's own failure rides along")
        assertEquals(
            listOf("y3:error", "x1:error", "x2:error"),
            log.filter { it.endsWith(":error") },
            "every error hook ran, in reverse lock order, innermost first",
        )
        assertEquals(0, y.a.value)
        assertNull(x.activeTransaction)
        assertNull(y.activeTransaction)
    }

    /** Atomic.kt:341: never commit after a fatal error — one from a completed hook rolls every root back. */
    @Test fun aCompletedHookStackOverflowRollsTheWholeFrameBack() {
        val x = FatalFrameX()
        val y = FatalFrameY()
        val reported = reportsOf(x, y)
        val log = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val fatal = StackOverflowError()
        x.middlewares(FatalFrameHooks("x", log, errors))
        y.middlewares(FatalFrameHooks("y", log, errors, onCompleted = { throw fatal }))
        val frameLog = mutableListOf<String>()
        register(FatalRecordingFrameObserver("o", frameLog))
        val xSeen = mutableListOf<Int>()
        val xEffect = x.a effect { xSeen += this }

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x, y) {
                    x { a mutate 1 }
                    y { a mutate 2 }
                }
            }

        assertSame(fatal, thrown)
        assertEquals(
            listOf("x:started", "y:started", "x:completed", "y:completed", "y:error", "x:error"),
            log,
            "every root got its error hook, in reverse lock order",
        )
        assertTrue(errors.all { it === fatal } && errors.size == 2)
        assertEquals(listOf("o:started", "o:rolledBack"), frameLog)
        assertEquals(0, x.a.value, "nothing committed")
        assertEquals(0, y.a.value)
        assertEquals(listOf(0), xSeen, "no observer heard of a commit")
        assertNull(x.activeTransaction)
        assertNull(y.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        xEffect.dispose()
    }

    /** Atomic.kt:319: an onFrameStarted fatal error rolls back before any hook or the body. */
    @Test fun anOnFrameStartedStackOverflowRollsTheFrameBackBeforeAnyHookOrTheBody() {
        val x = FatalFrameX()
        val log = mutableListOf<String>()
        x.middlewares(FatalFrameHooks("x", log))
        val fatal = StackOverflowError()
        val frameLog = mutableListOf<String>()
        register(
            FatalRecordingFrameObserver("o1", frameLog, failsOn = "started", failure = fatal),
            FatalRecordingFrameObserver("o2", frameLog),
        )
        var bodyRan = false

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x) {
                    bodyRan = true
                    x { a mutate 7 }
                }
            }

        assertSame(fatal, thrown)
        assertFalse(bodyRan)
        assertEquals(listOf("o1:started", "o2:started", "o1:rolledBack", "o2:rolledBack"), frameLog)
        assertEquals(emptyList(), log, "no middleware hook fired: none saw the frame start")
        assertEquals(0, x.a.value)
        assertNull(x.activeTransaction)
        assertNull(FrameMarkers.current())
    }

    /** Atomic.kt:358: every observer hears the rollback, then the fatal error one threw wins. */
    @Test fun anOnFrameRolledBackStackOverflowStillTellsTheOtherObservers() {
        val x = FatalFrameX()
        val fatal = StackOverflowError()
        val frameLog = mutableListOf<String>()
        val second = FatalRecordingFrameObserver("o2", frameLog)
        register(FatalRecordingFrameObserver("o1", frameLog, failsOn = "rolledBack", failure = fatal), second)
        val failure = IllegalStateException("body failed")

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x) {
                    x { a mutate 1 }
                    throw failure
                }
            }

        assertSame(fatal, thrown, "not an Error result")
        assertTrue(thrown.suppressed.any { it === failure })
        assertEquals(listOf("o1:started", "o2:started", "o1:rolledBack", "o2:rolledBack"), frameLog)
        assertSame(failure, second.causes.single())
        assertEquals(0, x.a.value)
        assertNull(x.activeTransaction)
    }

    /** Atomic.kt:368: a committed frame stays committed; no observer is told it rolled back. */
    @Test fun anOnFrameCommittedStackOverflowKeepsTheFrameCommitted() {
        val x = FatalFrameX()
        val log = mutableListOf<String>()
        x.middlewares(FatalFrameHooks("x", log))
        val fatal = StackOverflowError()
        val frameLog = mutableListOf<String>()
        register(
            FatalRecordingFrameObserver("o1", frameLog, failsOn = "committed", failure = fatal),
            FatalRecordingFrameObserver("o2", frameLog),
        )

        val thrown = assertFailsWith<StackOverflowError> { atomic(x) { x { a mutate 7 } } }

        assertSame(fatal, thrown)
        assertEquals(listOf("o1:started", "o2:started", "o1:committed", "o2:committed"), frameLog)
        assertEquals(listOf("x:started", "x:completed"), log)
        assertEquals(7, x.a.value)
        assertNull(x.activeTransaction)
    }

    /** Non-fatal control: an ordinary FrameObserver failure is still dropped and the frame commits. */
    @Test fun anOrdinaryFrameObserverFailureIsStillDropped() {
        val x = FatalFrameX()
        val frameLog = mutableListOf<String>()
        register(
            FatalRecordingFrameObserver("o1", frameLog, failsOn = "started", failure = IllegalStateException("started")),
            FatalRecordingFrameObserver("o2", frameLog, failsOn = "committed", failure = IllegalStateException("done")),
        )

        assertIs<TransactionResult.Success<*>>(atomic(x) { x { a mutate 1 } })

        assertEquals(listOf("o1:started", "o2:started", "o1:committed", "o2:committed"), frameLog)
        assertEquals(1, x.a.value)
    }

    /** FrameCommit.kt:407: every applied participant still fans out, then the fatal error is thrown. */
    @Test fun aParticipantsFanoutStackOverflowStillFansOutTheOthers() {
        val x = FatalFrameX()
        val y = FatalFrameY()
        val reported = reportsOf(x, y)
        val fatal = StackOverflowError()
        var armed = false
        val throwing = x.a effect { if (armed) throw fatal }
        val ySeen = mutableListOf<Int>()
        val yEffect = y.a effect { ySeen += this }
        val frameLog = mutableListOf<String>()
        register(FatalRecordingFrameObserver("o", frameLog))
        armed = true

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x, y) {
                    x { a mutate 1 }
                    y { a mutate 2 }
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf(0, 2), ySeen, "the later participant still fanned out")
        assertEquals(1, x.a.value)
        assertEquals(2, y.a.value)
        assertEquals(listOf("o:started", "o:rolledBack"), frameLog, "as for an ordinary fanout failure")
        assertNull(x.activeTransaction)
        assertNull(y.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        throwing.dispose()
        yEffect.dispose()
    }

    /** FrameCommit.kt:454 in a frame: the participant applied before the failing one fans out, the rest unwind. */
    @Test fun aParticipantsApplyStackOverflowFansOutTheAppliedOnesAndFailsRaw() {
        val x = FatalFrameX()
        val y = FatalFrameY()
        assertTrue(x.lockOrderKey < y.lockOrderKey, "x applies first")
        val reported = reportsOf(x, y)
        val xSeen = mutableListOf<Int>()
        val xEffect = x.a effect { xSeen += this }
        assertEquals(0, y.t.value.n)
        val fatal = StackOverflowError()
        y.equalsFails.set(fatal)

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(x, y) {
                    x { a mutate 1 }
                    y { t mutate FatalFrameTouchy(1, equalsFails) }
                }
            }

        assertSame(fatal, thrown, "raw, never a TransactionException")
        assertEquals(listOf(0, 1), xSeen, "the participant that applied fanned out")
        assertEquals(1, x.a.value)
        y.equalsFails.set(null)
        assertEquals(0, y.t.value.n, "the failing participant applied nothing")
        assertNull(x.activeTransaction)
        assertNull(y.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        assertIs<TransactionResult.Success<*>>(atomic(x, y) { y { t mutate FatalFrameTouchy(2, equalsFails) } })
        xEffect.dispose()
    }

    /** Atomic.kt:341 nested in an action: the frame and the enclosing action both roll back. */
    @Test fun aFrameStackOverflowInsideAnActionRollsBackBoth() {
        val outer = FatalFrameY()
        val x = FatalFrameX()
        val fatal = StackOverflowError()

        val thrown =
            assertFailsWith<StackOverflowError> {
                outer action {
                    a mutate 5
                    atomic(x) {
                        x { a mutate 1 }
                        throw fatal
                    }
                }
            }

        assertSame(fatal, thrown)
        assertEquals(0, outer.a.value)
        assertEquals(0, x.a.value)
        assertNull(outer.activeTransaction)
        assertNull(x.activeTransaction)
        assertNull(FrameMarkers.current())
        assertIs<TransactionResult.Success<*>>(outer action { a mutate 1 })
    }
}
