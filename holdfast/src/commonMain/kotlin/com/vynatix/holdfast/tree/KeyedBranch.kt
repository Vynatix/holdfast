@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.reflect.KClass

/**
 * A family of a store's children created per key at runtime
 * (`val threads by stores<String, ThreadStore> { id -> ThreadStore(id) }`),
 * one [LeafNode] per live key, named by the key's encoding through
 * [keyCodec] (`toString()` without one; such a branch is never encoded).
 *
 * The factory is declared once, with the branch; [create] and [getOrCreate]
 * run it for a key and attach the store it returns. A store is live — found
 * by [get], [entries], `tree.stores`, captures, the tree's value, reset and
 * tree middleware — once its factory has returned and it attached ([get],
 * [entries] and [getOrCreate] on another thread answer it only once its
 * attach has also synced its tree middleware and told the membership
 * listeners; a listing may include it a moment earlier); a
 * throwing factory leaves no entry behind (whatever store it built is its
 * own: the tree disposes nothing). A live keyed store leaves the branch when
 * it is disposed; when the declaring store disposes, every keyed store is
 * released as a subtree root and keeps working.
 *
 * Present from the declaration on: `tree.children` lists it with or without
 * entries.
 */
@ExperimentalStoreApi
// One branch's declaration fields; the factory, owner and registry are carried from provideDelegate.
@Suppress("LongParameterList")
class KeyedBranch<K : Any, S : Store<S>> internal constructor(
    /** The node of the store that declared this branch. */
    override val parent: LeafNode,
    override val name: String,
    /** The key type, for messages and the persisted-name self-check. */
    val keyClass: KClass<K>,
    /** The store type every key's store must be (a subclass is accepted). */
    val storeClass: KClass<S>,
    /** How keys are written into `encode()` and read back by `decode`; `null` means this branch is never encoded. */
    val keyCodec: StateCodec<K>?,
    internal val factory: (K) -> S,
    internal val owner: Store<*>,
    internal val registry: ChildRegistry,
) : StoreNode {
    override val nameOrigin: NameOrigin get() = NameOrigin.Property

    /**
     * Run the declared factory for [key] and make its store live under this
     * branch. A nested `create` for another key inside the factory attaches
     * the inner store first. A dispose of the declaring store that lands
     * after the store attached does not fail the call: the store is returned
     * live and unattached (a subtree root).
     *
     * @throws IllegalStateException if a store for [key] already exists or
     *   is being created (use [getOrCreate] to share one), if the factory
     *   returned a store of another class, a disposed store or one that
     *   already has a parent, or if the declaring store is disposed; a
     *   throwing factory propagates. Either way no entry is left behind.
     */
    fun create(key: K): S = createKeyed(key)

    /**
     * The live store for [key], else [create] it — the by-id idiom. While
     * another thread is constructing it, this call parks until that
     * construction ends (never spins); from inside the factory constructing
     * this very key it throws a cycle error instead. Do not hold a store
     * lock the factory needs while calling this.
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
     * @throws IllegalStateException if the declaring store is disposed.
     */
    val entries: Map<K, S>
        get() {
            owner.checkNotDisposed()
            val out = LinkedHashMap<K, S>()
            for ((key, store) in entriesKeyed()) {
                @Suppress("UNCHECKED_CAST")
                out[key as K] = store as S
            }
            return out
        }

    /** The live leaf under [key]; `null` on a closed registry, never a throw. */
    internal fun liveLeaf(key: Any): LeafNode? = registry.liveLeaf(this, key)

    internal fun leafNameFor(key: K): String = keyCodec?.encode(key) ?: key.toString()

    override fun toString(): String = "KeyedBranch($name)"
}
