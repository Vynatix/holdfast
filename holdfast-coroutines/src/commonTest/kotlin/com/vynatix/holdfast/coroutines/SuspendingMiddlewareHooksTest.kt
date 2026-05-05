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

private class HooksVault : Store<HooksVault>() {
    val n by state { 0 }
}

/**
 * Middleware that records both sync and async hooks. Implements both
 * [Middleware] AND [SuspendingMiddlewareHooks] simultaneously — used to
 * assert the concentric-ring interleaving:
 * sync.started -> async.startedAsync -> body -> async.completedAsync -> sync.completed.
 */
private class DualHookMiddleware(private val tag: String, private val log: MutableList<String>) :
    Middleware<HooksVault>(),
    SuspendingMiddlewareHooks<HooksVault> {

    override fun onTransactionStarted(context: MiddlewareContext<HooksVault>) {
        log.add("$tag.sync.started")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<HooksVault>) {
        log.add("$tag.sync.completed")
    }

    override fun onTransactionError(context: MiddlewareContext<HooksVault>, error: Throwable) {
        log.add("$tag.sync.error")
    }

    override suspend fun onTransactionStartedAsync(context: Middleware.MiddlewareContext<HooksVault>) {
        // Suspend at least once to prove we're really on the suspending path.
        delay(1)
        log.add("$tag.async.startedAsync")
    }

    override suspend fun onTransactionCompletedAsync(context: Middleware.MiddlewareContext<HooksVault>) {
        delay(1)
        log.add("$tag.async.completedAsync")
    }

    override suspend fun onTransactionErrorAsync(context: Middleware.MiddlewareContext<HooksVault>, error: Throwable) {
        delay(1)
        log.add("$tag.async.errorAsync")
    }
}

/**
 * Middleware that ONLY implements [SuspendingMiddlewareHooks] (not [Middleware]).
 * Used to prove sync `action` is silent for it (no sync-hook surface), while
 * `suspendAction` invokes its async hooks.
 *
 * It must still extend [Middleware] for `store.middlewares(...)` to accept it
 * (the registration signature requires `Middleware<V>`). To express "only
 * suspending hooks", the sync hook overrides simply do nothing — the contract
 * point being that `store.action { }` neither fires async hooks nor crashes.
 */
private class AsyncOnlyMiddleware(private val tag: String, private val log: MutableList<String>) :
    Middleware<HooksVault>(),
    SuspendingMiddlewareHooks<HooksVault> {
    // No sync overrides — defaults are no-op.

    override suspend fun onTransactionStartedAsync(context: Middleware.MiddlewareContext<HooksVault>) {
        log.add("$tag.async.startedAsync")
    }

    override suspend fun onTransactionCompletedAsync(context: Middleware.MiddlewareContext<HooksVault>) {
        log.add("$tag.async.completedAsync")
    }

    override suspend fun onTransactionErrorAsync(context: Middleware.MiddlewareContext<HooksVault>, error: Throwable) {
        log.add("$tag.async.errorAsync")
    }
}

private class FailingAsyncHookMiddleware(
    private val tag: String,
    private val log: MutableList<String>,
    private val failOnStartedAsync: Boolean = false,
    private val failOnCompletedAsync: Boolean = false,
    private val failOnErrorAsync: Boolean = false,
) : Middleware<HooksVault>(),
    SuspendingMiddlewareHooks<HooksVault> {

    override suspend fun onTransactionStartedAsync(context: Middleware.MiddlewareContext<HooksVault>) {
        log.add("$tag.async.startedAsync")
        if (failOnStartedAsync) throw RuntimeException("$tag.async.startedAsync boom")
    }

    override suspend fun onTransactionCompletedAsync(context: Middleware.MiddlewareContext<HooksVault>) {
        log.add("$tag.async.completedAsync")
        if (failOnCompletedAsync) throw RuntimeException("$tag.async.completedAsync boom")
    }

    override suspend fun onTransactionErrorAsync(context: Middleware.MiddlewareContext<HooksVault>, error: Throwable) {
        log.add("$tag.async.errorAsync")
        if (failOnErrorAsync) throw RuntimeException("$tag.async.errorAsync boom")
    }
}

class SuspendingMiddlewareHooksTest {

    @Test
    fun asyncHooksDoNotFireOnSyncAction() {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(AsyncOnlyMiddleware("M", log))

        v action { /* no-op */ }

        assertTrue(
            log.isEmpty(),
            "sync action must NOT fire async hooks on a middleware that implements only SuspendingMiddlewareHooks; log=$log",
        )
    }

    @Test
    fun asyncHooksFireOnSuspendActionForAsyncOnlyMiddleware() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(AsyncOnlyMiddleware("M", log))

        v.suspendAction { log.add("BODY") }

