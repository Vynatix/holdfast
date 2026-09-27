@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.KeyedMembershipListener
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.internalObserveKeyedMembership
import com.vynatix.holdfast.internalQualifiedName
import com.vynatix.holdfast.observableBacking
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.taggedStates
import com.vynatix.holdfast.tags
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// What tells the overlay's writer that a UserAuthored state changed (issue
// #20, R8/R3; plan PR 14): an observer on every UserAuthored state and live
// entry of a UserAuthored keyed family, and a keyed-membership listener for
// the entries created later and for evictions. Installed by each seed, in its
// transaction before `base { }` runs (the first one installs the listener); a
// state or entry is observed once, and an evicted entry is forgotten.
//
// An observer fires once at once, with the current value — no change: each
// observer here ignores exactly its first callback. (Should a commit on
// another thread notify it before that first callback runs, the commit's
// callback is the one ignored and the first callback counts: either way one
// change is counted.)
//
// A seed's own commit is not a change the overlay writes: it applies what the
// overlay holds (or what `base { }` seeded). The seed is a blocking
// transaction, so its fanout — every observer and keyed-membership callback
// of its commit — runs on the seed's thread while its transaction is still the
// store's active one: a callback on the seed's thread that finds that
// transaction active is not counted. A bridge's inbound value (or an
// `observeFrom` source's) from ANOTHER thread while the seed runs is a change
// like any other, and is counted: it bypasses the seed's transaction, which
// the store's active-transaction slot cannot tell (the slot is the store's,
// not the thread's). One from the seed's own thread inside its fanout (a user
// observer of the seed's commit that drives a bridge inline) is taken for the
// seed's own, and is written with the next change of a UserAuthored state.

/**
 * The UserAuthored states of [store] under watch: calls [changed] for every
 * commit that changes one — or evicts an entry of a UserAuthored family —
 * except a seed's own. See the top of this file.
 */
internal class OverlayWatch(
    private val store: Store<*>,
    private val storeName: String,
    private val changed: () -> Unit,
) {
    private val lock = SynchronizedObject()

    /** The states observed, by identity. Guarded by [lock]. */
    private val watched = HashSet<State<*>>()

    /** The keyed-membership listener, installed by the first seed. Guarded by [lock]. */
    private var membership: Disposable? = null

    /**
     * The seed transaction running now, and its thread, whose own commit's
     * callbacks — on that thread — are not counted; `null` between seeds.
     */
    private val seeding = atomic<SeedMark?>(null)

    /** A seed transaction, [seed], begins on this thread: its commit is not a change. */
    fun beginSeed(seed: Transaction) {
        seeding.value = SeedMark(seed, currentThreadId())
    }

    /** The seed transaction has ended, committed or rolled back. */
    fun endSeed() {
        seeding.value = null
    }

    /**
     * In a seed transaction, before `base { }`: watch every UserAuthored
     * state and live entry of the store not watched yet — a never-read
     * state is materialized for it, its initializer running as its first
     * read would — and, the first time, the store's keyed-entry membership.
     *
     * @throws IllegalStateException when a UserAuthored state is also Secret.
     *   Declarations refuse that pairing (a state's tags, and a keyed
     *   family's, are checked where it is declared, and a derived state is
     *   never UserAuthored), so no store reaches this: the check keeps a
     *   Secret value from ever being written, should a later kind of state
     *   carry both.
     */
    fun watchAll() {
        synchronized(lock) {
            if (membership == null) membership = store.internalObserveKeyedMembership(Membership())
        }
        val authored = store.taggedStates(StateTag.UserAuthored)
        val secret = authored.filter { StateTag.Secret in it.tags }
        check(secret.isEmpty()) {
            "$storeName's hydrator persists its UserAuthored states, and " +
                secret.joinToString { it.internalQualifiedName ?: "a state" } + " is also Secret: a Secret value " +
                "never leaves memory. The seed was refused. Keep the secret in a Secret state of its own."
        }
        authored.forEach(::watch)
    }

    /** Observe [state], unless it is observed already; its first callback is not a change. */
    private fun watch(state: State<*>) {
        val backing = state.observableBacking() ?: return
        if (!synchronized(lock) { watched.add(backing) }) return
        val first = atomic(true)
        backing.observe { if (!first.getAndSet(false)) onChange() }
    }

    /**
     * A watched state changed, or a watched entry was evicted: count it,
     * unless a seed's own commit did it — a callback on the seed's thread
     * while its transaction is the store's active one. (On wasmJs every
     * thread is `0`, and nothing runs while a blocking seed does.)
     */
    private fun onChange() {
        val mark = seeding.value
        val seedsOwn = mark != null && store.activeTransaction === mark.txn && currentThreadId() == mark.thread
        if (!seedsOwn) changed()
    }

    /** Entries of UserAuthored families: watched once created, a change once evicted. */
    private inner class Membership : KeyedMembershipListener {
        override fun onEntryAdded(
            family: String,
            key: Any,
            entry: State<*>,
        ) {
            if (StateTag.UserAuthored in entry.tags) watch(entry)
        }

        override fun onEntryEvicted(
            family: String,
            key: Any,
            entry: State<*>,
        ) {
            if (StateTag.UserAuthored !in entry.tags) return
            synchronized(lock) { watched.remove(entry.observableBacking() ?: entry) }
            onChange()
        }
    }
}

/** A seed transaction, [txn], running on [thread]: see [OverlayWatch.beginSeed]. */
private class SeedMark(
    val txn: Transaction,
    val thread: Long,
)
