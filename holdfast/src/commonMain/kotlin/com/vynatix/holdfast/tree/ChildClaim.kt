@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.platform.currentThreadId

// The claim half of a child's materialization (TreeMaterialize.kt): racing
// first reads each run the lambda holding nothing; the first to claim the
// entry (Declared → Constructing, under the registry lock) attaches its
// result while holding the entry's attach lock — no user code runs there —
// and every other reader parks on that lock, then takes the winner's child.

/** What [ChildEntry] reads as under the registry lock, for a reader deciding what to do. */
private sealed interface Seen {
    /** The produced child: the answer. */
    class Produced(
        val child: Any,
    ) : Seen

    /** The owner disposed. */
    data object Closed : Seen

    /** Declared: free to claim. */
    data object Free : Seen

    /** Another claim is attaching it. */
    data object Busy : Seen
}

/** Under the registry lock: what [entry] reads as. */
private fun seenLocked(entry: ChildEntry): Seen {
    val produced = entry.produced
    return when {
        produced != null -> Seen.Produced(produced)
        entry.registry.disposed -> Seen.Closed
        entry.phase == ChildEntry.Phase.Declared -> Seen.Free
        else -> Seen.Busy
    }
}

/**
 * Before running the lambda: the produced child when it is already there
 * (after parking while another thread finishes attaching it), else `null`
 * once the entry is free to claim.
 *
 * @throws IllegalStateException if the owner disposed.
 */
internal fun awaitClaimable(entry: ChildEntry): Any? {
    var answer: Any? = null
    var free = false
    while (answer == null && !free) {
        when (val seen = entry.registry.lock.withLock { seenLocked(entry) }) {
            is Seen.Produced -> answer = seen.child
            Seen.Closed -> closedOwner(entry)
            Seen.Free -> free = true
            Seen.Busy -> answer = parkOnClaim(entry)
        }
    }
    return answer
}

/**
 * Claim [entry] for [candidate] (`null`: this thread won and holds the
 * entry's attach lock), or answer the child another thread attached first.
 * While another claim is still attaching, parks on it and claims again.
 *
 * @throws IllegalStateException if the owner disposed; the run's stores are disposed first.
 */
internal fun claimOrJoin(
    entry: ChildEntry,
    candidate: Candidate,
    built: RunBuilt,
): Any? {
    var answer: Any? = null
    var won = false
    while (answer == null && !won) {
        val seen =
            entry.registry.lock.withLock {
                seenLocked(entry).also { if (it == Seen.Free) claimLocked(entry, candidate) }
            }
        when (seen) {
            is Seen.Produced -> answer = seen.child
            Seen.Closed -> {
                built.disposeOrphans(candidate.stores)
                closedOwner(entry)
            }
            Seen.Free -> won = true
            Seen.Busy -> answer = parkOnClaim(entry)
        }
    }
    return answer
}

/** Under the registry lock, on a free entry: Declared → Constructing, this thread holding the attach lock. */
private fun claimLocked(
    entry: ChildEntry,
    candidate: Candidate,
) {
    check(entry.attachLock.tryAcquire()) { "${entry.label}: a free entry's attach lock was held" }
    entry.attachingThreadId = currentThreadId()
    entry.attaching = candidate.produced
    entry.phase = ChildEntry.Phase.Constructing
    entry.node = candidate.node
}

/**
 * Wait for another thread's claim of [entry] to end (its attach phases run
 * no user code), answering `null` so the caller reads the entry again. On
 * the claiming thread itself — a listener told of the child reading it —
 * answer the claim's child at once.
 */
private fun parkOnClaim(entry: ChildEntry): Any? {
    if (entry.attachingThreadId == currentThreadId()) entry.attaching?.let { return it }
    entry.attachLock.withLock { }
    return null
}

private fun closedOwner(entry: ChildEntry): Nothing {
    entry.owner.checkNotDisposed()
    error("${entry.owner.displayName} disposed")
}
