@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

/**
 * The lookup tables one [TreeSnapshot] and every subtree of it share: which
 * capture sits at which node, and which leaf capture holds which store
 * (by the store's `lockOrderKey`, so a disposed store's states still read).
 * Filled while the tree is built, read-only afterwards.
 */
internal class TreeIndex {
    val byNode = HashMap<StoreNode, TreeSnapshot>()
    val byStoreKey = HashMap<Long, TreeSnapshot>()

    /** Keyed leaves a decoded tree holds bodies for with no live store at decode time, per keyed branch (PR 21-4). */
    val pendingKeys = HashMap<KeyedBranch<*, *>, Set<Any>>()

    fun register(snapshot: TreeSnapshot) {
        byNode[snapshot.node] = snapshot
        if (snapshot.storeKey != 0L) byStoreKey[snapshot.storeKey] = snapshot
    }

    /** Record [key] as pending under [branch]: a decoded body with no live store under its key at decode time. */
    fun addPendingKey(
        branch: KeyedBranch<*, *>,
        key: Any,
    ) {
        pendingKeys[branch] = (pendingKeys[branch] ?: emptySet()) + key
    }
}

/**
 * One moment's membership of a subtree, copied under the registry lock so
 * the capture can run outside it: the node, its live leaves with their
 * stores, and the shapes declared under it.
 */
internal class TreeShape(
    val node: StoreNode,
    val leaves: List<Pair<LeafNode, Store<*>>>,
    val children: List<TreeShape>,
) {
    fun stores(into: MutableList<Store<*>> = ArrayList()): MutableList<Store<*>> {
        for ((_, store) in leaves) into.add(store)
        for (child in children) child.stores(into)
        return into
    }
}
