@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

/**
 * One store's place in the tree: under a [Branch] (named by a pin or the
 * store's class name) or under a [KeyedBranch] (named by its encoded [key]).
 *
 * A node outlives its store's membership: once the store is disposed — or
 * the root is — [store] answers `null`, but the node still identifies the
 * place in snapshots and test timelines captured while it was live.
 */
@ExperimentalStoreApi
class LeafNode internal constructor(
    override val root: Root,
    override val parent: StoreNode,
    override val name: String,
    override val nameOrigin: NameOrigin,
    /** The key this leaf was created for under a [KeyedBranch]; `null` under a [Branch]. */
    val key: Any?,
) : StoreNode {
    @kotlin.concurrent.Volatile
    internal var storeRef: Store<*>? = null

    /** The store at this leaf, or `null` once it has left the tree (disposed, or its root disposed). */
    val store: Store<*>? get() = storeRef

    /** The store's unique key while it was attached; identifies it in registries without holding it. */
    @kotlin.concurrent.Volatile
    internal var storeKey: Long = 0L

    override fun toString(): String = "LeafNode(${path()})"

    internal fun path(): String = "${root.name}/${pathUnderRoot()}"

    /** The names from the root's first child down to this leaf, joined by `/`: how a restore issue names it. */
    internal fun pathUnderRoot(): String {
        val names = ArrayList<String>()
        var current: StoreNode? = this
        while (current != null && current !is Root) {
            names.add(current.name)
            current = current.parent
        }
        return names.asReversed().joinToString("/")
    }
}
