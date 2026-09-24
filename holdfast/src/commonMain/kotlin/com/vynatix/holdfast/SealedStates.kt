@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId

// Hooks for library machinery that keeps transactional state of its own on a
// store and drives transactions of its own on it (issue #20, R8; plan PR 13):
// `:holdfast-coroutines`' hydrator, which keeps its phase this way.
//
// A SEALED state commits, rolls back and fires its observers like any other
// state, but it is its owner's alone: every store write entrypoint — `mutate`,
// `update`, `bridge`, `observeFrom` — refuses it with the owner's teaching
// message, an inbound bridge value is dropped, and only the owner stages it
// (internalStageSealed), inside a transaction of the store. It is never
// registered, like a DerivedState's backing, so `snapshot()`, `restore`,
// `reset()`, `properties`, `taggedStates`, `removeState` and `clearStates`
// never see it: nothing can capture, restore, reset or drop it.
//
// The owner drives the store through internalTopLevelAction — a top-level
// transaction, middleware chain included, for a caller that already holds the
// store (the hydration gate holds its serializer) — and forbids
// `removeState`/`clearStates` inside a transaction that runs user code whose
// rollback must be whole (internalForbidStructuralWrites: a hydrator's adopt).
// Both drop states at once, outside any transaction, so no rollback could
// bring a dropped state back.

/**
 * A new sealed state of this store, named [name] and holding [initial]: a
 * `distinct` [MutableState] that commits, rolls back and fires its observers
 * like a declared state, and that library machinery keeps for itself.
 *
 *  - **Sealed.** `mutate`, `update`, `bridge` and `observeFrom` on it throw
 *    [IllegalStateException] — "Cannot write `Store.name`: [refusal]" — so
 *    [refusal] says whose state it is and how to change it instead; a value
 *    arriving through a bridge is dropped. Its owner stages it with
 *    [internalStageSealed].
 *  - **Hidden.** It is never registered: [snapshot] never captures it (and
 *    `StoreSnapshot.entry` refuses it), `restore` and [reset] never write it,
 *    [Store.properties], [taggedStates], `removeState` and `clearStates` never
 *    see it, and it carries no tags. It stays readable, and observable
 *    ([effect], a [derivedState] source, the coroutines flows), after the
 *    store is disposed, but nothing changes it any more.
 *
 * [name] only names it — in messages, a derived state's name, a
 * middleware's `modifiedStates` — and may repeat another state's name.
 *
 * @throws IllegalStateException if the store is disposed.
 */
@StoreInternalApi
fun <T : Any> Store<*>.internalSealedState(
    name: String,
    initial: T,
    refusal: String,
): MutableState<T> {
    checkNotDisposed()
    val state = MutableState(initial, transformer = null, owningStore = this, distinct = true)
    state.declaration =
        StateDeclaration(
            store = this,
            name = name,
            kind = StateKind.Sealed,
            initializer = { initial },
            transformer = null,
            distinct = true,
            codec = null,
            property = null,
            local = false,
        )
    state.writeSeal = WriteSeal(refusal)
    return state
}

/**
 * Stage [value] as the pending write of [state], a sealed state of this store
 * ([internalSealedState]), into this store's transaction open on this thread
 * — as `mutate` stages, without its refusal of sealed states: it commits, or
 * rolls back, with that transaction. The owner calls it inside its own
 * transactions ([internalTopLevelAction]), inside a reset's
 * ([StoreAttachment.onStoreReset]), or inside a caller's action.
 *
 * @throws IllegalStateException if the store is disposed; inside a state
 *   initializer, a schema migration or a derived state's compute; when no
 *   transaction of this store is open on this thread; or when that
 *   transaction is closed to writes — its root has applied (an observer
 *   staging into the commit notifying it) or it has ended.
 * @throws UnenrolledStoreException inside an `atomic` frame that does not
 *   enroll this store (unless its policy allows unenrolled writes).
 * @throws IllegalArgumentException when [state] is not a sealed state of this
 *   store.
 */
