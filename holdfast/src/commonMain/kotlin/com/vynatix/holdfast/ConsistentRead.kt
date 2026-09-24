@file:OptIn(ExperimentalStoreApi::class) // SnapshotScope.All, the scope of a full capture.

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.threadYield

// Consistent reads of several states (issue #20, plan decision D7).
//
// Every assignment of a state's committed value sits inside a write bracket:
// its `writesBegun` counter is bumped before, its `writesEnded` counter after.
// A commit opens the bracket on EVERY state it applies before assigning any of
// them, and closes them all only after the last assignment. A reader that
// finds no bracket open, reads the values, and then finds no bracket opened
// since has therefore read a cut no commit was half-way through: a commit
// whose first write it saw had already opened every one of its brackets, so
// the reader would have seen a bracket open or moved.
//
// Readers never lock and never make a writer wait. They retry — yielding
// between attempts — while a write is in flight. One bracket spans a single
// transaction's apply pass, a bridge's single inbound write, or — for an
// `atomic`/`suspendAtomic` frame — the apply pass of every participant that
// opened a top-level root, at once (FrameCommit.kt), so a cut across several
// stores ([captureConsistent]) sees such a frame whole or not at all. A
// participant a frame shares with an enclosing action or frame is a savepoint:
// its writes apply when the enclosing transaction commits, so until then a cut
// can see the frame's other stores new and that one old (and for good if the
// enclosing entry rolls back). Enrolling every store in the outermost frame
// keeps the guarantee.

/** Open the write bracket of every state in [states], before any of them is assigned. */
internal fun openWriteBracket(states: Iterable<MutableState<*>>) {
    for (state in states) state.writesBegun.incrementAndGet()
}

/** Close the write bracket of every state in [states], after all of them are assigned. */
internal fun closeWriteBracket(states: Iterable<MutableState<*>>) {
    for (state in states) state.writesEnded.incrementAndGet()
}

/**
 * The raw committed values of [states], in order, as of one instant at which
 * no write bracket was open on any of them — never part of one commit and part
 * of the one before. Lock-free; retries while a write is in flight.
 */
internal fun readConsistent(states: List<MutableState<*>>): List<Any> {
    val begun = LongArray(states.size)
    while (true) {
        tryReadCut(states, begun)?.let { return it }
        threadYield()
    }
}

/** One attempt of [readConsistent]: the values, or `null` if a write overlapped. */
private fun tryReadCut(
    states: List<MutableState<*>>,
    begun: LongArray,
): List<Any>? {
    for (i in states.indices) {
        begun[i] = states[i].writesBegun.value
        if (begun[i] != states[i].writesEnded.value) return null
    }
    val values = states.map { it.rawCurrentValue }
    val noWriteBegan = states.indices.all { states[it].writesBegun.value == begun[it] }
    return if (noWriteBegan) values else null
}

/**
 * Snapshots of [stores], in order, all taken from ONE consistent cut: every
 * declared state [scope] captures is materialized first, store by store,
 * taking no store lock (an initializer reads committed values; a caller inside
 * an action still holds its locks), then a single [readConsistent] reads them
 * all. A frame applies all its top-level participants inside one write
 * bracket, so the snapshots hold every participant of any frame whose
 * participants all applied as top-level roots (an outermost frame, or a nested
 * one sharing no store with its enclosing entry) either before it or after
 * it, never a mix — the multi-store cut issue #21's `Root` snapshot builds on.
 * A participant joined as a savepoint of an enclosing transaction applies with
 * that transaction instead (see the note at the top of this file).
 *
 * @throws IllegalStateException if a store is disposed, or as [snapshot] (a
 *   throwing initializer).
 */
internal fun captureConsistent(
    stores: List<Store<*>>,
    scope: SnapshotScope = SnapshotScope.All,
): List<StoreSnapshot> {
    val plans = stores.map { it.planCapture(scope) }
    val values = readConsistent(plans.flatMap { it.states })
    var from = 0
    return plans.map { plan ->
        val until = from + plan.states.size
        plan.build(values.subList(from, until)).also { from = until }
    }
}
