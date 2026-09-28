@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalDetach
import kotlinx.atomicfu.atomic
import kotlin.reflect.KClass

/**
 * The typed state tree (issue #21): a root names its branches through
 * delegated properties, keyed stores join through a factory bracket, and
 * everything is addressed by node and state identity — strings appear only
 * in `encode()` and `render()`.
 *
 * ```
 * object App : Root() {
 *     val settings by branch(SettingsStore(), ProfileStore())
 *     val session by branch(SessionStore()).named("session")
 *     val threads by keyed<String, ThreadStore>(under = session)
 * }
 * class ThreadStore(id: String) : Store<ThreadStore>(App.threads.at(id)) { … }
 *
 * val t = App.threads.create("t1", ::ThreadStore)   // live once this returns
 * val again: ThreadStore? = App[App.threads, "t1"]
 * ```
 *
 * A root is not a [Store] (decision U1): the leaves keep their own actions,
 * middleware and locks, and the tree only adds membership, consistent
 * captures and subtree operations over them. Declaring a branch attaches its
 * stores and runs no leaf code (it runs inside an `object`'s initializer);
 * `under` targets must be declared textually above their users, or the
 * forward reference fails inside object initialization. Everything here is
 * `@ExperimentalStoreApi`: every root subclass, keyed store class and call
 * site opts in (or the module does, with `-opt-in`).
 *
 * @param name the root's name in `encode()`/`render()`, else its class name.
 */
