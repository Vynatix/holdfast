@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, suspendAction's mutex path (SuspendAction.kt): a fatal error (here
// an explicit StackOverflowError) thrown by the body or by a middleware hook
// fails the transaction — every middleware's error hooks run, it rolls back,
// it never commits after a fatal `completed` hook — and then propagates out of
// suspendAction as it is: never a TransactionResult.Error, never handed to
// the uncaughtObserverHandler. One an error hook throws is thrown once the
// rest of the unwind ran. The store is never left held (#37's wedge shape).

private class FsaStore : Store<FsaStore>() {
    val x by state { 0 }
    val y by state { 0 }
}

/** Logs every sync hook as `label:hook`, keeps the errors it was handed and the transaction it saw. */
internal open class FatalSyncHooks<V : Store<V>>(
    val label: String,
    val log: MutableList<String>,
    private val onStarted: () -> Unit = {},
    private val onCompleted: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : Middleware<V>() {
    val errors = CopyOnWriteArrayList<Throwable>()

    @Volatile var transaction: Transaction? = null

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        transaction = context.transaction
        log += "$label:started"
        onStarted()
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
        onError(error)
    }
}

/** [FatalSyncHooks] plus the async siblings ([SuspendingMiddlewareHooks]), logged as `label:hookAsync`. */
internal class FatalDualHooks<V : Store<V>>(
    label: String,
    log: MutableList<String>,
    private val onStartedAsync: () -> Unit = {},
    private val onCompletedAsync: () -> Unit = {},
    private val onErrorAsync: (Throwable) -> Unit = {},
) : FatalSyncHooks<V>(label, log),
    SuspendingMiddlewareHooks<V> {
    val asyncErrors = CopyOnWriteArrayList<Throwable>()

    override suspend fun onTransactionStartedAsync(context: Middleware.MiddlewareContext<V>) {
        log += "$label:startedAsync"
        onStartedAsync()
    }

    override suspend fun onTransactionCompletedAsync(context: Middleware.MiddlewareContext<V>) {
        log += "$label:completedAsync"
        onCompletedAsync()
    }

    override suspend fun onTransactionErrorAsync(
        context: Middleware.MiddlewareContext<V>,
        error: Throwable,
    ) {
        log += "$label:errorAsync"
        asyncErrors += error
        onErrorAsync(error)
    }
}

/**
 * The throwable [this] stands for: itself, or — when kotlinx.coroutines'
 * debug-mode stack-trace recovery (on under `-ea`, as in every test JVM)
 * replaced it with a copy on its way across a coroutine boundary — the
 * original that copy carries as its cause. Production code (no `-ea`) never
 * sees such a copy, so the original is what the library threw.
 */
internal fun Throwable.unrecovered(): Throwable {
    var current = this
    while (true) {
        val cause = current.cause ?: return current
        val recovered = current.stackTrace.any { it.className.startsWith("_COROUTINE.") }
        if (cause.javaClass != current.javaClass || !recovered) return current
        current = cause
    }
}

/** [assertFailsWith] [T], answering the throwable the library threw ([unrecovered]). */
internal inline fun <reified T : Throwable> assertPropagates(block: () -> Unit): Throwable {
    val thrown = assertFailsWith<T>(block = block)
    return thrown.unrecovered()
}

/** What reaches this store's uncaughtObserverHandler from now on. */
internal fun Store<*>.recordedReports(): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

/** Nothing an entry installs on its thread is left behind: no settle scope, frame marker or fanout marker. */
internal fun assertNothingLeftOnThisThread() {
    assertNull(SettleScopes.current(), "no settle scope is left open on this thread")
    assertNull(FrameMarkers.current(), "no frame marker is left on this thread")
    assertNull(FanoutMarkers.current(), "no fanout marker is left on this thread")
}

/** No transaction or suspending owner is left installed on [store]. */
internal fun assertNothingInstalledOn(store: Store<*>) {
    assertNull(store.activeTransaction, "no transaction is left installed on ${store::class.simpleName}")
    assertNull(store.suspendingOwner, "no suspending owner is left installed on ${store::class.simpleName}")
}

