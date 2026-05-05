package com.vynatix.holdfast

import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.vaultTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class MiddlewareTestVault : Store<MiddlewareTestVault>() {
    val n by state { 0 }
    val m by state { "init" }
}

private class GlobalRecordingMiddleware(private val tag: String, private val events: MutableList<String>) :
    Middleware<MiddlewareTestVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<MiddlewareTestVault>) {
        events.add("$tag:started")
    }
    override fun onTransactionCompleted(context: MiddlewareContext<MiddlewareTestVault>) {
        events.add("$tag:completed")
    }
    override fun onTransactionError(context: MiddlewareContext<MiddlewareTestVault>, error: Throwable) {
        events.add("$tag:error")
    }
}

private class MetadataMiddleware : Middleware<MiddlewareTestVault>() {
    var metadataInCompleted: Any? = null
    var metadataInStarted: Any? = null
    override fun onTransactionStarted(context: MiddlewareContext<MiddlewareTestVault>) {
        metadataInStarted = context.metadata["seen"]
        context.metadata["seen"] = "value-set-in-started"
    }
    override fun onTransactionCompleted(context: MiddlewareContext<MiddlewareTestVault>) {
        metadataInCompleted = context.metadata["seen"]
    }
}

private class ContextCapturingMiddleware : Middleware<MiddlewareTestVault>() {
    var capturedVault: MiddlewareTestVault? = null
    var capturedTransaction: Transaction? = null
    override fun onTransactionStarted(context: MiddlewareContext<MiddlewareTestVault>) {
        capturedVault = context.store
        capturedTransaction = context.transaction
    }
}

private class ThrowingOnStartedMiddleware(val message: String) : Middleware<MiddlewareTestVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<MiddlewareTestVault>): Unit = throw RuntimeException(message)
}

class MiddlewareLifecycleTest {

    @Test
    fun onTransactionStartedRunsBeforeActionBlock() = vaultTest {
        val events = mutableListOf<String>()
        val v = MiddlewareTestVault().apply { middlewares(GlobalRecordingMiddleware("M", events)) }
        track(v).action {
            events.add("BLOCK")
            n mutate 1
        }.shouldBeSuccess()
        assertEquals("M:started", events.first())
        assertTrue(events.indexOf("M:started") < events.indexOf("BLOCK"), "onTransactionStarted must precede the action block")
    }

    @Test
    fun onTransactionCompletedRunsAfterSuccessfulActionBlock() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("M", events))

        v action {
            events.add("BLOCK")
            n mutate 1
        }

        assertEquals("M:completed", events.last())
        val blockIdx = events.indexOf("BLOCK")
        val completedIdx = events.indexOf("M:completed")
        assertTrue(blockIdx < completedIdx, "onTransactionCompleted must follow the action block")
    }

    @Test
    fun onTransactionErrorRunsWhenActionBlockThrowsAndExceptionIsRethrown() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("M", events))

        val result = v action {
            events.add("BLOCK")
            error("intentional")
        }

        assertIs<TransactionResult.Error>(result)
        assertEquals("intentional", result.exception.message)
        assertTrue("M:started" in events)
        assertTrue("BLOCK" in events)
        assertTrue("M:error" in events)
        assertTrue("M:completed" !in events, "completed must NOT fire when action throws")
    }

    @Test
    fun middlewareReceivesContextWithVaultAndTransaction() {
        val v = MiddlewareTestVault()
        val capture = ContextCapturingMiddleware()
        v.middlewares(capture)

        val result = v action { n mutate 1 }

        assertIs<TransactionResult.Success<*>>(result)
        assertSame(v, capture.capturedVault)
        assertNotNull(capture.capturedTransaction)
        assertSame(result.transaction, capture.capturedTransaction)
    }

    @Test
    fun middlewareMetadataMapIsMutableAndPropagatesToOnCompleted() {
        val v = MiddlewareTestVault()
        val mw = MetadataMiddleware()
        v.middlewares(mw)

        v action { n mutate 1 }

        assertEquals(null, mw.metadataInStarted)
        assertEquals(
            "value-set-in-started",
            mw.metadataInCompleted,
            "metadata written in onStarted must be visible in onCompleted of the same context",
        )
    }
}

class MiddlewareChainTest {

    @Test
    fun multipleMiddlewaresExecuteInLastRegisteredFirstOrder() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(
            GlobalRecordingMiddleware("A", events),
            GlobalRecordingMiddleware("B", events),
            GlobalRecordingMiddleware("C", events),
        )

        v action {
            events.add("BLOCK")
            n mutate 1
        }

