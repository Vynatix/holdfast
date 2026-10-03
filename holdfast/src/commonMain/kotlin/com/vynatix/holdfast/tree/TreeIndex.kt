@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

/**
 * The lookup tables one [TreeSnapshot] and every subtree of it share: which
 * capture sits at which node, which leaf capture holds which store (by the
 * store's `lockOrderKey`, so a disposed store's states still read), which
 * stores were in the receiver's subtree when the capture was taken, and the
 * captured STRUCTURE itself — each node's parent and name as captured.
 * Filled while the tree is built, read-only afterwards.
 *
 * The structure is frozen because the live one moves: when a store
 * disposes, its children become parentless, class-named subtree roots. A
 * kept snapshot reads, encodes and reports the structure as captured,
 * whatever disposed since — navigation goes through [parentOf] and the
 * frozen names, never the live `parent`/`name` links.
 *
 * Membership (GUIDE §17.5): a state of a store that was anywhere under the
 * receiver ([ownerNode]) when the capture was taken reads `Absent` from any
 * sub-node capture or carved subtree; a state of the receiver's ancestor or
 * of an unrelated store throws.
 *
 * @param memberKeys the `lockOrderKey` of the receiver and of every store
 *   live anywhere under it — not only under the captured node — when the
 *   capture (or decode) was taken, listed in the same validated walk as the
 *   captured shape. Keys only, never a store, so a capture pins nothing.
 * @param ownerNode the receiver's node: the store whose `tree` took it.
 */
internal class TreeIndex(
    val memberKeys: Set<Long>,
    val ownerNode: LeafNode,
) {
    /**
     * The receiver's identity as of the capture or decode — its
     * `TreeIdentified.treeId`, else its node name — written as the
     * envelope's `receiver`.
     */
    val receiver: String = receiverIdentity(ownerNode.store, ownerNode)

    val byNode = HashMap<StoreNode, TreeSnapshot>()
    val byStoreKey = HashMap<Long, TreeSnapshot>()

    /** Each captured node's parent as captured, from the receiver down to every captured node. */
    val parentOf = HashMap<StoreNode, StoreNode>()

    /** The names of the nodes between the receiver and the captured node, which hold no capture of their own. */
    val chainNames = HashMap<StoreNode, String>()

    /** Each captured node's name origin as captured. */
    val origins = HashMap<StoreNode, NameOrigin>()

    /**
     * Keyed leaves a decoded tree holds BODIES for with no live store at
     * decode time, per keyed branch: create, then restore. A keyed leaf text
     * written without a body (an empty leaf capture) is never pending: it
     * holds nothing to restore.
     */
    val pendingKeys = HashMap<KeyedBranch<*, *>, Set<Any>>()

    fun register(snapshot: TreeSnapshot) {
        byNode[snapshot.node] = snapshot
        if (snapshot.storeKey != 0L) byStoreKey[snapshot.storeKey] = snapshot
    }

    /**
     * Record [chain] — the nodes from the receiver down to the captured node,
     * with their names — as the frozen path to the capture.
     */
    fun recordChain(chain: List<Pair<StoreNode, String>>) {
        for (i in 1 until chain.size) {
            val (node, name) = chain[i]
            parentOf[node] = chain[i - 1].first
            chainNames[node] = name
        }
    }

    /** Record [key] as pending under [branch]: a decoded body with no live store under its key at decode time. */
    fun addPendingKey(
        branch: KeyedBranch<*, *>,
        key: Any,
    ) {
        pendingKeys[branch] = (pendingKeys[branch] ?: emptySet()) + key
    }
}

/** [node]'s name as captured (the receiver's: its name now; it is never written). */
internal fun TreeIndex.nameOf(node: StoreNode): String =
    byNode[node]?.name
        ?: chainNames[node]
        ?: ownerNode.name.takeIf { node === ownerNode }
        ?: error("node '${node.name}' is not part of this capture")

/** [node]'s name origin as captured, else as it is now. */
internal fun TreeIndex.originOf(node: StoreNode): NameOrigin = origins[node] ?: node.nameOrigin

/** Whether [node] is [top] or lies under it in the captured structure. */
internal fun TreeIndex.isUnderCaptured(
    node: StoreNode,
    top: StoreNode,
): Boolean {
    var current: StoreNode? = node
    while (current != null) {
        if (current === top) return true
        current = parentOf[current]
    }
    return false
}

/**
 * The captured names from the receiver's child down to [node]; empty for
 * the receiver itself.
 *
 * @throws IllegalArgumentException if [node] is not under the receiver in this capture.
 */
internal fun TreeIndex.pathOf(node: StoreNode): List<String> {
    val names = ArrayList<String>()
    var current: StoreNode? = node
    while (current != null && current !== ownerNode) {
        names.add(nameOf(current))
        current = parentOf[current]
    }
    require(current === ownerNode) { "node '${node.name}' is not under '${ownerNode.name}' in this capture" }
    return names.asReversed()
}
