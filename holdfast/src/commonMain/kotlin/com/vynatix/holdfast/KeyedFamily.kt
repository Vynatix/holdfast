@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.threadYield
import kotlinx.atomicfu.atomic
import kotlin.reflect.KProperty

// The one KeyedState implementation (issue #20, R7): a family's live entries,
// how an entry is created — through the same latch, no-write region and
// cycle detection as a declared state (Materialization.kt) — and how it is
// dropped when its eviction commits.

/**
 * The keyed state family [name] of [store], declared by `keyedState` with
 * [spec]. Its live entries are kept by key in creation order: each is a
 * [StateKind.Keyed] declaration whose [StateDeclaration.keyed] names this
 * family and the key, and whose state is created from [spec]'s initializer.
 *
 * The entry map is guarded by [store]'s registry lock (`propertiesLock`),
 * which no initializer and no user code ever runs under. A declaration in it
 * with no materialized state is an entry being created: it is not live yet,
 * and every read of the family leaves it out.
 */
internal class KeyedFamily<K : Any, T : Any>(
    val store: Store<*>,
    val name: String,
    val spec: KeyedFamilySpec<K, T>,
    /** The delegated property this family was declared from; see [StateDeclaration.property]. */
    val property: KProperty<*>?,
    /** Whether [property] is a local (or top-level) delegated property. */
    val local: Boolean,
) : KeyedState<K, T> {
    /** Entries by key, in creation order. Guarded by [store]'s registry lock. */
    private val live = LinkedHashMap<K, StateDeclaration<T>>()

    /**
     * Bumped every time an entry of this family comes to life
     * ([publish]), after it is live. Creating an entry is not a commit,
     * so no write bracket covers it: a capture reads this before it lists the
     * family's entries and again after its cut ([captureConsistent]).
     */
    private val created = atomic(0L)

    /** See [created]; read without a lock. */
    val createdVersion: Long get() = created.value

    val tags: Set<StateTag> get() = spec.tags

    /** `Store.name`, for failure messages. */
    val qualifiedName: String
        get() = "${store.displayName}.$name"

    override fun get(key: K): State<T> {
        store.checkNotDisposed()
        val state = stateFor(key)
        store.cancelEvictionInView(state)
        // From an initializer a reset pass re-runs: the pass resets this
        // entry too, if it came to life during the pass (Reset.kt).
        store.activeTransaction?.pendingReset?.noteGot(state)
        return state
    }

    override fun getOrNull(key: K): State<T>? {
        store.checkNotDisposed()
        val view = store.evictionView()
        val state = store.registry.lock.withLock { live[key]?.materialized }
        return state?.takeIf { !it.retired && view?.evictsInChain(it) != true }
    }

    override fun contains(key: K): Boolean = getOrNull(key) != null

    override val entries: Map<K, State<T>>
        get() {
            store.checkNotDisposed()
            val view = store.evictionView()
            val visible = LinkedHashMap<K, State<T>>()
            for ((key, state) in committedEntries()) {
                if (view?.evictsInChain(state) != true) visible[key] = state
            }
            return visible
        }

    override fun evict(key: K) {
        stageEvictions("evict an entry of $qualifiedName") { listOfNotNull(getOrNull(key) as MutableState<*>?) }
    }

    override fun evictAll() {
        stageEvictions("evict every entry of $qualifiedName") { entries.values.map { it as MutableState<*> } }
    }

    /** Names the family, never a key. */
    override fun toString(): String = "KeyedState($qualifiedName)"

    /**
     * The live state of [key]'s entry, created now when it has none. The
     * entry's declaration goes into the map first (under the registry lock)
     * and its state is created outside it, through the declaration's latch
     * ([materialize]): threads asking for one key meanwhile wait for one
     * initializer, as for a declared state. A throwing initializer takes the
     * declaration back out, so the next call runs it again. A state found
     * retired — evicted, or created for a declaration dropped meanwhile
     * ([publish]) — is not returned; the call looks again.
     */
    fun stateFor(key: K): MutableState<T> {
        while (true) {
            val decl =
                store.registry.lock.withLock {
                    store.checkNotDisposed()
                    live[key] ?: if (store.creationHoldBack.heldBackLocked()) {
                        null
                    } else {
                        entryDeclaration(key).also { live[key] = it }
                    }
                }
            if (decl == null) {
                // A capture holds creation back for a moment (CreationHoldBack).
                threadYield()
                continue
            }
            var created = false
            val state =
                try {
                    materialize(decl).also { created = true }
                } finally {
                    if (!created) {
                        store.registry.lock.withLock {
                            if (live[key] === decl && decl.materialized == null) live.remove(key)
                        }
                    }
                }
            if (!state.retired) {
                store.registry.keyed.announceAdded(decl, state)
                return state
            }
        }
    }

    /**
     * Make [state] the live state of [decl], one of this family's entries
     * ([StateRegistry.publish]; the caller holds [decl]'s latch): under the
     * registry lock, once no capture holds creation back
     * ([CreationHoldBack]) — yielding outside the lock until then. A
     * declaration a failed creation took out of the map meanwhile, re-run by
     * a thread that was waiting for it, goes back in; one whose key has a
     * newer entry by now is an orphan, and its state is retired at once.
     *
     * @throws IllegalStateException if the store is disposed.
     */
    fun publish(
        decl: StateDeclaration<T>,
        state: MutableState<T>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val key = checkNotNull(decl.keyed).key as K
        while (true) {
            val published =
                store.registry.lock.withLock {
                    check(!store.isDisposed) { "store disposed" }
                    if (store.creationHoldBack.heldBackLocked()) return@withLock false
                    val current = live[key]
                    if (current == null) {
                        live[key] = decl
                    } else if (current !== decl) {
                        state.retired = true
                    }
                    decl.materialized = state
                    // After the entry is live: a capture that listed the entries
                    // before it reads the bump after its cut ([created]).
                    if (!state.retired) created.incrementAndGet()
                    true
                }
            if (published) return
            threadYield()
        }
    }

    /**
     * Retire [state], an entry whose eviction is committing, and take its
     * declaration out of the map, so the next [get] of its key creates a new
     * entry. Runs inside the commit's write bracket (KeyedCommit.kt).
     */
    fun unlink(state: MutableState<*>) {
        val decl = state.declaration ?: return
        store.registry.lock.withLock {
            state.retired = true
            val key = checkNotNull(decl.keyed).key
            if (live[key] === decl) live.remove(key)
        }
    }

    /** Every live entry — created, not retired — in creation order, as committed (no transaction's view). */
    fun committedEntries(): List<Pair<K, MutableState<T>>> =
        store.registry.lock.withLock {
            live.mapNotNull { (key, decl) -> decl.materialized?.takeIf { !it.retired }?.let { key to it } }
        }

    /**
     * Drop every entry (the store is being disposed) and return the live
     * states, for the caller to shut down outside the lock. The caller holds
     * the registry lock.
     */
    fun releaseAllLocked(): List<MutableState<*>> {
        val states = live.values.mapNotNull { it.materialized }
        live.clear()
        return states
    }
}

/** A new entry's declaration: named after the family (`docs[*]`, never the key), tagged like it. */
private fun <K : Any, T : Any> KeyedFamily<K, T>.entryDeclaration(key: K): StateDeclaration<T> =
    StateDeclaration(
        store = store,
        name = "$name[*]",
        kind = StateKind.Keyed,
        initializer = { spec.initialize(key) },
        transformer = spec.transformer,
        distinct = spec.distinct,
        codec = spec.codec,
        property = null,
        local = false,
        tags = spec.tags,
        keyed = KeyedEntry(this, key),
    )
