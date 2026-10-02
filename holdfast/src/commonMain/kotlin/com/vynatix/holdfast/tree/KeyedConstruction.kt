@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.describeClass
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.settling

// Keyed creation (issue #21): `KeyedBranch.create(key)`/`getOrCreate(key)`
// reserve the key — holding the entry's construction lock from the
// reservation on, so a racing `getOrCreate` parks instead of spinning — run
// the branch's declared factory outside every lock of the tree, and attach
// the store it returned through the same phases a `store { }` child goes
// through (`TreeAttach.kt`), with the registry's promotion as phase 4's
// registry step. The store is live once phase 4 returned; a failure before
// that abandons the reservation and leaves no entry (the tree disposes
// nothing: a store a failing factory built is the factory's own).

/** [KeyedBranch.create]: reserve the key, run the factory, attach — or fail and leave no entry. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.createKeyed(key: K): S {
    owner.checkNotDisposed()
    val leafName = leafNameOrRefuse(key)
    val (entry, reserved) = registry.reserveOrExisting(this, key, leafName)
    check(reserved) {
        "${owner.displayName}: $name.create($key) — a store for this key already exists (or is being created); " +
            "use getOrCreate(key)"
    }
    return constructReserved(entry, key)
}

/** [KeyedBranch.getOrCreate]: the live store for [key], else construct it; parks on another thread's construction. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.getOrCreateKeyed(key: K): S {
    owner.checkNotDisposed()
    // Named once, before any lock: the codec is user code, and the name is
    // only ever used by a reservation — never re-run on a wake-up, nor on a
    // hit, where the registry discards it.
    val leafName = leafNameOrRefuse(key)
    while (true) {
        owner.checkNotDisposed()
        val (entry, reserved) = registry.reserveOrExisting(this, key, leafName)
        if (reserved) return constructReserved(entry, key)
        val phase = entry.phase
        if (phase is LeafEntry.Phase.Live && !entry.parkWhileAttachingElsewhere()) {
            @Suppress("UNCHECKED_CAST")
            return phase.store as S
        }
        if (phase is LeafEntry.Phase.Live) continue
        check(entry.constructingThreadId != currentThreadId()) {
            "${owner.displayName}: $name.getOrCreate($key) called from inside the factory constructing that very " +
                "key — a cycle"
        }
        // Another thread reserved it and holds its construction lock from the
        // reservation on (`ChildRegistry.reserveOrExisting`), through its
        // factory and attach: park there — never spin, not even before its
        // factory starts — then read the registry again (it is Live, or gone
        // after a failure).
        entry.constructionLock.withLock { }
    }
}

/**
 * [KeyedBranch.get]: the live store for [key], type-erased — once another
 * thread still attaching it (registered in phase 4, its ring sync and
 * announce still running under the construction lock) has finished, so a
 * lookup never hands out a store before its tree middleware and listeners
 * cover it.
 */
internal fun KeyedBranch<*, *>.lookupKeyed(key: Any): Store<*>? {
    while (true) {
        val (entry, store) = registry.liveEntry(this, key) ?: return null
        if (!entry.parkWhileAttachingElsewhere()) return store
    }
}

/** [KeyedBranch.entries], type-erased: parks, as [lookupKeyed] does, on each entry another thread is attaching. */
internal fun KeyedBranch<*, *>.entriesKeyed(): List<Pair<Any, Store<*>>> {
    while (true) {
        val live = registry.liveEntries(this)
        if (live.none { (entry, _) -> entry.parkWhileAttachingElsewhere() }) {
            return live.map { (entry, store) -> entry.key to store }
        }
    }
}

/**
 * Whether this LIVE entry was still being attached by another thread —
 * whose construction lock is held through phases 5 and 6 — in which case
 * this call parked until that attach ended (the caller reads again). The
 * constructing thread itself (a listener told of the store) is answered at
 * once; on wasmJs every thread is that thread.
 */
private fun LeafEntry.parkWhileAttachingElsewhere(): Boolean {
    val constructing = constructingThreadId
    val elsewhere = constructing != null && constructing != currentThreadId()
    if (elsewhere) constructionLock.withLock { }
    return elsewhere
}

/**
 * The leaf name for [key] through the branch's key codec (`toString()`
 * without one), computed once per `create`/`getOrCreate`. A refusal —
 * whatever exception the codec or `toString()` threw: a lookup table's
 * `Map.getValue` throws a `NoSuchElementException` quoting the key, and a
 * `ClassCastException` or `NullPointerException` may — is rethrown with the
 * branch context every other tree error carries: the store and branch names
 * only, never the key or the codec's own message, which may quote it (so
 * the codec's exception is not attached either).
 */
private fun <K : Any> KeyedBranch<K, *>.leafNameOrRefuse(key: K): String =
    try {
        leafNameFor(key)
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Exception, // Whatever naming throws may quote the key.
    ) {
        keyNamingError(failure)
    }

private fun KeyedBranch<*, *>.keyNamingError(failure: Throwable): Nothing =
    error(
        "${owner.displayName}: $name.create/getOrCreate could not name the key: naming it threw " +
            "${failure.describeClass()} (from the branch's key codec, or the key's toString() on a branch " +
            "without one); the key and that message are withheld",
    )

/**
 * Construct the store for [entry], one this thread just reserved through
 * `ChildRegistry.reserveOrExisting` (so it holds the entry's construction
 * lock), inside a settle scope — joining the one open on this thread, else
 * one of its own — so a recompute the attach queues (an ancestor's tree
 * value following the new store) runs once this returns, never inside the
 * factory. Releases the construction lock once the store attached or the
 * reservation was abandoned.
 */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.constructReserved(
    entry: LeafEntry,
    key: K,
): S = settling { constructUnsettled(entry, key) }

private fun <K : Any, S : Store<S>> KeyedBranch<K, S>.constructUnsettled(
    entry: LeafEntry,
    key: K,
): S {
    entry.constructingThreadId = currentThreadId()
    var promoted = false
    try {
        // Phase 1: the factory, outside every lock of the tree; verify; take the attachment.
        val produced = factory(key)
        check(storeClass.isInstance(produced)) {
            "${owner.displayName}: $name is declared as stores<…, ${storeClass.simpleName}> but the factory " +
                "returned ${produced::class.simpleName}"
        }
        check(!produced.isDisposed) {
            "${owner.displayName}: the factory for $name.create($key) returned a disposed store"
        }
        val attachment = produced.treeAttachment()
        // Phase 2: claim the entry.
        registry.lock.withLock {
            registry.checkOpen()
            entry.phase = LeafEntry.Phase.Constructing
            entry.leaf = attachment.node
        }
        // Phases 3 and 4: link, then register through the promotion.
        val target = AttachTarget(attachment, entry.leafName, NameOrigin.Key, key)
        val attach = ChildAttach(owner, registry, name, this, listOf(target), entry)
        attach.link()
        attach.publish { registry.promoteLocked(entry, produced) }
        promoted = true
        // Phases 5 and 6 cannot fail the create.
        attach.syncRings()
        attach.announce()
        return produced
    } finally {
        if (!promoted) registry.abandon(entry)
        entry.constructingThreadId = null
        entry.constructionLock.release()
    }
}