/**
 * Nothing the failed entry took is left behind — and the store takes a
 * blocking action at once (no wait, no StoreSuspendInFlightException, #37), a
 * bare write outside any action and a new suspendAction.
 */
private suspend fun FsaStore.assertNotWedged() {
    assertNothingInstalledOn(this)
    assertNothingLeftOnThisThread()
    val before = y.value
    assertIs<TransactionResult.Success<*>>(this action { y mutate before + 1 })
    // `with`, not a bare `this { }`: a bare-invoke mutation is refused (issue 005).
    with(this) { y mutate before + 2 }
    assertIs<TransactionResult.Success<*>>(suspendAction { y mutate before + 3 })
    assertEquals(before + 3, y.value)
}

class FatalSuspendActionTest {
    @Test fun aBodyStackOverflowRunsEveryErrorHookAndRollsBackThenPropagates() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val dual = FatalDualHooks<FsaStore>("dual", log)
            val plain = FatalSyncHooks<FsaStore>("plain", log)
            store.middlewares(dual, plain)
            val fatal = StackOverflowError()

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        throw fatal
                    }
                }

            assertSame(fatal, thrown, "the body's own StackOverflowError propagates, not a wrapper")
            assertEquals(0, store.x.value, "rolled back")
            assertEquals(
                listOf(
                    "plain:started",
                    "dual:started",
                    "dual:startedAsync",
                    "dual:errorAsync",
                    "dual:error",
                    "plain:error",
                ),
                log.toList(),
                "every error hook ran, the async one before its sync sibling, innermost first",
            )
            assertSame(fatal, dual.asyncErrors.single())
            assertSame(fatal, dual.errors.single())
            assertSame(fatal, plain.errors.single())
            assertEquals(TransactionStatus.RolledBack, plain.transaction?.status)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            store.assertNotWedged()
        }

    @Test fun aNonFatalBodyFailureIsStillAnErrorResult() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val plain = FatalSyncHooks<FsaStore>("plain", log)
            store.middlewares(plain)
            val boom = IllegalStateException("boom")

            val result =
                store.suspendAction {
                    x mutate 1
                    throw boom
                }

            assertSame(boom, assertIs<TransactionResult.Error>(result).exception)
            assertEquals(0, store.x.value)
            assertEquals(listOf("plain:started", "plain:error"), log.toList())
            assertTrue(reported.isEmpty())
            store.assertNotWedged()
        }

    @Test fun anErrorHookStackOverflowDuringACancellationUnwindCarriesTheCancellation() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val inner = FatalSyncHooks<FsaStore>("inner", log, onError = { throw fatal })
            val outer = FatalSyncHooks<FsaStore>("outer", log)
            store.middlewares(inner, outer)
            val stop = CancellationException("stop")

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        throw stop
                    }
                }

            assertSame(fatal, thrown)
            assertTrue(thrown.suppressedExceptions.any { it === stop }, "the cancellation rides along as suppressed")
            assertEquals(listOf("outer:started", "inner:started", "inner:error", "outer:error"), log.toList())
            assertSame(stop, outer.errors.single(), "the outer error hook still ran, with the cancellation")
            assertEquals(TransactionStatus.RolledBack, outer.transaction?.status)
            assertEquals(0, store.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            store.assertNotWedged()
        }

    @Test fun anAsyncErrorHookStackOverflowLetsTheRestOfTheUnwindRunThenPropagates() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val failing = FatalDualHooks<FsaStore>("failing", log, onErrorAsync = { throw fatal })
            val outer = FatalDualHooks<FsaStore>("outer", log)
            store.middlewares(failing, outer)
            val boom = IllegalStateException("boom")

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        throw boom
                    }
                }

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertTrue(thrown.suppressedExceptions.any { it === boom }, "the body's failure rides along as suppressed")
            assertEquals(
                listOf("failing:errorAsync", "failing:error", "outer:errorAsync", "outer:error"),
                log.filter { it.contains("error") },
                "the failing middleware's sync hook and the outer middleware's hooks still ran",
            )
            assertSame(boom, outer.asyncErrors.single())
            assertSame(boom, outer.errors.single())
            assertEquals(TransactionStatus.RolledBack, outer.transaction?.status)
            assertEquals(0, store.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            store.assertNotWedged()
        }

    @Test fun aSyncErrorHookStackOverflowStillLetsTheOuterMiddlewareSeeTheError() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val failing = FatalSyncHooks<FsaStore>("failing", log, onError = { throw fatal })
            val outer = FatalSyncHooks<FsaStore>("outer", log)
            store.middlewares(failing, outer)
            val boom = IllegalStateException("boom")

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        throw boom
                    }
                }

            assertSame(fatal, thrown)
            assertTrue(thrown.suppressedExceptions.any { it === boom })
            assertEquals(listOf("outer:started", "failing:started", "failing:error", "outer:error"), log.toList())
            assertSame(boom, outer.errors.single(), "registered after the failing one, it still received the error")
            assertEquals(TransactionStatus.RolledBack, outer.transaction?.status)
            assertEquals(0, store.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            store.assertNotWedged()
        }

    @Test fun aNonFatalErrorHookFailureIsStillIsolated() =
        runBlocking<Unit> {
            val store = FsaStore()
            val log = CopyOnWriteArrayList<String>()
            val failing = FatalSyncHooks<FsaStore>("failing", log, onError = { error("hook") })
            val outer = FatalSyncHooks<FsaStore>("outer", log)
            store.middlewares(failing, outer)
            val boom = IllegalStateException("boom")

            val result =
                store.suspendAction {
                    x mutate 1
                    throw boom
                }

            assertSame(boom, assertIs<TransactionResult.Error>(result).exception)
            assertSame(boom, outer.errors.single())
            store.assertNotWedged()
        }

    @Test fun aStartedHookStackOverflowSkipsTheBodyRollsBackAndLeavesNothingInstalled() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val inner = FatalSyncHooks<FsaStore>("inner", log)
            var armed = true
            val thrower = FatalSyncHooks<FsaStore>("thrower", log, onStarted = { if (armed) throw fatal })
            store.middlewares(inner, thrower)
            var ran = false

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        ran = true
                        x mutate 1
                    }
                }

            assertSame(fatal, thrown)
            assertFalse(ran, "the body never ran")
            assertFalse("inner:started" in log, "the remaining started hooks never ran: $log")
            assertSame(fatal, thrower.errors.single(), "the thrower saw its own error")
            assertEquals(TransactionStatus.RolledBack, thrower.transaction?.status)
            assertEquals(0, store.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            // #37's wedge shape: a stranded transaction or suspending owner would
            // make the next blocking action wait, then refuse.
            armed = false
            store.assertNotWedged()
        }

    @Test fun anAsyncStartedHookStackOverflowSkipsTheBodyAfterItsSyncSibling() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            val inner = FatalSyncHooks<FsaStore>("inner", log)
            var armed = true
            val dual = FatalDualHooks<FsaStore>("dual", log, onStartedAsync = { if (armed) throw fatal })
            store.middlewares(inner, dual)
            var ran = false

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        ran = true
                        x mutate 1
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(listOf("dual:started", "dual:startedAsync"), log.take(2), "the sync started hook ran first")
            assertFalse("inner:started" in log, "the remaining started hooks never ran: $log")
            assertFalse(ran, "the body never ran")
            assertSame(fatal, dual.errors.single())
            assertEquals(TransactionStatus.RolledBack, dual.transaction?.status)
            assertEquals(0, store.x.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            armed = false
            store.assertNotWedged()
        }

    @Test fun aCompletedHookStackOverflowNeverCommitsAndEveryMiddlewareSeesCompletedThenError() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            var armed = true
            val inner = FatalSyncHooks<FsaStore>("inner", log, onCompleted = { if (armed) throw fatal })
            val outer = FatalSyncHooks<FsaStore>("outer", log)
            store.middlewares(inner, outer)
            val seen = CopyOnWriteArrayList<Int>()
            store.x effect { seen += this }

            val thrown = assertPropagates<StackOverflowError> { store.suspendAction { x mutate 1 } }

            assertSame(fatal, thrown)
            assertEquals(
                listOf(
                    "outer:started",
                    "inner:started",
                    "inner:completed",
                    "outer:completed",
                    "inner:error",
                    "outer:error",
                ),
                log.toList(),
                "the outer completed hook still ran, then both saw the error",
            )
            assertSame(fatal, inner.errors.single())
            assertSame(fatal, outer.errors.single())
            assertEquals(0, store.x.value, "never committed")
            assertEquals(listOf(0), seen.toList(), "no observer fired: no commit")
            assertEquals(TransactionStatus.RolledBack, outer.transaction?.status)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            armed = false
            store.assertNotWedged()
        }

    @Test fun anAsyncCompletedHookStackOverflowNeverCommits() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val fatal = StackOverflowError()
            var armed = true
            val inner = FatalDualHooks<FsaStore>("inner", log, onCompletedAsync = { if (armed) throw fatal })
            val outer = FatalSyncHooks<FsaStore>("outer", log)
            store.middlewares(inner, outer)
            val seen = CopyOnWriteArrayList<Int>()
            store.x effect { seen += this }

            val thrown = assertPropagates<StackOverflowError> { store.suspendAction { x mutate 1 } }

            assertSame(fatal, thrown)
            assertEquals(
                listOf(
                    "outer:started",
                    "inner:started",
                    "inner:startedAsync",
                    "inner:completedAsync",
                    "inner:completed",
                    "outer:completed",
                    "inner:errorAsync",
                    "inner:error",
                    "outer:error",
                ),
                log.toList(),
            )
            assertSame(fatal, inner.asyncErrors.single())
            assertSame(fatal, outer.errors.single())
            assertEquals(0, store.x.value, "never committed")
            assertEquals(listOf(0), seen.toList())
            assertEquals(TransactionStatus.RolledBack, outer.transaction?.status)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            armed = false
            store.assertNotWedged()
        }

    @Test fun anObserverStackOverflowInTheCommitPropagatesAfterTheOtherObserversWhileTheCommitStands() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val log = CopyOnWriteArrayList<String>()
            val plain = FatalSyncHooks<FsaStore>("plain", log)
            store.middlewares(plain)
            val fatal = StackOverflowError()
            val second = CopyOnWriteArrayList<Int>()
            store.x effect { if (this == 1) throw fatal }
            store.x effect { second += this }

            val thrown = assertPropagates<StackOverflowError> { store.suspendAction { x mutate 1 } }

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertEquals(1, store.x.value, "the apply pass committed")
            assertEquals(listOf(0, 1), second.toList(), "the other observer still fired")
            assertEquals(
                listOf("plain:started", "plain:completed"),
                log.toList(),
                "a committed transaction gets no error",
            )
            assertTrue(reported.isEmpty(), "never reported: $reported")
            store.assertNotWedged()
        }

    @Test fun aNonFatalObserverFailureIsStillReportedAndTheActionSucceeds() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val boom = IllegalStateException("observer")
            store.x effect { if (this == 1) throw boom }

            val result = store.suspendAction { x mutate 1 }

            assertIs<TransactionResult.Success<*>>(result)
            assertEquals(1, store.x.value)
            assertEquals(listOf<Throwable>(boom), reported.toList())
            store.assertNotWedged()
        }

    @Test fun anOutOfMemoryErrorPropagatesLikeAStackOverflow() =
        runBlocking<Unit> {
            val store = FsaStore()
            val reported = store.recordedReports()
            val fatal = OutOfMemoryError("test")

            val thrown =
                assertPropagates<OutOfMemoryError> {
                    store.suspendAction {
                        x mutate 1
                        throw fatal
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(0, store.x.value)
            assertTrue(reported.isEmpty())
            store.assertNotWedged()
        }
}
