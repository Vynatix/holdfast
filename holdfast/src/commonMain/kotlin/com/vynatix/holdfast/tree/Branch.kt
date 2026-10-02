@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

/**
 * A group of a store's children: the stores a `val session by group {
 * listOf(SignInStore() named "sign-in", ProfileStore()) }` declaration
 * listed, each at its own [LeafNode] (named by its `named` pin, else its
 * class name minus `Store`). Created when the group is first materialized —
 * the lambda runs then, never at declaration — and fixed for its life: a
 * listed store that disposes leaves the group, the others stay. When the
 * declaring store disposes, the group's stores are released as subtree
 * roots (never disposed: compare [KeyedBranch]).
 */
@ExperimentalStoreApi
class Branch internal constructor(
    /** The node of the store that declared this group. */
    override val parent: LeafNode,
    override val name: String,
    override val nameOrigin: NameOrigin,
    /** The listed stores with their nodes, in listing order. */
    members: List<Pair<Store<*>, LeafNode>>,
    internal val owner: Store<*>,
    internal val registry: ChildRegistry,
) : StoreNode {
    /**
     * The listed stores, in listing order. Fixed at materialization; a
     * disposed one stays listed but leaves the tree.
     */
    val stores: List<Store<*>> = members.map { it.first }

    /** The listed stores' nodes, in listing order. */
    internal val leaves: List<LeafNode> = members.map { it.second }

    /**
     * The leaves still attached under this group: every leaf once the group
     * is live, each removed when its store disposes, all when the owner
     * disposes. Guarded by [registry]'s lock.
     */
    internal val live: MutableSet<LeafNode> = HashSet()

    /** The [LeafNode.name] of [store] under this group. `store` must be one of [stores]. */
    fun leafName(store: Store<*>): String = leafOf(store).name

    internal fun leafOf(store: Store<*>): LeafNode {
        val index = stores.indexOfFirst { it === store }
        require(index >= 0) {
            "${store::class.simpleName ?: "Store"} is not listed under group '$name' of '${parent.name}'"
        }
        return leaves[index]
    }

    override fun toString(): String = "Branch($name)"
}
