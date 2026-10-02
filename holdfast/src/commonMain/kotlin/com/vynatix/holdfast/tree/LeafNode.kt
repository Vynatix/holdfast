@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.atomicfu.atomic

/** [LeafNode.attachPhase]: not indexed by the registry yet; a dispose now is never announced. */
internal const val ATTACH_UNANNOUNCED = 0

/** [LeafNode.attachPhase]: indexed; the declaring thread owes the listeners `onAttached`, or is telling them. */
internal const val ATTACH_ANNOUNCING = 1

/** [LeafNode.attachPhase]: every listener heard `onAttached`; a dispose now is announced by its own thread. */
internal const val ATTACH_ANNOUNCED = 2

/** [LeafNode.attachPhase]: the store disposed while [ATTACH_ANNOUNCING]; the announcer delivers or drops the detach. */
internal const val DETACH_DEFERRED = 3

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

    /**
     * A branch leaf's announcement to the membership listeners (a keyed
     * leaf's is ordered by its entry's construction lock instead):
     * [ATTACH_UNANNOUNCED] until the registry indexed it, [ATTACH_ANNOUNCING]
     * from then until the declaring thread has told every listener
     * `onAttached`, then [ATTACH_ANNOUNCED] — or [DETACH_DEFERRED] when the
     * store disposed meanwhile, so the announcer tells `onDetached` right
     * after (a listener hears Attached strictly before Detached) or, when
     * it had not begun, tells neither: a store gone before it was announced
     * is never announced at all.
     */
    internal val attachPhase = atomic(ATTACH_UNANNOUNCED)

    /**
     * The store at this branch leaf disposed: `true` when the caller tells
     * the listeners `onDetached` now; `false` when the detach is dropped
     * (the leaf was never announced) or deferred to the announcer running.
     */
    internal fun claimDetach(): Boolean {
        var claimed: Boolean? = null
        while (claimed == null) {
            val phase = attachPhase.value
            claimed =
                when {
                    phase == ATTACH_ANNOUNCED -> true
                    phase != ATTACH_ANNOUNCING -> false
                    attachPhase.compareAndSet(ATTACH_ANNOUNCING, DETACH_DEFERRED) -> false
                    else -> null
                }
        }
        return claimed
    }

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
