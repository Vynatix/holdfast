package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FanoutStore : Store<FanoutStore>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

private class FanoutTarget : Store<FanoutTarget>() {
    val copy by state { 0 }
}

private sealed class FanoutEvent {
    data object Reacted : FanoutEvent()
}

private class FanoutEventStore : EventfulStore<FanoutEventStore, FanoutEvent>() {
    val trigger by state { 0 }
}

private class FanoutSupportStore private constructor(
    private val support: EventfulSupport<FanoutEvent>,
) : Store<FanoutSupportStore>(),
    Eventful<FanoutEvent> by support {
    constructor() : this(EventfulSupport())

    val trigger by state { 0 }

    init {
        @Suppress("LeakingThis")
        support.bindStore(this)
    }
}

/**
 * Writes into a transaction that has already applied (issue #20, D16).
 *
 * A top-level commit applies its writes, then fans out to observers while the
 * transaction is still the store's active one. An observer on the committing
 * thread that wrote back into that store staged into the finished transaction:
 * `mutate`/`update`/`emit` landed in buffers nobody applies again, and a nested
 * `action` or `atomic` opened a savepoint that merged into them — each write
 * silently lost. Every case here asserts the write is surfaced instead, and
 * that the commit that triggered it stands.
 */
class FanoutWriteTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    /** Subscribe [react] to [state]'s commits only (the initial fire is skipped). */
    private fun <T : Any> onCommit(
        state: State<T>,
        react: (T) -> Unit,
    ) {
        var initial = true
        disposables +=
            state.effect {
                if (initial) initial = false else react(this)
            }
    }

    private fun Store<*>.collectFailures(): MutableList<Throwable> {
        val failures = mutableListOf<Throwable>()
        uncaughtObserverHandler = { failures += it }
        return failures
    }

    private fun assertAppliedTransactionError(
        error: Throwable,
        vararg fragments: String,
    ) {
        assertIs<IllegalStateException>(error)
        val message = assertNotNull(error.message)
        assertTrue("has already applied its writes" in message, "not the applied-transaction error: $message")
        fragments.forEach { assertTrue(it in message, "message lacks '$it': $message") }
    }

    @Test fun observerMutateIntoItsOwnCommittingStoreIsSurfaced() {
        val s = FanoutStore()
        val failures = s.collectFailures()
        onCommit(s.trigger) { value -> s { echo mutate value * 10 } }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r, "the commit that triggered the observer stands")
        assertEquals(1, s.trigger.value)
        assertEquals(1, failures.size, "the refused write must reach the handler, not vanish")
        assertAppliedTransactionError(failures.single(), "Cannot write FanoutStore.echo", "computed { }", "store.scope")
        assertEquals(0, s.echo.value, "the refused write never lands later either")
        s action { trigger mutate 2 }
        assertEquals(0, s.echo.value)
        assertEquals(2, failures.size)
    }

    @Test fun observerUpdateIntoItsOwnCommittingStoreIsSurfaced() {
        val s = FanoutStore()
        val failures = s.collectFailures()
        onCommit(s.trigger) { s { echo update { it + 1 } } }

        s action { trigger mutate 1 }

        assertEquals(1, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "FanoutStore.echo")
        assertEquals(0, s.echo.value)
    }

    @Test fun observerNestedActionIntoItsOwnCommittingStoreReturnsError() {
        val s = FanoutStore()
        val failures = s.collectFailures()
        var nested: TransactionResult<*>? = null
        var bodyRan = false
        onCommit(s.trigger) { value ->
            nested =
                s action {
                    bodyRan = true
                    echo mutate value
                }
        }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r)
        val error = assertIs<TransactionResult.Error>(nested, "the nested action must fail, not merge into the root")
        assertAppliedTransactionError(error.exception, "open a nested action on FanoutStore")
        assertEquals(TransactionStatus.RolledBack, error.transaction.status)
        assertTrue(!bodyRan, "a refused action never runs its body")
        assertEquals(0, s.echo.value)
        assertEquals(emptyList(), failures, "a returned Error is the caller's to handle; nothing escapes the observer")
    }

    @Test fun observerNestedActionRunsNoMiddleware() {
        val s = FanoutStore()
        val started = mutableListOf<String>()
        s.middlewares(
            object : Middleware<FanoutStore>() {
                override fun onTransactionStarted(context: MiddlewareContext<FanoutStore>) {
                    started += context.transaction.id
                }
            },
        )
        var nested: TransactionResult<*>? = null
        onCommit(s.trigger) { nested = s action { echo mutate 5 } }

        s action { trigger mutate 1 }

        assertIs<TransactionResult.Error>(nested)
        assertEquals(1, started.size, "only the triggering action started; the refused one never did")
    }

    @Test fun observerEmitOnItsOwnCommittingEventfulStoreIsSurfaced() {
        val s = FanoutEventStore()
        val failures = s.collectFailures()
        onCommit(s.trigger) { s.emit(FanoutEvent.Reacted) }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "emit an event on FanoutEventStore")
    }

    @Test fun observerEmitOnItsOwnCommittingEventfulSupportStoreIsSurfaced() {
        val s = FanoutSupportStore()
        val failures = s.collectFailures()
        onCommit(s.trigger) { s.emit(FanoutEvent.Reacted) }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "emit an event on FanoutSupportStore")
    }

    @Test fun observerAtomicOverItsOwnCommittingStoreReturnsError() {
        val s = FanoutStore()
        val t = FanoutTarget()
        val failures = s.collectFailures()
        var frame: TransactionResult<*>? = null
        var bodyRan = false
        onCommit(s.trigger) { value ->
            frame =
                atomic(s, t) {
                    bodyRan = true
                    s { echo mutate value }
                    t { copy mutate value }
                }
        }

        s action { trigger mutate 3 }

        val error = assertIs<TransactionResult.Error>(frame, "a frame nesting into the applied root fails as a whole")
        assertAppliedTransactionError(error.exception, "open an atomic(...) frame on FanoutStore")
        assertTrue(!bodyRan, "a refused frame never runs its body")
        assertEquals(0, s.echo.value)
        assertEquals(0, t.copy.value, "no participant commits on its own")
        assertEquals(emptyList(), failures)
    }

    @Test fun crossStoreObserverWritesStillCommit() {
        val s = FanoutStore()
        val t = FanoutTarget()
        val failures = s.collectFailures()
        onCommit(s.trigger) { value -> t { copy mutate value } }
        onCommit(s.echo) { value -> t action { copy update { it + value } } }

        s action { trigger mutate 4 }
        assertEquals(4, t.copy.value, "a mutate on another, idle store commits as its own action")
        s action { echo mutate 3 }
        assertEquals(7, t.copy.value, "an action on another, idle store commits")
        assertEquals(emptyList(), failures)
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun frameObserverWritesIntoAnAppliedParticipantAreSurfaced() {
        // Every participant applies before any fans out (issue #20, R9): an
        // observer of the later participant `t` that writes the earlier
        // participant `s` meets s's already-applied frame root.
        val s = FanoutStore()
        val t = FanoutTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = t.collectFailures()
        onCommit(t.copy) { value -> s { echo mutate value } }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1, s.trigger.value)
        assertEquals(2, t.copy.value)
        assertAppliedTransactionError(failures.single(), "Cannot write FanoutStore.echo")
        assertEquals(0, s.echo.value)
    }

    /**
     * Silently lost before: the nested action opened a savepoint of s's
     * committed frame root, merged into it and returned Success.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun frameObserverNestedActionIntoAnAppliedParticipantReturnsError() {
        val s = FanoutStore()
        val t = FanoutTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = t.collectFailures()
        var nested: TransactionResult<*>? = null
        var bodyRan = false
        onCommit(t.copy) { value ->
            nested =
                s action {
                    bodyRan = true
                    echo mutate value
                }
        }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        val error = assertIs<TransactionResult.Error>(nested, "must not merge into s's committed frame root")
        assertAppliedTransactionError(error.exception, "open a nested action on FanoutStore")
        assertEquals(TransactionStatus.RolledBack, error.transaction.status)
        assertTrue(!bodyRan, "a refused action never runs its body")
        assertEquals(0, s.echo.value)
        assertEquals(emptyList(), failures)
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun frameObserverAtomicOverAnAppliedParticipantReturnsError() {
        val s = FanoutStore()
        val t = FanoutTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = t.collectFailures()
        var frame: TransactionResult<*>? = null
        onCommit(t.copy) { value -> frame = atomic(s) { s { echo mutate value } } }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        val error = assertIs<TransactionResult.Error>(frame)
        assertAppliedTransactionError(error.exception, "open an atomic(...) frame on FanoutStore")
        assertEquals(0, s.echo.value)
        assertEquals(emptyList(), failures)
    }

    /** Silently lost before: the event was staged into s's committed frame root. */
    @OptIn(StoreInternalApi::class)
    @Test
    fun frameObserverEmitOnAnAppliedParticipantIsSurfaced() {
        val s = FanoutEventStore()
        val t = FanoutTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = t.collectFailures()
        onCommit(t.copy) { s.emit(FanoutEvent.Reacted) }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "emit an event on FanoutEventStore")
    }

    /**
     * The other direction too (issue #20, R9): a frame applies every
     * participant before any fans out, so when `s` fans out, `t`'s frame root
     * has already applied, and an observer's write into it is refused. (It
     * used to stage into t's still-open root and commit with it.)
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun frameObserverWritesIntoALaterParticipantAreSurfaced() {
        val s = FanoutStore()
        val t = FanoutTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = s.collectFailures()
        onCommit(s.trigger) { value -> t { copy mutate value * 100 } }

        val r = atomic(s, t) { s { trigger mutate 2 } }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(2, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "Cannot write FanoutTarget.copy", "participant of the frame")
        assertEquals(0, t.copy.value)
    }

    @Test fun theStoreAcceptsWritesAgainOnceTheCommitIsDone() {
        // The refusal is about nesting into a finished transaction, not about
        // the store: the next action — from the observer's store itself —
        // commits normally.
        val s = FanoutStore()
        s.collectFailures()
        onCommit(s.trigger) { value -> s { echo mutate value } }
        s action { trigger mutate 1 }

        val r = s action { echo mutate 9 }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(9, s.echo.value)
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun savepointFactoryRefusesAnAppliedParent() {
        val root = Transaction.createForExternal("root", ownerThreadId = 0L)
        root.commit()

        val e = assertFailsWith<IllegalStateException> { Transaction.createSavepointForExternal("sp", 0L, root) }
        assertAppliedTransactionError(e, "open a savepoint")
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun stagingAnEventIntoAnAppliedTransactionThrows() {
        val root = Transaction.createForExternal("root", ownerThreadId = 0L)
        root.commit()

        val e =
            assertFailsWith<IllegalStateException> {
                root.stagePendingEvent(kotlinx.coroutines.flow.MutableSharedFlow<Any>(), FanoutEvent.Reacted)
            }
        assertAppliedTransactionError(e, "emit an event")
    }

    /**
     * The backstop inside `action`: a transaction that has applied but is
     * neither owned by this thread nor fanning out on it (here, one installed
     * by hand) still cannot be nested into.
     *
     * The counting serializer proves the refusal came from that backstop,
     * after the serializer was taken, and not from `action`'s front check —
     * which a `fanoutThreadId` left naming this thread after the commit would
     * trigger, refusing (as nested) a call that should wait its turn.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun actionRefusesToNestIntoAnAppliedTransactionItDoesNotOwn() {
        val s = FanoutStore()
        val foreign = Transaction.createForExternal("foreign", ownerThreadId = Long.MIN_VALUE + 1)
        foreign.commit()
        assertNotEquals(
            currentThreadId(),
            foreign.fanoutThreadId,
            "a finished commit must stop naming its fanout thread",
        )
        var acquired = 0
        var released = 0
        s.asyncSerializer =
            object : Store.AsyncSerializer {
                override fun blockingAcquire() {
                    acquired++
                }

                override fun blockingRelease() {
                    released++
                }
            }
        s.internalSetActiveTransaction(foreign)
        try {
            val r = s action { echo mutate 1 }

            val error = assertIs<TransactionResult.Error>(r)
            assertAppliedTransactionError(error.exception, "open a nested action on FanoutStore")
            assertEquals(1, acquired, "not nested: the action takes the serializer before the backstop refuses it")
            assertEquals(1, released)
        } finally {
            s.internalSetActiveTransaction(null)
            s.asyncSerializer = null
        }
        assertEquals(0, s.echo.value)
    }

    /**
     * An `atomic` participant that already has a transaction on this thread
     * (an enclosing action) gets a SAVEPOINT entry. It commits into that
     * action — its root has not applied — and stays installed while later
     * participants fan out, so anything staged into it then would be lost.
     */
    @OptIn(StoreInternalApi::class)
    private fun <S : Store<S>> commitSavepointFrame(
        s: S,
        t: FanoutTarget,
        writeS: S.() -> Unit,
    ): TransactionResult<*> {
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        return s action {
            atomic(s, t) {
                s.invoke { writeS() }
                t { copy mutate 2 }
            }
        }
    }

    /** Silently lost before: the savepoint of the committed entry merged into a dead buffer. */
    @Test fun frameObserverNestedActionIntoACommittedSavepointParticipantReturnsError() {
        val s = FanoutStore()
        val t = FanoutTarget()
        val failures = t.collectFailures()
        var nested: TransactionResult<*>? = null
        var bodyRan = false
        onCommit(t.copy) { value ->
            nested =
                s action {
                    bodyRan = true
                    echo mutate value
                }
        }

        val outer = commitSavepointFrame(s, t) { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(outer)
        val error = assertIs<TransactionResult.Error>(nested, "must not merge into s's committed savepoint entry")
        assertAppliedTransactionError(error.exception, "open a nested action on FanoutStore", "status: Committed")
        assertFalse(bodyRan, "a refused action never runs its body")
        assertEquals(1, s.trigger.value)
        assertEquals(2, t.copy.value)
        assertEquals(0, s.echo.value)
        assertEquals(emptyList(), failures)
    }

    @Test fun frameObserverAtomicOverACommittedSavepointParticipantReturnsError() {
        val s = FanoutStore()
        val t = FanoutTarget()
        val failures = t.collectFailures()
        var frame: TransactionResult<*>? = null
        onCommit(t.copy) { value -> frame = atomic(s) { s { echo mutate value } } }

        val outer = commitSavepointFrame(s, t) { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(outer)
        val error = assertIs<TransactionResult.Error>(frame)
        assertAppliedTransactionError(error.exception, "open an atomic(...) frame on FanoutStore")
        assertEquals(1, s.trigger.value)
        assertEquals(0, s.echo.value)
        assertEquals(emptyList(), failures)
    }

    @Test fun frameObserverEmitOnACommittedSavepointParticipantIsSurfaced() {
        val s = FanoutEventStore()
        val t = FanoutTarget()
        val failures = t.collectFailures()
        onCommit(t.copy) { s.emit(FanoutEvent.Reacted) }

        val outer = commitSavepointFrame(s, t) { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(outer)
        assertEquals(1, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "emit an event on FanoutEventStore")
    }

    @Test fun frameObserverMutateIntoACommittedSavepointParticipantIsSurfaced() {
        val s = FanoutStore()
        val t = FanoutTarget()
        val failures = t.collectFailures()
        onCommit(t.copy) { value -> s { echo mutate value } }

        val outer = commitSavepointFrame(s, t) { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(outer)
        assertEquals(1, s.trigger.value)
        assertAppliedTransactionError(failures.single(), "Cannot write FanoutStore.echo", "computed { }")
        assertEquals(0, s.echo.value)
    }

    /**
     * A frame unwinds in reverse lock order, each participant's error hooks
     * first, then its rollback: when `s`'s `onTransactionError` runs, `t`'s
     * root is already rolled back but still installed. An action on `t` from
     * there used to open a savepoint of it, merge into it and return Success.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun frameErrorHookNestedActionIntoARolledBackParticipantReturnsError() {
        val s = FanoutStore()
        val t = FanoutTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        var nested: TransactionResult<*>? = null
        s.middlewares(
            object : Middleware<FanoutStore>() {
                override fun onTransactionError(
                    context: MiddlewareContext<FanoutStore>,
                    error: Throwable,
                ) {
                    nested = t action { copy mutate 9 }
                }
            },
        )

        val r =
            atomic(s, t) {
                t { copy mutate 1 }
                error("frame fails")
            }

        assertIs<TransactionResult.Error>(r)
        val error = assertIs<TransactionResult.Error>(nested, "must not merge into t's rolled-back root")
        val message = assertNotNull(error.exception.message)
        assertTrue("has already been rolled back (status: RolledBack)" in message, "unexpected: $message")
        assertTrue("open a nested action on FanoutTarget" in message, "unexpected: $message")
        assertEquals(0, t.copy.value)
    }
}