@ExperimentalStoreApi
// The tree DSL is intentionally broad; each member is one primitive; bodies live in helper files.
@Suppress("TooManyFunctions")
abstract class Root(
    name: String? = null,
) : StoreNode {
    final override val root: Root get() = this
    final override val parent: StoreNode? get() = null
    final override val name: String = name ?: (this::class.simpleName ?: "Root")
    final override val nameOrigin: NameOrigin = if (name != null) NameOrigin.Pinned else NameOrigin.ClassName

    internal val registry = TreeRegistry(this)

    private val disposedFlag = atomic(false)

    /** Whether [dispose] has been called; once `true`, every tree entrypoint throws ("… disposed"). */
    val isDisposed: Boolean get() = disposedFlag.value

    /**
     * Every node of the tree in pre-order — the root, then each declared
     * branch, its live leaves, and what is declared under it — as of this
     * call. Runs no leaf code.
     *
     * @throws IllegalStateException if the root is disposed.
     */
    val nodes: List<StoreNode>
        get() {
            checkNotDisposed()
            return registry.nodesPreorder(this)
        }

    /**
     * Declare a branch listing [stores], under [under] (a branch of this
     * root, declared textually above) or the root: `val settings by
     * branch(SettingsStore(), ProfileStore())`. Each store attaches to this
     * tree when the property binds — a store listed under two branches, or
     * in two roots, fails fast naming both — and is named by its class minus
     * `Store` unless pinned ([BranchDeclaration.named]). Runs no leaf code.
     */
    protected fun branch(
        vararg stores: Store<*>,
        under: Branch? = null,
    ): BranchDeclaration = BranchDeclaration(this, stores.toList(), under)

    /**
     * Declare a keyed branch of [S] stores by [K]: `val threads by
     * keyed<String, ThreadStore>()`. Stores are created per key through
     * [KeyedBranch.create]/[KeyedBranch.getOrCreate] and named by their key
     * through [keyCodec] — defaulted for `String` keys; without one the
     * branch is never encoded.
     */
    protected inline fun <reified K : Any, reified S : Store<S>> keyed(
        under: Branch? = null,
        keyCodec: StateCodec<K>? = null,
    ): KeyedDeclaration<K, S> = keyedDeclaration(K::class, S::class, under, keyCodec)

    @PublishedApi
    internal fun <K : Any, S : Store<S>> keyedDeclaration(
        keyClass: KClass<K>,
        storeClass: KClass<S>,
        under: Branch?,
        keyCodec: StateCodec<K>?,
    ): KeyedDeclaration<K, S> {
        @Suppress("UNCHECKED_CAST")
        val codec = keyCodec ?: if (keyClass == String::class) StringKeyCodec as StateCodec<K> else null
        return KeyedDeclaration(this, keyClass, storeClass, under, codec)
    }

    /**
     * The live stores in the subtree at [node], in tree order: a branch's
     * listed stores, then those declared under it, a keyed branch's in
     * creation order. Runs no leaf code.
     *
     * @throws IllegalStateException if the root is disposed.
     * @throws IllegalArgumentException if [node] belongs to another root.
     */
    fun children(node: StoreNode): List<Store<*>> {
        checkNotDisposed()
        requireOwn(node)
        return registry.nodesPreorder(node).mapNotNull { (it as? LeafNode)?.store }
    }

    /** The live [branch] store for [key], or `null` when none has been created or it was disposed. */
    operator fun <K : Any, S : Store<S>> get(
        branch: KeyedBranch<K, S>,
        key: K,
    ): S? {
        checkNotDisposed()
        requireOwn(branch)
        @Suppress("UNCHECKED_CAST")
        return registry.liveStore(branch, key) as S?
    }

    /** The live stores of [branch] by key, in creation order. A copy. */
    fun <K : Any, S : Store<S>> entries(branch: KeyedBranch<K, S>): Map<K, S> {
        checkNotDisposed()
        requireOwn(branch)
        val out = LinkedHashMap<K, S>()
        for ((key, store) in registry.liveEntries(branch)) {
            @Suppress("UNCHECKED_CAST")
            out[key as K] = store as S
        }
        return out
    }

    /** The leaf [store] sits at in this tree, or `null` when it is not (or no longer) a member. */
    fun nodeOf(store: Store<*>): LeafNode? {
        checkNotDisposed()
        return registry.leafOf(store)
    }

    /**
     * Capture the subtree at [node] — the whole tree by default — as ONE
     * consistent cut across its live leaves, in [scope] (see [TreeSnapshot]).
     * Never-read declared states the scope captures are materialized first
     * (their initializers run); no leaf's transaction lock is taken, no writer
     * is blocked, and on a committing thread the capture reads committed
     * values. A leaf disposed while the capture runs is left out; a keyed
     * store still inside its factory is never included. Under
     * `SnapshotScope.UserAuthored`, leaves and branches with nothing captured
     * are pruned.
     *
     * @throws IllegalStateException if the root is disposed, or as
     *   `Store.snapshot()` does (a throwing or cyclic initializer).
     * @throws IllegalArgumentException if [node] belongs to another root.
     */
    fun snapshot(
        node: StoreNode = this,
        scope: SnapshotScope = SnapshotScope.All,
    ): TreeSnapshot = captureTree(this, node, scope)

    /**
     * Detach every leaf and drop every listener. Disposes no store: the
     * leaves keep working on their own, and disposing one later no longer
     * reaches this root. Idempotent; never blocks on a leaf's transaction
     * lock.
     */
    fun dispose() {
        if (!disposedFlag.compareAndSet(expect = false, update = true)) return
        val leaves = registry.close()
        for (leaf in leaves) {
            val store = leaf.storeRef ?: continue
            store.internalDetach(treeMembershipKey)
            leaf.storeRef = null
        }
    }

    internal fun checkNotDisposed() {
        if (disposedFlag.value) error("root '$name' disposed")
    }

    internal fun requireOwn(node: StoreNode) {
        require(node.root === this) { "node '${node.name}' belongs to root '${node.root.name}', not root '$name'" }
    }

    /** A keyed store's factory returned and verified: tell the listeners, then promote it. */
    internal fun promoteLeaf(
        entry: LeafEntry,
        store: Store<*>,
    ) {
        entry.attachedNotified = true
        notifyAttached(entry.leaf)
        registry.promote(entry, store)
    }

    /** A branch store registered under the lock; tell the listeners after release. */
    internal fun onLeafAttached(leaf: LeafNode) {
        if (leaf.storeRef != null) notifyAttached(leaf)
    }

    /** [TreeLeafAttachment.onStoreDisposed]: the tree drops the leaf, then tells the listeners once. */
    internal fun leafDisposed(
        leaf: LeafNode,
        entry: LeafEntry?,
    ) {
        if (!registry.detachLeaf(leaf)) return
        // Wait out the attach fanout of a keyed entry promoted a moment ago,
        // so a listener hears Attached strictly before Detached.
        entry?.constructionLock?.withLock { }
        onLeafDetached(leaf)
    }

    internal fun onLeafDetached(leaf: LeafNode) {
        for (listener in registry.listeners) listener.onDetached(leaf)
    }

    private fun notifyAttached(leaf: LeafNode) {
        for (listener in registry.listeners) listener.onAttached(leaf)
    }

    override fun toString(): String = name
}
