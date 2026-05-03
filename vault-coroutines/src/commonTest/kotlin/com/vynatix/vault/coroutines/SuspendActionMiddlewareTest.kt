package com.vynatix.vault.coroutines

import com.vynatix.vault.Middleware
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MwVault : Vault<MwVault>() {
    val n by state { 0 }
}

private class CountingStartedMiddleware : Middleware<MwVault>() {
    var count = 0
    override fun onTransactionStarted(context: MiddlewareContext<MwVault>) {
        count++
    }
}

private class RecordingMiddleware(
    private val tag: String,
    private val log: MutableList<String>,
) : Middleware<MwVault>() {
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
        // Registration order is [A, B]; concentric forward: A.started -> B.started -> body
        // backward on error: B.error -> A.error
        v.middlewares(
            RecordingMiddleware("A", log),
            RecordingMiddleware("B", log),
        )

        v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertEquals(
            listOf("A.started", "B.started", "BODY", "B.error", "A.error"),
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
            listOf("A.started", "B.started", "BODY", "B.completed", "A.completed"),
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
        // A.started fires (throws but is run-caught), then B.started, then body, then completed in reverse.
        assertEquals(
            listOf("A.started", "B.started", "BODY", "B.completed", "A.completed"),
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
            listOf("A.started", "B.started", "BODY", "B.completed", "A.completed"),
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
        // B.error throws but is run-caught; A.error must still fire.
        assertEquals(
            listOf("A.started", "B.started", "BODY", "B.error", "A.error"),
            log,
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
