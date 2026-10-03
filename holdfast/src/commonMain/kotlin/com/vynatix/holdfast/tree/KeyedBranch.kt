@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.reflect.KClass

/**
 * A family of a store's children created per key at runtime
 * (`val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }`),
 * one [LeafNode] per live key, named by the key's encoding through
 * [keyCodec] (`toString()` without one; such a branch is never encoded).
 *
 * The factory is declared once, with the branch; [create] and [getOrCreate]
 * run it for a key and attach the store it returns. The factory is ordinary
 * code run on the calling thread holding no lock of the tree's (only what
 * the caller holds: inside an action it sees that action's uncommitted
 * writes; inside a state initializer, a derived-state compute or a migrate
 * it inherits that no-write region, so a factory that opens an action fails
 * there — retryably). Racing creators of one key may each run the factory:
 * the first whose store claims the key attaches it, [getOrCreate] on every
 * other thread returns that store, [create] there fails, and each loser
 * disposes the store its own run built (never a store that existed before
 * the run, nor one some tree parent holds); a loser's store is never
 * attached or announced. A store is live — found by [get], [entries],
 * `tree.stores`, captures, the tree's value, reset and tree middleware —
 * once its factory has returned and it attached ([get], [entries] and
 * [getOrCreate] on another thread answer it only once its attach has also
 * synced its tree middleware and told the membership listeners; a listing
 * may include it a moment earlier); a throwing factory leaves no entry
 * behind (whatever store it built is its own), and an attach that fails
 * after the factory returned (a store of the wrong class, one that already
 * has a parent, the declaring store disposed meanwhile) disposes the
 * store(s) that run built. Attaching is structural, not transactional: a
 * rollback of the action the create ran in does not undo it. A live keyed
 * store leaves the branch when it is disposed ([dispose], [disposeAll] or
 * its own `dispose()`).
 *
 * The branch OWNS its stores — its factory built every one of them — so
 * when the declaring store disposes, every live keyed store is disposed
 * with it ([onParentDispose] [KeyedDisposal.Dispose], the default), or
 * released as a subtree root that keeps working ([KeyedDisposal.Release]).
 * Either way each store is first released (it leaves the branch and becomes
 * a parentless root); under `Dispose` it is then disposed once the declaring
 * store's dispose has released every tree lock — inside an action or
 * frame, when that entry settles.
 *
 * Present from the declaration on: `tree.children()` lists it with or
 * without entries.
 */
@ExperimentalStoreApi
class KeyedBranch<K : Any, S : Store<S>> internal constructor(
    /** The node of the store that declared this branch. */
    override val parent: LeafNode,
    override val name: String,
    override val nameOrigin: NameOrigin,
    spec: KeyedSpec<K, S>,
    internal val owner: Store<*>,
    internal val registry: ChildRegistry,
) : StoreNode {
    /** The key type, for messages and the persisted-name self-check. */
    val keyClass: KClass<K> = spec.keyClass

    /** The store type every key's store must be (a subclass is accepted). */
    val storeClass: KClass<S> = spec.storeClass

    /** How keys are written into `encode()` and read back by `decode`; `null` means this branch is never encoded. */
    val keyCodec: StateCodec<K>? = spec.keyCodec

    /** What the branch's live stores become when the declaring store disposes. */
    val onParentDispose: KeyedDisposal = spec.onParentDispose

    internal val factory: (K) -> S = spec.factory

    /**
     * Run the declared factory for [key] and make its store live under this
     * branch. A nested `create` for another key inside the factory attaches
     * the inner store first; the factory needing its own key again (through
     * [create], [getOrCreate], a child lambda or an initializer) throws a
     * cycle error. A dispose of the declaring store that lands after the
     * store attached does not fail the call: the store is returned live and
     * unattached (a subtree root).
     *
     * @throws IllegalStateException if a store for [key] already exists or
     *   another thread's factory run attached one first (use [getOrCreate]
     *   to share one; this run's store is disposed), if the factory returned
     *   a store of another class, a disposed store or one that already has
     *   a parent, or if the declaring store is disposed; a throwing factory
     *   propagates. Either way no entry is left behind.
     */
    fun create(key: K): S = createKeyed(key)

    /**
     * The live store for [key], else [create] it — the by-id idiom. Racing
     * calls may each run the factory; every one returns the ONE store that
     * attached first (the others' stores are disposed). It parks only while
     * another thread finishes attaching the key's store — a moment that runs
     * no user code — and never while a factory runs; from inside the factory
     * constructing this very key it throws a cycle error instead.
     *
     * @throws IllegalStateException as [create] does, or if the declaring
     *   store is disposed.
     */
    fun getOrCreate(key: K): S = getOrCreateKeyed(key)

    /**
     * The live store for [key], or `null` when none has been created, it is
     * still being constructed, or it was disposed. A store another thread
     * is still attaching — registered, but its tree middleware not yet
     * synced or its membership listeners not yet told — is answered once
     * that attach ends: this call parks until then.
     *
     * WARNING — despite the operator syntax this is not a plain map read: it
     * may PARK the calling thread while another thread finishes attaching
     * the key's store. Do not call it where blocking is not allowed (a
     * membership listener, a lock-holding hot path).
     *
     * @throws IllegalStateException if the declaring store is disposed.
     */
    operator fun get(key: K): S? {
        owner.checkNotDisposed()
        @Suppress("UNCHECKED_CAST")
        return lookupKeyed(key) as S?
    }

    /**
     * The live stores by key, in creation order. A copy. Parks, as [get]
     * does, while another thread is still attaching one of them.
     *
     * WARNING — this is not a cheap read: it may PARK the calling thread
     * (once per entry another thread is still attaching), and it builds a
     * fresh map on every call. Call it once and hold the copy.
     *
     * @throws IllegalStateException if the declaring store is disposed.
     */
    fun entries(): Map<K, S> {
        owner.checkNotDisposed()
        val out = LinkedHashMap<K, S>()
        for ((key, store) in entriesKeyed()) {
            @Suppress("UNCHECKED_CAST")
            out[key as K] = store as S
        }
        return out
    }

    /**
     * Dispose the live store under [key]: it leaves the branch, as any
     * disposed keyed store does. `false` when there is none (never created,
     * still inside its factory, or already disposed). Parks, as [get] does,
     * while another thread finishes attaching it.
     *
     * @throws IllegalStateException if the declaring store is disposed.
     */
    fun dispose(key: K): Boolean {
        owner.checkNotDisposed()
        val store = lookupKeyed(key)?.takeUnless { it.isDisposed } ?: return false
        store.dispose()
        return true
    }

    /**
     * Dispose every live store of the branch, in creation order (as
     * [entries] lists them); a store created meanwhile stays.
     *
     * @throws IllegalStateException if the declaring store is disposed.
     */
    fun disposeAll() {
        owner.checkNotDisposed()
        for ((_, store) in entriesKeyed()) store.dispose()
    }

    /** The live leaf under [key]; `null` on a closed registry, never a throw. */
    internal fun liveLeaf(key: Any): LeafNode? = registry.liveLeaf(this, key)

    internal fun leafNameFor(key: K): String = keyCodec?.encode(key) ?: key.toString()

    override fun toString(): String = "KeyedBranch($name)"
}
