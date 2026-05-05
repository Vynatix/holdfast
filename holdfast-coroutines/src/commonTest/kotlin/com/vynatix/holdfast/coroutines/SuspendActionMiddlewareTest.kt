package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MwVault : Store<MwVault>() {
    val n by state { 0 }
}

private class CountingStartedMiddleware : Middleware<MwVault>() {
    var count = 0
    override fun onTransactionStarted(context: MiddlewareContext<MwVault>) {
        count++
    }
}

private class RecordingMiddleware(private val tag: String, private val log: MutableList<String>) : Middleware<MwVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<MwVault>) {
        log.add("$tag.started")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<MwVault>) {
        log.add("$tag.completed")
    }

    override fun onTransactionError(context: MiddlewareContext<MwVault>, error: Throwable) {
        log.add("$tag.error")
    }
}

private class FailingHookMiddleware(
    private val tag: String,
    private val log: MutableList<String>,
    private val failOnStarted: Boolean = false,
    private val failOnCompleted: Boolean = false,
    private val failOnError: Boolean = false,
) : Middleware<MwVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<MwVault>) {
        log.add("$tag.started")
        if (failOnStarted) throw RuntimeException("$tag.started boom")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<MwVault>) {
        log.add("$tag.completed")
        if (failOnCompleted) throw RuntimeException("$tag.completed boom")
    }

    override fun onTransactionError(context: MiddlewareContext<MwVault>, error: Throwable) {
        log.add("$tag.error")
        if (failOnError) throw RuntimeException("$tag.error boom")
    }
}

class SuspendActionMiddlewareTest {

    @Test
    fun startedHookFiresOnSuspendAction() = runBlocking {
        val v = MwVault()
        val mw = CountingStartedMiddleware()
        v.middlewares(mw)

        v.suspendAction { /* no-op */ }

        assertEquals(1, mw.count, "onTransactionStarted must fire on suspendAction; was no-op in 1.x")
    }

    @Test
    fun completedHookFiresOnSuccessfulSuspendAction() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        v.middlewares(RecordingMiddleware("M", log))

        v.suspendAction {
            log.add("BODY")
            n mutate 1
        }

        assertEquals(listOf("M.started", "BODY", "M.completed"), log)
    }

    @Test
    fun errorHookFiresOnThrowingSuspendAction() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        v.middlewares(RecordingMiddleware("M", log))

        val r = v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertIs<TransactionResult.Error>(r)
        assertTrue("M.started" in log)
        assertTrue("M.error" in log)
        assertTrue("M.completed" !in log, "completed must not fire on error path; log=$log")
    }

    @Test
    fun concentricRingOrderingAcrossTwoMiddlewaresOnError() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        // Registration order is [A, B]; LAST argument is outermost (matches sync `action`):
        //   B.started -> A.started -> body -> A.error -> B.error
        v.middlewares(
            RecordingMiddleware("A", log),
            RecordingMiddleware("B", log),
        )

        v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertEquals(
            listOf("B.started", "A.started", "BODY", "A.error", "B.error"),
            log,
        )
    }

    @Test
    fun concentricRingOrderingAcrossTwoMiddlewaresOnSuccess() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        v.middlewares(
            RecordingMiddleware("A", log),
            RecordingMiddleware("B", log),
        )

        v.suspendAction { log.add("BODY") }

        assertEquals(
            listOf("B.started", "A.started", "BODY", "A.completed", "B.completed"),
            log,
        )
    }

    @Test
    fun runCatchingIsolatesEachStartedHookFailureFromOtherMiddleware() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        // Middleware A throws in onTransactionStarted; B must still fire its started hook,
        // body must still run, and both completed hooks must fire on the success path.
        v.middlewares(
            FailingHookMiddleware("A", log, failOnStarted = true),
            RecordingMiddleware("B", log),
        )

        val r = v.suspendAction {
            log.add("BODY")
        }

        assertIs<TransactionResult.Success<*>>(r)
        // B.started fires (B is outermost), then A.started (A throws but is run-caught),
        // then body, then completed forward (innermost-first): A.completed, B.completed.
        assertEquals(
            listOf("B.started", "A.started", "BODY", "A.completed", "B.completed"),
            log,
        )
    }

    @Test
    fun runCatchingIsolatesEachCompletedHookFailureFromOtherMiddleware() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        v.middlewares(
            RecordingMiddleware("A", log),
            FailingHookMiddleware("B", log, failOnCompleted = true),
        )

        val r = v.suspendAction { log.add("BODY") }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(
            listOf("B.started", "A.started", "BODY", "A.completed", "B.completed"),
            log,
        )
    }

    @Test
    fun runCatchingIsolatesEachErrorHookFailureFromOtherMiddleware() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        v.middlewares(
            RecordingMiddleware("A", log),
            FailingHookMiddleware("B", log, failOnError = true),
        )

        val r = v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertIs<TransactionResult.Error>(r)
        // B.error throws but is run-caught; A.error must still fire (innermost-first on error).
        assertEquals(
            listOf("B.started", "A.started", "BODY", "A.error", "B.error"),
            log,
        )
    }

    @Test
    fun middleware_ordering_is_identical_across_action_and_suspendAction() = runBlocking {
        // Defensive regression test for issue 31: same middleware registration on the
        // same store must produce the same hook trace whether the user invokes the
        // blocking `action { }` or the suspending `suspendAction { }` path. Earlier
        // 1.x had `action` using last-registered = outermost (B.started first) and
        // `suspendAction` using first-registered = outermost (A.started first), an
        // asymmetric defect. This test fails if the asymmetry ever returns.
        val v = MwVault()
        val syncLog = mutableListOf<String>()
        v.middlewares(
            RecordingMiddleware("A", syncLog),
            RecordingMiddleware("B", syncLog),
        )

        v action { syncLog.add("BODY") }

        val suspendLog = mutableListOf<String>()
        // Build a second store with the same registration so the syncLog→suspendLog
        // comparison is symmetric (same store would replay middleware state across
        // calls; clean instance keeps the comparison apples-to-apples).
        val v2 = MwVault()
        v2.middlewares(
            RecordingMiddleware("A", suspendLog),
            RecordingMiddleware("B", suspendLog),
        )

        v2.suspendAction { suspendLog.add("BODY") }

        assertEquals(
            syncLog,
            suspendLog,
            "blocking action and suspendAction must produce identical middleware ordering; sync=$syncLog suspend=$suspendLog",
        )
    }

    @Test
    fun bodyMaySuspendBetweenStartedAndCompleted() = runBlocking {
        val v = MwVault()
        val log = mutableListOf<String>()
        v.middlewares(RecordingMiddleware("M", log))

        v.suspendAction {
            log.add("BEFORE-DELAY")
            delay(10)
            log.add("AFTER-DELAY")
            n mutate 1
        }

        assertEquals(
            listOf("M.started", "BEFORE-DELAY", "AFTER-DELAY", "M.completed"),
            log,
        )
    }
}
