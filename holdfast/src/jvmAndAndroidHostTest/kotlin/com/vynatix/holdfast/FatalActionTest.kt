@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61: a fatal error (on the JVM a VirtualMachineError, here an explicit
// StackOverflowError) thrown by user code an action runs propagates out of the
// action — never folded into a TransactionResult.Error, never handed to the
// uncaughtObserverHandler — after the transaction has been made consistent:
// rolled back, every middleware's onTransactionError fired. Store.kt
// runTransaction, Middleware.execute, mutate/update/invoke, reset().

private class FatalActionStore : Store<FatalActionStore>() {
    val a by state { 0 }
    val b by state { 0 }
}

/** A store whose never-read [lazy] state's initializer throws [initializerFails] while it is set. */
private class FatalResetStore : Store<FatalResetStore>() {
    val initializerFails = AtomicReference<Throwable?>(null)
    val a by state { 0 }
    val lazy by state { initializerFails.get()?.let { throw it } ?: 7 }
}

/** Logs every hook as `label:hook`, keeps what it was handed, and runs a hook's extra step. */
private class FatalHooks<V : Store<V>>(
    private val label: String,
    private val log: MutableList<String>,
    private val onStarted: () -> Unit = {},
    private val onCompleted: () -> Unit = {},
    private val onError: () -> Unit = {},
) : Middleware<V>() {
    val errors = mutableListOf<Throwable>()
    var transaction: Transaction? = null

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
        onError()
    }
}

/** Throws from [onStoreReset] what [fails] holds. */
private class FailingResetAttachment(
    private val fails: Throwable,
) : StoreAttachment {
    override fun onStoreReset(): Unit = throw fails
}

private val failingResetKey = StoreAttachmentKey<FailingResetAttachment>("fatal-reset")

/** What reaches [this] store's uncaughtObserverHandler from now on. */
private fun Store<*>.recordedReports(): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

/** Nothing an entry installs on its thread is left behind: no settle scope, no-write frame or frame marker. */
private fun assertNothingLeftOnThisThread() {
    assertNull(SettleScopes.current(), "no settle scope is left open on this thread")
    assertNull(NoWriteRegion.current(), "no no-write frame is left on this thread")
    assertNull(FrameMarkers.current(), "no frame marker is left on this thread")
}

/** Run [block] on another thread, failing rather than hanging when it does not finish within 10 s. */
private fun <R> onAnotherThread(block: () -> R): R {
    val result = AtomicReference<Result<R>?>(null)
    val thread = daemon("fatal-action-other") { result.set(runCatching(block)) }
    thread.join(10_000)
    assertFalse(thread.isAlive, "the other thread finished: nothing was left holding the store")
    return checkNotNull(result.get()).getOrThrow()
}

