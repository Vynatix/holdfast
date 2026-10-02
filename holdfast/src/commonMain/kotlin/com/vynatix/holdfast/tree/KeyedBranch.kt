@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreMembership
import kotlin.reflect.KClass

/**
 * A branch whose stores are created per key at runtime
 * (`val threads by keyed<String, ThreadStore>()`), one [LeafNode] per live
 * key, named by the key's encoding through [keyCodec] (`toString()` without
 * one; such a branch is never encoded).
 *
 * A keyed store is constructed through [create] or [getOrCreate], never
 * bare: its class header takes [at] —
 * `class ThreadStore(id: String) : Store<ThreadStore>(App.threads.at(id))` —
 * which is valid only inside the factory of a `create`/`getOrCreate` for
 * this branch and key on the calling thread, and throws a teaching error
 * anywhere else. The store is live — found by `Root.get`, `entries`,
 * `children`, captures, `value`, reset and tree middleware — only once the
 * factory has returned the instance that took the token; a throwing factory,
 * a factory returning another instance or a disposed store leaves nothing
 * behind (the abandoned store is disposed). A live keyed store leaves the
 * tree when it is disposed.
 */
@ExperimentalStoreApi
class KeyedBranch<K : Any, S : Store<S>> internal constructor(
    override val root: Root,
    override val parent: StoreNode,
    override val name: String,
    /** The key type, for messages and the persisted-name self-check. */
    val keyClass: KClass<K>,
    /** The store type every key's store must be (a subclass is accepted). */
    val storeClass: KClass<S>,
    /** How keys are written into `encode()` and read back by `decode`; `null` means this branch is never encoded. */
    val keyCodec: StateCodec<K>?,
) : StoreNode {
    override val nameOrigin: NameOrigin get() = NameOrigin.Property

    /**
     * The membership token a keyed store's constructor passes to [Store]:
     * `class ThreadStore(id: String) : Store<ThreadStore>(App.threads.at(id))`.
     * Valid only inside the factory of a [create]/[getOrCreate] for this
     * branch and [key] on this thread, once per create.
     *
     * @throws IllegalStateException outside such a factory, for another
     *   branch or key, a second time inside one create, or on a disposed root.
     */
    fun at(key: K): StoreMembership<S> = mintAt(key)

    /**
     * Construct the store for [key] through [factory] and make it live. The
     * factory must construct exactly one store that takes [at] for this
     * key and return it; `create(id, ::ThreadStore)` is the usual shape.
     * A nested `create` for another key inside the factory attaches the
     * inner store first.
     *
     * @throws IllegalStateException if a store for [key] already exists or is
     *   being created (use [getOrCreate] to share one), if the factory's store
     *   did not take [at] or returned another instance, or on a disposed root;
     *   a throwing factory propagates. Either way nothing is left behind.
     */
    fun create(
        key: K,
        factory: (K) -> S,
    ): S = createKeyed(key, factory)

    /**
     * The live store for [key], else [create] it through [factory] — the
     * documented by-id idiom. While another thread is constructing it, this
     * call parks until that construction ends (never spins); from inside the
     * factory constructing this very key it throws a cycle error instead.
     * Do not hold a store lock the factory needs while calling this.
     */
    fun getOrCreate(
        key: K,
        factory: (K) -> S,
    ): S = getOrCreateKeyed(key, factory)

    internal fun leafNameFor(key: K): String = keyCodec?.encode(key) ?: key.toString()

    override fun toString(): String = "KeyedBranch($name)"
}
