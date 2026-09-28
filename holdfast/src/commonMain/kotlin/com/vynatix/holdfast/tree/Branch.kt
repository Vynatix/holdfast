@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

/**
 * A branch of a [Root]: the stores a `val x by branch(a, b)` declaration
 * listed, each at its own [LeafNode], plus whatever was declared `under` it.
 * Named by its property unless pinned (`branch(...).named("session")`).
 */
@ExperimentalStoreApi
class Branch internal constructor(
    override val root: Root,
    override val parent: StoreNode,
    override val name: String,
    override val nameOrigin: NameOrigin,
    /** The listed stores, in listing order. Fixed at declaration; a disposed one stays listed but leaves the tree. */
    val stores: List<Store<*>>,
) : StoreNode {
    internal lateinit var leaves: List<LeafNode>

    /** The [LeafNode.name] of [store] under this branch. `store` must be one of [stores]. */
    fun leafName(store: Store<*>): String = leafOf(store).name

    internal fun leafOf(store: Store<*>): LeafNode =
        leaves.firstOrNull { it.storeKey == store.lockOrderKey }
            ?: throw IllegalArgumentException(
                "${store::class.simpleName ?: "Store"} is not listed under branch '$name' of root '${root.name}'.",
            )

    override fun toString(): String = "Branch($name)"
}
