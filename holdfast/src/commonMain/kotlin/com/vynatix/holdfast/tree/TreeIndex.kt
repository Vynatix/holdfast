@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

/**
 * The lookup tables one [TreeSnapshot] and every subtree of it share: which
 * capture sits at which node, which leaf capture holds which store (by the
 * store's `lockOrderKey`, so a disposed store's states still read), and
 * which stores were members of the root when the capture was taken.
 * Filled while the tree is built, read-only afterwards.
 *
 * @param memberKeys the `lockOrderKey` of every store that sat at a leaf of
 *   the WHOLE tree — not only the captured subtree — when the capture (or
 *   decode) was taken, copied under the registry lock in the same take as
 *   the subtree's shape (`TreeRegistry.listingOf`). Membership is decided
 *   then: a member outside the captured subtree reads `Absent` from
 *   `TreeSnapshot.entry` whether or not it has disposed since, where the
 *   registry alone would no longer know it. Keys only, never a store, so a
 *   capture pins nothing.
 */
internal class TreeIndex(
    val memberKeys: Set<Long>,
) {
    val byNode = HashMap<StoreNode, TreeSnapshot>()
    val byStoreKey = HashMap<Long, TreeSnapshot>()

    /**
     * Keyed leaves a decoded tree holds BODIES for with no live store at
     * decode time, per keyed branch (PR 21-4): create, then restore. A keyed
     * leaf text written without a body (an empty leaf capture) is never
     * pending: it holds nothing to restore.
     */
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

/**
 * What a capture lists under ONE take of the registry lock: the captured
 * subtree's [shape] and the whole tree's membership ([memberKeys], see
 * [TreeIndex.memberKeys]), so the two agree — a member the listing saw and
 * the cut then missed (disposed meanwhile) reads `Absent`, never throws.
 */
internal class TreeListing(
    val shape: TreeShape,
    val memberKeys: Set<Long>,
)
