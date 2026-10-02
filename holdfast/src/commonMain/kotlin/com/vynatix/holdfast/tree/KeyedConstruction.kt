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
// run the branch's declared factory HOLDING NO LOCK of the tree's (only
// whatever the caller holds), marked on this thread so the factory needing
// its own key again — directly or through child lambdas and initializers —
// throws a cycle error instead of recursing. Racing creators of one key may
// each run the factory; the first whose store claims the key in the
// registry (`ChildRegistry.claimKey`) attaches it through the same phases a
// `store { }` child goes through (`TreeAttach.kt`), with the registry's
// promotion as phase 4's registry step, holding the entry's construction
// lock through those phases (no user code runs there). A losing
// `getOrCreate` returns the winner; a losing `create` fails "already
// exists". Either way the loser disposes what its own run built that
// nothing else holds (`RunBuilt`), as does a run whose attach failed after
// the factory returned (wrong class, a parent already, the owner disposed);
// a store that existed before the run is never disposed. A loser's store is
// never attached or announced.

/** [KeyedBranch.create]: run the factory, claim the key, attach — or fail and leave no entry. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.createKeyed(key: K): S {
    owner.checkNotDisposed()
    val leafName = leafNameOrRefuse(key)
    return settling {
        check(registry.keyedEntry(this, key) == null) { alreadyExists(key) }
        val (produced, built) = runFactory(key)
        val (entry, claimed) = claimOrDispose(key, leafName, produced, built)
        if (!claimed) {
            built.disposeOrphans(listOf(produced), keep = listOfNotNull(entry.liveStore()))
            error(alreadyExists(key))
        }
        attachClaimed(entry, key, produced, built)
    }
}

private fun KeyedBranch<*, *>.alreadyExists(key: Any) =
    "${owner.displayName}: $name.create($key) — a store for this key already exists (or is being created); " +
        "use getOrCreate(key)"

/** [KeyedBranch.getOrCreate]: the live store for [key], else run the factory and race to attach its store. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.getOrCreateKeyed(key: K): S {
    owner.checkNotDisposed()
    // Named once, before any lock: the codec is user code, and the name is
    // only ever used by a claim — never re-run on a wake-up, nor on a hit,
    // where the registry discards it.
    val leafName = leafNameOrRefuse(key)
    return settling { existingOrNull(key) ?: raceToAttach(key, leafName) }
}

/** The store already live under [key] (after parking while another thread finishes attaching it), else `null`. */
private fun <K : Any, S : Store<S>> KeyedBranch<K, S>.existingOrNull(key: K): S? {
    while (true) {
        val entry = registry.keyedEntry(this, key) ?: return null
        awaitAttached(entry)?.let { return it }
    }
}

/**
 * [entry]'s store once its attach ended — parking while another thread
 * still attaches it — or `null` when that attach failed and the entry is
 * gone (the caller looks again).
 */
@Suppress("UNCHECKED_CAST")
internal fun <S : Store<S>> KeyedBranch<*, S>.awaitAttached(entry: LeafEntry): S? {
    while (true) {
        when (val phase = entry.phase) {
            is LeafEntry.Phase.Live -> if (!entry.parkWhileAttachingElsewhere()) return phase.store as S
            LeafEntry.Phase.Detached -> return null
            LeafEntry.Phase.Constructing -> {
                check(entry.constructingThreadId != currentThreadId()) {
                    "${owner.displayName}: $name: a lookup of a key from inside its own attach"
                }
                entry.constructionLock.withLock { }
            }
        }
    }
}

private fun LeafEntry.liveStore(): Store<*>? = (phase as? LeafEntry.Phase.Live)?.store

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
