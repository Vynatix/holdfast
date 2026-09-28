package com.vynatix.holdfast

/**
 * Token that binds a [Store] into library-owned membership machinery at
 * construction (issue #21 plan, PR 21-1, decision U3). Issue #21's typed
 * tree is the first consumer: `KeyedBranch.at(key)` mints the only
 * implementation, carrying the leaf's `KClass` for a runtime `isInstance`
 * check against the store it binds.
 *
 * `internal constructor()` keeps every implementation inside the `:holdfast`
 * module. Application code can only ever accept a token — through a
 * subclass's `@ExperimentalStoreApi protected constructor(membership:
 * StoreMembership<Self>)` (see [Store] and `EventfulStore`) — never mint one,
 * so a bare `ThreadStore(id)` cannot stand in for the tree's own
 * `App.threads.create(id) { ThreadStore(it) }`.
 *
 * [bind] runs inside the accepting constructor's body, after every [Store]
 * field above it — and, for an `EventfulStore` subclass, its own fields —
 * has initialized, and before any subclass property initializer or
 * delegated state runs: the store being bound is still under construction.
 */
@ExperimentalStoreApi
abstract class StoreMembership<S : Store<S>> internal constructor() {
    /**
     * Bind [store] into whatever this token represents. Called exactly once,
     * by the [Store] (or `EventfulStore`) constructor that accepted this
     * token, on the thread constructing [store]. Must not read [store]'s
     * declarations, open a transaction or frame on it, or touch another
     * store — [store] is still under construction, and other threads may be
     * waiting on structures this call has not finished setting up.
     *
     * `internal` — application code never calls it; only the token's own
     * minter needs to.
     */
    internal abstract fun bind(store: Store<S>)
}
