@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private class SettleReportSource : Store<SettleReportSource>() {
    val a by state { 0 }
}

private class SettleReportHost : Store<SettleReportHost>() {
    val h by state { 0 }
}

private class SettleReportOuter : Store<SettleReportOuter>() {
    val o by state { 0 }
}

/**
 * A settle task that throws out of [SettleTask.settle] outside any
 * transaction body — the shape of issue #26's wasmJs bug, a throw out of a
 * lock release that none of the failure paths reporting themselves could
 * reach (issue #30). Reports the way a `DerivedRecompute` does, through its
 * host.
 */
private class ThrowingSettleTask(
    private val host: Store<*>,
) : SettleTask {
    val thrown = IllegalStateException("thrown out of a settle task, outside its transaction")
    var runs = 0

    override val settleRank: Int get() = 0

    override fun settle() {
        runs++
        throw thrown
    }

    override fun deferPastSettle(report: Boolean) = Unit

    override fun reportUncaught(error: Throwable) {
        host.internalReportUncaughtFailure(error)
    }
}

/**
 * A settle task that only records that a settle ran it: the work queued
 * behind a failing one.
 */
private class RecordingSettleTask : SettleTask {
    var ran = false

    override val settleRank: Int get() = 1

    override fun settle() {
        ran = true
    }

    override fun deferPastSettle(report: Boolean) = Unit

    override fun reportUncaught(error: Throwable) = Unit
}

/**
 * The failures of a settle are reported, never dropped, and the settle keeps
 * going (issue #30): `SettleScope.settle()` used to wrap each task, and each
 * frame's store drain, in a `runCatching` whose result it discarded.
 */
class SettleFailureReportingTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    /**
     * A task that throws outside its transaction body is reported once,
     * through its host's `uncaughtObserverHandler`, and the work queued
     * after it in the same settle still runs.
     */
    @Test fun aSettleTaskThatThrowsOutsideItsTransactionIsReportedAndTheSettleContinues() {
        val host = SettleReportHost()
        val reported = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        val throwing = ThrowingSettleTask(host)
        val later = RecordingSettleTask()
        val scope = SettleScopes.open()
        assertTrue(scope.enqueue(throwing))
        assertTrue(scope.enqueue(later))

        scope.settle()

        assertEquals(1, throwing.runs)
        assertTrue(later.ran, "a failing task does not strand the work queued after it")
        assertEquals(1, reported.size, "the throw is reported once")
        assertEquals(throwing.thrown, reported.single(), "the thrown exception itself, unwrapped")
    }

    /**
     * The same throw inside a real entry's settle: the derived states queued
     * behind the failing task still recompute, and the throw alone is
     * reported — the recompute's transaction succeeded, so nothing of it
     * reaches the handler.
     */
    @Test fun aFailingSettleTaskDoesNotStrandTheDerivedStatesQueuedBehindIt() {
        val source = SettleReportSource()
        val host = SettleReportHost()
        val outer = SettleReportOuter()
        val reported = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        var computes = 0
        val doubled =
            host.derivedState(source.a) {
                computes++
                source.a.value * 2
            }
        disposables += doubled
        val throwing = ThrowingSettleTask(host)

        outer
            .action {
                val scope =
                    SettleScopes.current() ?: fail("an action's body runs inside its settle scope")
                assertTrue(scope.enqueue(throwing))
                source.action { a mutate 1 }
            }.getOrThrow()

        assertEquals(
            2,
            computes,
            "the initial compute, then the recompute the failing task did not strand",
        )
        assertEquals(2, doubled.value)
        assertEquals(1, reported.size, "the throw alone: the recompute's transaction succeeded")
        assertEquals(throwing.thrown, reported.single())
    }
}