@StoreInternalApi
fun <T : Any> Store<*>.internalStageSealed(
    state: MutableState<T>,
    value: T,
) {
    checkNotDisposed()
    require(state.owningStore === this && state.writeSeal != null) {
        "internalStageSealed stages a sealed state of $displayName (internalSealedState), not $state"
    }
    NoWriteRegion.refuse { "write ${describeState(state)}" }
    val txn =
        activeTransaction?.takeIf { stagesInto(it) }
            ?: throw IllegalStateException(
                "Cannot write ${describeState(state)}: it is staged into a transaction of $displayName open on " +
                    "this thread, and none is. Stage it inside an action of $displayName.",
            )
    // The rule mutate's staging applies: an unenrolled store's transaction
    // here is an enclosing action's, which commits whatever the frame does.
    val frame = FrameMarkers.current()
    if (frame != null && !frame.isEnrolled(this) && !frame.policy.allowUnenrolled) {
        throw UnenrolledStoreException(unenrolledMessage(frame, "a write of ${describeState(state)}"))
    }
    check(txn.stagePendingWrite(state, value)) { appliedWriteMessage(txn, state) }
}

/**
 * Run [body] as a top-level transaction of this store, for a caller that
 * already holds the store — `:holdfast-coroutines`' hydration gate, which
 * holds its serializer — exactly as [Store.action] runs one, but without
 * taking the serializer: the transaction opens under `transactionLock`, runs
 * [body] through the middleware chain (which sees it under [id]), commits
 * and fans out, or rolls back, and a failure folds into the returned
 * [TransactionResult.Error]. Like `action` it is an entry: it joins the
 * settle scope open on this thread, else opens one that settles when it
 * returns ([SettleScope]); a caller that holds the store across several of
 * these opens one around its hold, so derived states settle once it has
 * released the store.
 *
 * It does NOT drain the post-commit queue: its caller, a top-level holder of
 * the store, drains it once it has released the serializer, on every exit
 * (see [Store.internalDrainPostCommitTasks]).
 *
 * @throws IllegalStateException if the store is disposed; inside a state
 *   initializer, a schema migration or a derived state's compute; or when a
 *   transaction of this store is active — the caller does not hold the
 *   store, or calls this from inside one of its transactions.
 */
@StoreInternalApi
fun <V : Store<V>, R> V.internalTopLevelAction(
    id: String,
    body: V.() -> R,
): TransactionResult<R> {
    checkNotDisposed()
    NoWriteRegion.refuse { "open an action on $displayName" }
    return settling {
        runUnderLock {
            val active = activeTransaction
            check(active == null) {
                "internalTopLevelAction('$id') opens a top-level transaction of $displayName, but transaction " +
                    "'${active?.id}' is active: its caller must hold the store, outside any of its transactions"
            }
            runTransaction(id, body)
        }
    }
}

/**
 * Forbid `removeState` and `clearStates` on this transaction's store, on this
 * transaction's owner thread, while this transaction — or a savepoint of it
 * — is the store's active transaction: they throw [IllegalStateException]
 * with [reason]. For library machinery that runs user code inside this
 * transaction and promises its rollback is whole — a hydrator's `adopt`:
 * both drop states at once, outside any transaction, so no rollback could
 * bring a dropped state back. Every write that stages into the transaction
 * is still allowed; the machinery polices those itself.
 *
 * [reason] completes "Cannot remove state 'x' from Store: …".
 */
@StoreInternalApi
fun Transaction.internalForbidStructuralWrites(reason: String) {
    structuralRefusal = reason
}

/**
 * `Store.name` of this state, for a message — `Store.docs[*]` for a keyed
 * entry, never its key — or `null` for a State no store declared (a
 * `computed { }` one, a `MutableState` constructed by hand). Never its value.
 * Works after the store is disposed.
 */
@StoreInternalApi
val State<*>.internalQualifiedName: String?
    get() = observableBacking()?.declaration?.qualifiedName

/**
 * Throw when this transaction, or one enclosing it, forbids structural writes
 * on this thread ([internalForbidStructuralWrites]): [name] is a state
 * `removeState`/`clearStates` of [store] would drop. The caller holds the
 * registry lock.
 */
internal fun Transaction.refuseStructuralWriteHere(
    store: Store<*>,
    name: String,
) {
    val here = currentThreadId()
    var txn: Transaction? = this
    while (txn != null) {
        val reason = txn.structuralRefusal
        if (reason != null && txn.ownerThreadId == here) {
            throw IllegalStateException("Cannot remove state '$name' from ${store.displayName}: $reason")
        }
        txn = txn.parent
    }
}

/** Throw when [state] is sealed ([MutableState.writeSeal]), with its owner's teaching text. */
internal fun refuseSealedWrite(state: MutableState<*>) {
    val seal = state.writeSeal ?: return
    throw IllegalStateException("Cannot write ${state.declaration?.qualifiedName}: ${seal.refusal}")
}
