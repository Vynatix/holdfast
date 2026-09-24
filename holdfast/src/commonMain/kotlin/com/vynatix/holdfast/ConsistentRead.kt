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
// between attempts — while a write is in flight. Today one bracket spans one
// store's apply pass (and a bridge's single inbound write); a frame can open
// one bracket across all its participants' states to give a multi-store cut.

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