class FatalActionTest {
    @Test fun aBodyStackOverflowRollsBackAfterEveryErrorHookThenPropagates() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        val inner = FatalHooks<FatalActionStore>("inner", log)
        val outer = FatalHooks<FatalActionStore>("outer", log)
        store.middlewares(inner, outer)
        val fatal = StackOverflowError()

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    a mutate 5
                    throw fatal
                }
            }

        assertSame(fatal, thrown, "the body's own StackOverflowError propagates, not a wrapper")
        assertEquals(listOf("outer:started", "inner:started", "inner:error", "outer:error"), log)
        assertSame(fatal, inner.errors.single())
        assertSame(fatal, outer.errors.single())
        assertEquals(TransactionStatus.RolledBack, outer.transaction?.status, "the transaction rolled back")
        assertEquals(0, store.a.value, "the staged write was dropped")
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList(), "a fatal error is never reported")
        assertNothingLeftOnThisThread()
        assertIs<TransactionResult.Success<*>>(store action { a mutate 1 })
        assertEquals(1, store.a.value)
    }

    /** Every VirtualMachineError is fatal, not only a stack overflow. */
    @Test fun anOutOfMemoryErrorPropagatesLikeAStackOverflow() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val fatal = OutOfMemoryError()

        val thrown =
            assertFailsWith<OutOfMemoryError> {
                store action {
                    a mutate 5
                    throw fatal
                }
            }

        assertSame(fatal, thrown)
        assertEquals(0, store.a.value)
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
    }

    /** Non-fatal control: an Error that is not a VirtualMachineError still folds into an Error result. */
    @Test fun anAssertionErrorFromABodyIsStillAnErrorResult() {
        val store = FatalActionStore()
        val failure = AssertionError("not fatal")

        val result =
            store action {
                a mutate 5
                throw failure
            }

        assertSame(failure, assertIs<TransactionResult.Error>(result).exception)
        assertEquals(0, store.a.value)
        assertNull(store.activeTransaction)
    }

    @Test fun aNestedActionsStackOverflowRollsBackEveryLevel() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        val hooks = FatalHooks<FatalActionStore>("hooks", log)
        store.middlewares(hooks)
        val fatal = StackOverflowError()

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    a mutate 5
                    action {
                        b mutate 1
                        throw fatal
                    }
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf("hooks:started", "hooks:started", "hooks:error", "hooks:error"), log)
        assertTrue(hooks.errors.all { it === fatal }, "both levels' error hooks got the fatal error")
        assertEquals(0, store.a.value, "the outer action rolled back")
        assertEquals(0, store.b.value, "the savepoint rolled back")
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        assertNothingLeftOnThisThread()
        assertIs<TransactionResult.Success<*>>(store action { b mutate 2 })
    }

    @Test fun aStartedHookStackOverflowSkipsTheBodyAndStillGetsItsErrorHook() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        val fatal = StackOverflowError()
        val guard = FatalHooks<FatalActionStore>("guard", log, onStarted = { throw fatal })
        val audit = FatalHooks<FatalActionStore>("audit", log)
        store.middlewares(guard, audit)
        var bodyRan = false

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    bodyRan = true
                    a mutate 1
                }
            }

        assertSame(fatal, thrown)
        assertFalse(bodyRan)
        assertEquals(listOf("audit:started", "guard:started", "guard:error", "audit:error"), log)
        assertSame(fatal, audit.errors.single())
        assertEquals(0, store.a.value)
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
    }

    /** Never commit after a fatal error: one from onTransactionCompleted fails the transaction (policy). */
    @Test fun aCompletedHookStackOverflowRollsBackInsteadOfCommitting() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        val fatal = StackOverflowError()
        val validator = FatalHooks<FatalActionStore>("validator", log, onCompleted = { throw fatal })
        store.middlewares(validator)
        val observed = mutableListOf<Int>()
        val effect = store.a effect { observed += this }

        val thrown = assertFailsWith<StackOverflowError> { store action { a mutate 5 } }

        assertSame(fatal, thrown)
        assertEquals(listOf("validator:started", "validator:completed", "validator:error"), log)
        assertSame(fatal, validator.errors.single())
        assertEquals(TransactionStatus.RolledBack, validator.transaction?.status)
        assertEquals(0, store.a.value, "nothing committed")
        assertEquals(listOf(0), observed, "no observer heard of a commit")
        assertEquals(emptyList(), reported.toList())
        assertNull(store.activeTransaction)
        effect.dispose()
    }

    /** Middleware.execute (decision 2): a fatal error outranks what an error hook throws, which rides along. */
    @Test fun aFatalErrorOutranksWhatAnErrorHookThrows() {
        val store = FatalActionStore()
        val log = mutableListOf<String>()
        val hookFailure = IllegalStateException("audit failed")
        val inner = FatalHooks<FatalActionStore>("inner", log)
        val outer = FatalHooks<FatalActionStore>("outer", log, onError = { throw hookFailure })
        store.middlewares(inner, outer)
        val fatal = StackOverflowError()

        val thrown = assertFailsWith<StackOverflowError> { store action { throw fatal } }

        assertSame(fatal, thrown, "the fatal error is the primary throwable")
        assertTrue(thrown.suppressed.any { it === hookFailure }, "the hook's failure rides along as suppressed")
        assertSame(fatal, inner.errors.single())
        assertSame(fatal, outer.errors.single())
        assertNull(store.activeTransaction)
        assertIs<TransactionResult.Success<*>>(store action { a mutate 1 })
    }

    /** Middleware.execute (decision 2): a fatal error an error hook throws replaces the body's ordinary failure. */
    @Test fun aStackOverflowFromAnErrorHookReplacesTheBodysFailure() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        val fatal = StackOverflowError()
        val bodyFailure = IllegalStateException("body failed")
        val inner = FatalHooks<FatalActionStore>("inner", log, onError = { throw fatal })
        val outer = FatalHooks<FatalActionStore>("outer", log)
        store.middlewares(inner, outer)

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    a mutate 1
                    throw bodyFailure
                }
            }

        assertSame(fatal, thrown)
        assertSame(bodyFailure, inner.errors.single())
        assertSame(fatal, outer.errors.single(), "the outer hook was handed the fatal error")
        assertEquals(0, store.a.value)
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
    }

    /** Non-fatal control: a throwing error hook still replaces an ordinary failure, folded into Error. */
    @Test fun anOrdinaryHookFailureStillReplacesAnOrdinaryBodyFailureAsAnError() {
        val store = FatalActionStore()
        val log = mutableListOf<String>()
        val hookFailure = IllegalArgumentException("hook failed")
        store.middlewares(FatalHooks("inner", log, onError = { throw hookFailure }), FatalHooks("outer", log))

        val result = store action { error("body failed") }

        assertSame(hookFailure, assertIs<TransactionResult.Error>(result).exception)
        assertNull(store.activeTransaction)
    }

    /** Store.kt:1604/1230: a bare mutate's one-shot action used to drop the fatal error with its result. */
    @Test fun aBareMutatePropagatesAnObserversStackOverflowAndTheValueStands() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val fatal = StackOverflowError()
        var armed = false
        val effect = store.a effect { if (armed) throw fatal }
        armed = true

        val thrown = assertFailsWith<StackOverflowError> { store { a mutate 9 } }

        assertSame(fatal, thrown)
        assertEquals(9, store.a.value, "the commit's value stands")
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        assertNothingLeftOnThisThread()
        armed = false
        store { a mutate 10 }
        assertEquals(10, store.a.value)
        effect.dispose()
    }

    @Test fun updateRethrowsAStackOverflowFromItsBlockOrItsCommit() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val fatal = StackOverflowError()

        assertSame(fatal, assertFailsWith<StackOverflowError> { store { a update { throw fatal } } })
        assertEquals(0, store.a.value, "the block's update was rolled back")

        var armed = true
        val effect = store.a effect { if (armed && this == 4) throw fatal }
        val fromCommit = assertFailsWith<StackOverflowError> { store { a update { 4 } } }
        assertSame(fatal, fromCommit, "a commit-phase fatal error arrives raw, never as a TransactionException")
        assertEquals(4, store.a.value)
        assertEquals(emptyList(), reported.toList())
        armed = false
        store { a update { it + 1 } }
        assertEquals(5, store.a.value)
        effect.dispose()
    }

    @Test fun invokePropagatesAStackOverflowRaw() {
        val store = FatalActionStore()
        val fatal = StackOverflowError()
        assertSame(fatal, assertFailsWith<StackOverflowError> { store { throw fatal } })
    }

    /** Store.kt:812: the locks and the serializer are released on the way out, so other threads get the store. */
    @Test fun anotherThreadTakesTheStoreAfterABodyStackOverflow() {
        val store = FatalActionStore()
        val fatal = StackOverflowError()
        assertFailsWith<StackOverflowError> {
            store action {
                a mutate 1
                throw fatal
            }
        }

        val result = onAnotherThread { store action { a mutate 2 } }

        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(2, store.a.value)
    }

    /**
     * Store.kt:812: the drain a fatal error skips is the store's next holder's: what the
     * action queued is not lost.
     */
    @Test fun aPostCommitTaskQueuedBeforeABodyStackOverflowRunsBeforeTheErrorLeaves() {
        val store = FatalActionStore()
        val fatal = StackOverflowError()
        var ran = 0
        assertFailsWith<StackOverflowError> {
            store action {
                postCommit { ran++ }
                throw fatal
            }
        }
        assertEquals(1, ran, "the holder drained its queue after releasing, before the error left (issue #61)")

        assertIs<TransactionResult.Success<*>>(store action { a mutate 1 })

        assertEquals(1, ran, "the queued task ran exactly once")
    }

    /** Reset.kt:116 and NoWriteRegion.kt:130: an initializer's fatal error leaves before the reset opens. */
    @Test fun resetPropagatesAnInitializersStackOverflowBeforeItsTransactionOpens() {
        val store = FatalResetStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        store.middlewares(FatalHooks("hooks", log))
        store action { a mutate 3 }
        log.clear()
        val fatal = StackOverflowError()
        store.initializerFails.set(fatal)

        val thrown = assertFailsWith<StackOverflowError> { store.reset() }

        assertSame(fatal, thrown)
        assertEquals(emptyList(), log, "no reset transaction started")
        assertEquals(3, store.a.value, "nothing was reset")
        assertEquals(emptyList(), reported.toList())
        assertNothingLeftOnThisThread()
        assertIs<TransactionResult.Success<*>>(store action { a mutate 4 }, "this thread can still write")
        store.initializerFails.set(null)
        assertEquals(7, store.lazy.value, "the initializer stays retryable")
        assertIs<TransactionResult.Success<*>>(store.reset())
        assertEquals(0, store.a.value)
    }

    /** Reset.kt: a re-run initializer's fatal error, inside the reset's transaction, rolls the whole reset back. */
    @Test fun aReRunInitializersStackOverflowRollsTheResetBack() {
        val store = FatalResetStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        store.middlewares(FatalHooks("hooks", log))
        assertEquals(7, store.lazy.value)
        store action { a mutate 3 }
        log.clear()
        val fatal = StackOverflowError()
        store.initializerFails.set(fatal)

        val thrown = assertFailsWith<StackOverflowError> { store.reset() }

        assertSame(fatal, thrown)
        assertEquals(listOf("hooks:started", "hooks:error"), log)
        assertEquals(3, store.a.value, "the reset rolled back")
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        assertNothingLeftOnThisThread()
        store.initializerFails.set(null)
        assertIs<TransactionResult.Success<*>>(store.reset())
        assertEquals(0, store.a.value)
    }

    @Test fun aResetHooksStackOverflowRollsTheResetBack() {
        val store = FatalActionStore()
        val reported = store.recordedReports()
        val log = mutableListOf<String>()
        val hooks = FatalHooks<FatalActionStore>("hooks", log)
        store.middlewares(hooks)
        store action { a mutate 3 }
        val fatal = StackOverflowError()
        store.internalAttachIfAbsent(failingResetKey) { FailingResetAttachment(fatal) }
        log.clear()

        val thrown = assertFailsWith<StackOverflowError> { store.reset() }

        assertSame(fatal, thrown)
        assertEquals(listOf("hooks:started", "hooks:error"), log)
        assertEquals(TransactionStatus.RolledBack, hooks.transaction?.status)
        assertEquals(3, store.a.value, "the reset rolled back")
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
    }
}