        assertEquals(
            listOf("M.async.startedAsync", "BODY", "M.async.completedAsync"),
            log,
        )
    }

    @Test
    fun asyncErrorHookFiresOnThrowingSuspendAction() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(AsyncOnlyMiddleware("M", log))

        val r = v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertIs<TransactionResult.Error>(r)
        assertEquals(
            listOf("M.async.startedAsync", "BODY", "M.async.errorAsync"),
            log,
        )
    }

    @Test
    fun syncAndAsyncHooksInterleavePerMiddleware() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(DualHookMiddleware("M", log))

        v.suspendAction { log.add("BODY") }

        // Per design: sync.started -> async.startedAsync -> body -> async.completedAsync -> sync.completed.
        assertEquals(
            listOf(
                "M.sync.started",
                "M.async.startedAsync",
                "BODY",
                "M.async.completedAsync",
                "M.sync.completed",
            ),
            log,
        )
    }

    @Test
    fun concentricRingOrderingAcrossTwoDualHookMiddlewaresOnSuccess() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        // Registration order is [A, B]; LAST argument is outermost. The full
        // master ordering for suspendAction success path:
        //   B.sync.started, B.async.startedAsync,
        //   A.sync.started, A.async.startedAsync,
        //   BODY,
        //   A.async.completedAsync, A.sync.completed,
        //   B.async.completedAsync, B.sync.completed
        v.middlewares(
            DualHookMiddleware("A", log),
            DualHookMiddleware("B", log),
        )

        v.suspendAction { log.add("BODY") }

        assertEquals(
            listOf(
                "B.sync.started", "B.async.startedAsync",
                "A.sync.started", "A.async.startedAsync",
                "BODY",
                "A.async.completedAsync", "A.sync.completed",
                "B.async.completedAsync", "B.sync.completed",
            ),
            log,
        )
    }

    @Test
    fun concentricRingOrderingAcrossTwoDualHookMiddlewaresOnError() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(
            DualHookMiddleware("A", log),
            DualHookMiddleware("B", log),
        )

        val r = v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertIs<TransactionResult.Error>(r)
        // Error path mirrors the unwind order of completed: each middleware's
        // async hook fires before its sync hook, innermost-first.
        assertEquals(
            listOf(
                "B.sync.started", "B.async.startedAsync",
                "A.sync.started", "A.async.startedAsync",
                "BODY",
                "A.async.errorAsync", "A.sync.error",
                "B.async.errorAsync", "B.sync.error",
            ),
            log,
        )
    }

    @Test
    fun runCatchingIsolatesAsyncStartedHookFailureFromOtherMiddleware() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(
            FailingAsyncHookMiddleware("A", log, failOnStartedAsync = true),
            DualHookMiddleware("B", log),
        )

        val r = v.suspendAction { log.add("BODY") }

        assertIs<TransactionResult.Success<*>>(r)
        // B's full flow runs; A.async.startedAsync throws but is run-caught,
        // so A.async.completedAsync still fires on the success unwind.
        assertEquals(
            listOf(
                "B.sync.started",
                "B.async.startedAsync",
                "A.async.startedAsync",
                "BODY",
                "A.async.completedAsync",
                "B.async.completedAsync",
                "B.sync.completed",
            ),
            log,
        )
    }

    @Test
    fun runCatchingIsolatesAsyncCompletedHookFailureFromOtherMiddleware() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(
            DualHookMiddleware("A", log),
            FailingAsyncHookMiddleware("B", log, failOnCompletedAsync = true),
        )

        val r = v.suspendAction { log.add("BODY") }

        assertIs<TransactionResult.Success<*>>(r)
        // B.async.completedAsync throws but is run-caught; A's hooks still fire
        // and B.sync.completed still fires (B has no sync override → no log entry).
        assertEquals(
            listOf(
                "B.async.startedAsync",
                "A.sync.started",
                "A.async.startedAsync",
                "BODY",
                "A.async.completedAsync",
                "A.sync.completed",
                "B.async.completedAsync",
            ),
            log,
        )
    }

    @Test
    fun runCatchingIsolatesAsyncErrorHookFailureFromOtherMiddleware() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(
            DualHookMiddleware("A", log),
            FailingAsyncHookMiddleware("B", log, failOnErrorAsync = true),
        )

        val r = v.suspendAction {
            log.add("BODY")
            error("boom")
        }

        assertIs<TransactionResult.Error>(r)
        assertEquals(
            listOf(
                "B.async.startedAsync",
                "A.sync.started",
                "A.async.startedAsync",
                "BODY",
                "A.async.errorAsync",
                "A.sync.error",
                "B.async.errorAsync",
            ),
            log,
        )
    }

    @Test
    fun bodyMaySuspendBetweenAsyncHooks() = runBlocking {
        val v = HooksVault()
        val log = mutableListOf<String>()
        v.middlewares(DualHookMiddleware("M", log))

        v.suspendAction {
            log.add("BEFORE")
            delay(5)
            log.add("AFTER")
            n mutate 1
        }

        assertEquals(
            listOf(
                "M.sync.started",
                "M.async.startedAsync",
                "BEFORE",
                "AFTER",
                "M.async.completedAsync",
                "M.sync.completed",
            ),
            log,
        )
    }
}