        assertEquals(
            listOf(
                "C:started",
                "B:started",
                "A:started",
                "BLOCK",
                "A:completed",
                "B:completed",
                "C:completed",
            ),
            events,
            "last-registered runs outermost; first-registered runs innermost",
        )
    }

    @Test
    fun middlewareThrowingInOnStartedShortCircuitsActionAndCausesRollback() {
        val v = MiddlewareTestVault()
        v action { n mutate 5 }
        assertEquals(5, v.n.value)

        v.middlewares(ThrowingOnStartedMiddleware("middleware refused"))
        val result = v action { n mutate 99 }

        assertIs<TransactionResult.Error>(result)
        assertEquals(
            5,
            v.n.value,
            "mutation must roll back when middleware throws in onTransactionStarted",
        )
    }

    @Test
    fun clearMiddlewareDropsAllRegisteredMiddlewaresForFutureActions() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("M", events))

        v action { n mutate 1 }
        assertTrue(events.isNotEmpty())
        events.clear()

        v.clearMiddleware()
        v action { n mutate 2 }
        assertTrue(events.isEmpty(), "after clearMiddleware, no middleware events for new actions")
    }
}

class MiddlewareSnapshotTest {

    @Test
    fun middlewareAddedDuringAnInProgressActionDoesNotApplyToCurrentAction() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        val newMw = GlobalRecordingMiddleware("LATE", events)

        v action {
            v.middlewares(newMw)
            n mutate 1
        }

        assertEquals(
            emptyList(),
            events,
            "middleware registered during an action must not retro-apply to that action",
        )
    }

    @Test
    fun middlewareSnapshotInsideActionDoesNotSeeConcurrentRegistrations() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("EARLY", events))

        v action {
            v.middlewares(GlobalRecordingMiddleware("LATE", events))
            n mutate 1
        }

        // EARLY was registered before action; runs.
        // LATE was registered during action; must NOT run for this action.
        assertTrue(events.any { it.startsWith("EARLY:") }, "EARLY must run; events=$events")
        assertTrue(events.none { it.startsWith("LATE:") }, "LATE must not run for the in-flight action; events=$events")
    }
}

class MiddlewareImplicitTransactionTest {

    @Test
    fun middlewareFiresForImplicitTransactionCreatedByMutateOutsideAction() {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("M", events))

        v { n mutate 42 }

        assertEquals(42, v.n.value)
        assertTrue("M:started" in events, "middleware must fire for implicit transaction; events=$events")
        assertTrue("M:completed" in events, "middleware must complete; events=$events")
    }
}

class MiddlewareConcurrentRegistrationTest {

    @Test
    fun concurrentMiddlewaresRegisterAndClearDuringInFlightActionsDoNotCorruptList() = runBlocking {
        val v = MiddlewareTestVault()
        val workers = 8
        val opsPerWorker = 50

        val jobs = List(workers) { workerId ->
            async(Dispatchers.Default) {
                repeat(opsPerWorker) {
                    when (workerId % 4) {
                        0 -> v.middlewares(GlobalRecordingMiddleware("w$workerId-$it", mutableListOf()))
                        1 -> v.clearMiddleware()
                        2 -> v action { n mutate it }
                        3 -> v { n.value }
                    }
                }
            }
        }
        jobs.awaitAll()

        // The store should still be usable: register a single fresh middleware and run an action.
        v.clearMiddleware()
        val finalEvents = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("FINAL", finalEvents))
        v action { n mutate 999 }
        assertEquals(listOf("FINAL:started", "FINAL:completed"), finalEvents)
        assertEquals(999, v.n.value)
    }

    @Test
    fun clearMiddlewareCalledFromOneCoroutineDoesNotAffectActionInProgressOnAnotherCoroutine() = runBlocking {
        val v = MiddlewareTestVault()
        val events = mutableListOf<String>()
        v.middlewares(GlobalRecordingMiddleware("M", events))

        // Run many actions in parallel while another coroutine clears middleware.
        // The action's middleware snapshot is taken at the start of the action under
        // transactionLock; concurrent clearMiddleware can't tear that snapshot.
        val opsPerWorker = 100
        val actorJobs = List(4) {
            async(Dispatchers.Default) {
                repeat(opsPerWorker) {
                    runCatching { v action { n mutate it } }
                }
            }
        }
        val clearJobs = List(2) {
            async(Dispatchers.Default) {
                repeat(opsPerWorker) {
                    v.clearMiddleware()
                }
            }
        }
        (actorJobs + clearJobs).awaitAll()

        // Final state: store is consistent; final action runs without error
        v.clearMiddleware()
        v action { n mutate 0 }
        assertEquals(0, v.n.value)
    }
}
