@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.atomicfu.atomic

/** [LeafNode.attachPhase]: not registered under a parent in this attach epoch; a detach now is never announced. */
internal const val ATTACH_UNANNOUNCED = 0

/** [LeafNode.attachPhase]: registered; the attaching thread owes the ancestors `onAttached`, or is telling them. */
internal const val ATTACH_ANNOUNCING = 1

/** [LeafNode.attachPhase]: every ancestor's listeners heard `onAttached`; a detach now is claimed and delivered. */
internal const val ATTACH_ANNOUNCED = 2

/** [LeafNode.attachPhase]: detached while [ATTACH_ANNOUNCING]; the announcer delivers the detach. */
internal const val DETACH_DEFERRED = 3

/** [LeafNode.attachPhase]: a detach was claimed; its deliverer resets the phase to [ATTACH_UNANNOUNCED]. */
internal const val DETACHED = 4

/**
 * One store's place in a store tree, for the store's whole life: created
 * with the store's tree state, re-parented when the store is declared as a
 * child (`store { }`, `stores { }`, a keyed `create`), and reset to a
 * parentless, class-named node when its parent disposes (it becomes a
 * subtree root and keeps working).
 *
 * The node outlives its store: once the store is disposed [store] answers
 * `null`, but the node keeps the place it had (its [parent], [name] and
 * [key]), so snapshots and test timelines captured while it was live still
 * identify it.
 */
@ExperimentalStoreApi
class LeafNode internal constructor(
    attachment: TreeLeafAttachment?,
    parent: StoreNode?,
    name: String,
    nameOrigin: NameOrigin,
    key: Any?,
) : StoreNode {
    /**
     * The store's tree state; `null` for a node `decode` minted for a keyed
     * entry with no live store, and once the store's dispose finished
     * (the last step of `TreeLeafAttachment.onStoreDisposed`).
     */
    @kotlin.concurrent.Volatile
    internal var attachment: TreeLeafAttachment? = attachment

    @kotlin.concurrent.Volatile
    override var parent: StoreNode? = parent
        internal set

    @kotlin.concurrent.Volatile
    override var name: String = name
        internal set

    @kotlin.concurrent.Volatile
    override var nameOrigin: NameOrigin = nameOrigin
        internal set

    /** The key this leaf was created for under a [KeyedBranch]; `null` elsewhere. */
    @kotlin.concurrent.Volatile
    var key: Any? = key
        internal set

    /** The store at this leaf, or `null` once it disposed (and for a node `decode` minted). */
    val store: Store<*>? get() = attachment?.storeRef

    /**
     * The store's `lockOrderKey`, fixed at creation: identifies it in
     * captures without holding it. `0L` only for a node `decode` minted.
     */
    internal val storeKey: Long = attachment?.storeRef?.lockOrderKey ?: 0L

    /**
     * The name this node takes when it has no parent: its store's class
     * name minus `Store` (`"Store"` when the platform reports none, and for
     * a minted node). Computed once, so a reset never reads [store].
     */
    internal val defaultName: String = attachment?.storeRef?.let(::defaultNodeName) ?: DEFAULT_STORE_NAME

    /**
     * The attach-phase machine of one attach epoch (see `TreeMembership.kt`):
     * [ATTACH_UNANNOUNCED] → [ATTACH_ANNOUNCING] when the attacher registers
     * it under its parent → [ATTACH_ANNOUNCED] once every ancestor's
     * listeners heard `onAttached`; a detach claims [ATTACH_ANNOUNCED] →
     * [DETACHED] (its deliverer resets to [ATTACH_UNANNOUNCED]) or defers
     * from [ATTACH_ANNOUNCING] to [DETACH_DEFERRED] (the announcer delivers
     * and resets).
     */
    internal val attachPhase = atomic(ATTACH_UNANNOUNCED)

    /**
     * Claim this epoch's detach delivery: `true` when the caller delivers
     * `onDetached` now (and then resets [DETACHED] to [ATTACH_UNANNOUNCED]);
     * `false` when the detach is deferred to the announcer running, or there
     * is nothing to deliver (never announced, or claimed already).
     */
    internal fun claimDetach(): Boolean {
        var claimed: Boolean? = null
        while (claimed == null) {
            claimed =
                when (attachPhase.value) {
                    ATTACH_ANNOUNCED -> if (attachPhase.compareAndSet(ATTACH_ANNOUNCED, DETACHED)) true else null
                    ATTACH_ANNOUNCING ->
                        if (attachPhase.compareAndSet(ATTACH_ANNOUNCING, DETACH_DEFERRED)) false else null
                    else -> false
                }
        }
        return claimed
    }

    override fun toString(): String {
        val names = ArrayList<String>()
        var current: StoreNode? = this
        while (current != null) {
            names.add(current.name)
            current = current.parent
        }
        return "LeafNode(${names.asReversed().joinToString("/")})"
    }
}

/** [LeafNode.defaultName] when no class name is available. */
private const val DEFAULT_STORE_NAME = "Store"

/** The name [store]'s node takes with no parent: its class name minus `Store`, else `"Store"`. */
internal fun defaultNodeName(store: Store<*>): String = defaultLeafName(store::class.simpleName) ?: DEFAULT_STORE_NAME
