@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

// A blocking entry inside the store's own suspending body (issue #31).
//
// `:holdfast-coroutines`' `suspendAction`/`suspendAtomic` hold the store
// through its serializer mutex for their whole body. A blocking top-level
// entry on that store — `action`, `atomic(...)`, and `reset()`/`restore()`,
// which run through `action` — is not recognized as nested while
// [Store.suspendingOwner] is set ([Store.ownsActiveTransaction] disqualifies
// the check on purpose: the body may resume on any thread, so a recorded
// owner thread id would let an unrelated caller on that pooled thread
// interleave with it). It therefore takes the top-level path into
// `holdSerialized` and spins in the serializer's blocking acquire — on the
// very mutex the enclosing body's own coroutine holds. From inside that body
// the spin can never end, on any platform.
//
// The body is recognized by the signal issue #28 landed for reads: every
// suspending root records the settle scope it was opened in
// ([Transaction.openedInScope]), and the body carries that scope across every
// dispatch and hands it to the coroutines it launches
// (`SettleAmbientContext`). A blocking call cannot read the coroutine
// context, but it CAN read the scope the caller's thread has installed: the
// call is inside the body exactly when that scope's chain — itself, or a
// nested suspending entry's scope view of it (#38, [JoinedSettleScope]) —
// reaches the root's.
//
// Only the SAME store's body is a deadlock. A blocking entry on another
// store runs; a blocking entry beside a parked suspending holder — from a
// thread or coroutine whose scope is not the body's — keeps waiting for the
// holder as before (on wasmJs, where that wait could never end, the refusal
// of issue #27 still covers it). The probe requires a POSITIVE scope match:
// `null === null` must not read as "inside", so a root with no recorded
// scope never refuses anyone.

/**
 * Refuse a blocking top-level entry on this store — [attempt] names it for
 * the teaching message: `action { }` (which `reset()` and `restore()` run
 * through) or `atomic(…)` — when the call is made from inside THIS store's
 * own suspending body: a `suspendAction`/`suspendAtomic` (`:holdfast-coroutines`)
 * holding the store through its serializer mutex. The blocking acquire would
 * wait for the very mutex the enclosing body holds, which can only be
 * released by the body the call itself is blocking — a self-deadlock, so it
 * throws an [IllegalStateException] naming the store and the working
 * alternatives instead, BEFORE any wait (on wasmJs, before the single-threaded
 * refusal of issue #27, so every platform teaches the same message).
 *
 * "Inside the body" is decided by the settle-scope signal of issue #28: the
 * root transaction the body installed recorded the scope its entry opened
 * ([Transaction.openedInScope]), and that scope is installed on every thread
 * the body — and any coroutine it launched or that shares its context —
 * resumes on, and on nothing else. A nested suspending entry runs under a
 * scope VIEW of the enclosing one's (#38), so the check walks the chain
 * ([scopeChainReaches]): the body's own scope or a view nested in it both
 * count, an unrelated scope does not. See the top of this file.
 */
internal fun Store<*>.refuseInsideOwnSuspendingBody(attempt: String) {
    val inOwnBody =
        suspendingOwner != null &&
            // A positive scope match only, per the note at the top of this file.
            activeTransaction?.root?.openedInScope?.let { scopeChainReaches(SettleScopes.current(), it) } == true
    if (inOwnBody) throw IllegalStateException(ownSuspendingBodyMessage(displayName, attempt))
}

/**
 * Why [refuseInsideOwnSuspendingBody] refuses: names [store], the deadlock,
 * and what works instead — `mutate`/`update` stage into the suspending
 * transaction, the blocking call can be made once the body has returned, and
 * a blocking entry on another store (or a suspending entry anywhere) is not
 * affected.
 */
private fun ownSuspendingBodyMessage(
    store: String,
    attempt: String,
): String =
    "A blocking $attempt on $store inside that store's own suspending body (a suspendAction or " +
        "suspendAtomic holding it) would never return: the body holds the store's suspend mutex and " +
        "can only go on once this call does. Stage the write with `mutate`/`update` (e.g. " +
        "`store { state mutate value }`), which joins the suspending transaction, or make the " +
        "blocking call once that body has returned. A blocking $attempt on another store — and a " +
        "suspending entry on any store — is not affected."
