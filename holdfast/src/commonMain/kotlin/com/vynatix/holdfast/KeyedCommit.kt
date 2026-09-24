@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

// Committing keyed-entry evictions (issue #20, R7; plan D7, D18).
//
// A savepoint's commit merges its evictions into its parent's, the last
// operation on an entry winning (and dropping the parent's pending write to
// an entry the savepoint evicts). A top-level commit applies them in its
// apply pass (FrameCommit.kt): once every pending write is assigned, each
// evicted entry is retired (`MutableState.retired`) and dropped from its
// family, INSIDE the same write bracket — the bracket covers the evicted
// states too — so a consistent cut (`snapshot()`, ConsistentRead.kt) sees an
// eviction exactly when it sees that commit's writes, never one without the
// other: a capture reads each entry's retired flag inside its validated cut
// and leaves a retired entry out. (Creating an entry is no commit and has no
// bracket; a capture lists the entries again when one came to life between
// its listing and its cut while a commit applied — see captureConsistent.)
// Every apply pass counts itself on its store before its bracket opens
// (KeyedRegistry.commitsApplied), for that check, as every inbound bridge
// write does (MutableState.applyFromBridge). For a frame, every
// participant's evictions apply in the frame's one bracket. Rollback discards
// them, touching nothing.
//
// The fanout pass then starts by shutting the evicted entries down —
// observers dropped, bridge detached, `observeFrom` subscriptions disposed,
// no notification — and telling the membership listeners, before any
// observer of the commit runs. Nothing else changes for the entries that
// stay: their observers and bridges are untouched.

/**
 * The states this top-level transaction's apply pass writes: its pending
 * writes and the entries it evicts. A stable copy; the caller holds its
 * pending lock.
 */
internal fun Transaction.bracketedStates(): List<MutableState<*>> {
    if (evictions.isEmpty()) return pendingWrites.keys.toList()
    return pendingWrites.keys.toList() + evictions.filterValues { it }.keys
}

/**
 * Apply this top-level transaction's evictions: retire each evicted entry and
 * drop it from its family, and return them for the fanout to shut down. The
 * caller holds the pending lock and the write bracket over
 * [bracketedStates], and has assigned the pending writes.
 */
internal fun Transaction.retireEvictions(): List<MutableState<*>> {
    if (evictions.isEmpty()) return emptyList()
    val evicted = evictions.filterValues { it }.keys.toList()
    evictions.clear()
    for (state in evicted) checkNotNull(state.declaration?.keyed).family.unlink(state)
    return evicted
}

/**
 * Merge this savepoint's evictions into [parent]'s, the savepoint's decision
 * winning; an entry it evicts loses [parent]'s pending write too. The caller
 * holds both pending locks and has merged the pending writes.
 */
internal fun Transaction.mergeEvictionsInto(parent: Transaction) {
    for ((state, evicts) in evictions) {
        parent.evictions[state] = evicts
        if (evicts) parent.pendingWrites.remove(state)
    }
    evictions.clear()
}

/**
 * The first step of an evicting commit's fanout: shut every entry in
 * [evicted] down silently, tell each family's membership listeners, then
 * report what a shutdown threw (a bridge's or an `observeFrom` subscription's
 * `dispose`) through its store's `uncaughtObserverHandler` — every entry is
 * shut down before the first report, so a throwing handler, which ends the
 * fanout like any fanout failure, leaves none running.
 */
internal fun shutDownEvicted(evicted: List<MutableState<*>>) {
    val failures =
        evicted.mapNotNull { state -> runCatching { state.shutdownSilently() }.exceptionOrNull()?.let { state to it } }
    for (state in evicted) keyedRegistryOf(state).announceEvicted(state)
    for ((state, failure) in failures) state.owningStore.internalReportUncaughtFailure(failure)
}

/**
 * Refuse a write to [state] that no write may reach: a derived state's
 * ([refuseDerivedStateWrite]), or an evicted keyed entry's stale handle.
 *
 * @throws IllegalStateException naming the state (never a key) and the fix.
 */
internal fun refuseUnwritable(state: State<*>) {
    refuseDerivedStateWrite(state)
    val entry = state as? MutableState<*> ?: return
    check(!entry.retired) { staleEntryMessage(entry) }
}

/** The keyed state families of [state]'s store. */
private fun keyedRegistryOf(state: MutableState<*>): KeyedRegistry = state.owningStore.registry.keyed

private fun staleEntryMessage(entry: MutableState<*>): String {
    val family = entry.declaration?.keyed?.family
    return "Cannot write ${entry.declaration?.qualifiedName}: this entry of the keyed state family " +
        "${family?.qualifiedName} was evicted, so this State is a stale handle — the family no longer holds it, and " +
        "a value written to it could never be read back through the family. Fix: get the entry again " +
        "(${family?.name}[key]), which creates a new one from the family's initializer, and do not keep an entry's " +
        "State across an eviction."
}
