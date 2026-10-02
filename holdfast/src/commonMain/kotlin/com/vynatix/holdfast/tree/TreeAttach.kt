@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.platform.threadYield

// The attach of one or more stores under a parent node — a `store { }`
// child, a `group { }`'s members, a keyed store — once their
// declaration produced them (`TreeMaterialize.kt`, `KeyedConstruction.kt`).
// Phases, each a method below, in order:
//
// 3. `link`: under `treeStructureLock` (alone, no registry lock held), take
//    each store's parent edge by compare-and-set (the one-parent rule),
//    refuse a cycle, write the nodes' parent/name/origin/key, and capture
//    the owner's ancestor chain for the seqlock. Undone whole on any failure.
// 4. `publish`: inside a seqlock bump of that chain, under the owner's
//    registry lock, move every leaf UNANNOUNCED → ANNOUNCING and make the
//    entry live (the registry step). Irrevocable once it returns; a closed
//    registry undoes phase 3 and fails ("… disposed").
// 5. `syncRings`: each attached subtree's tree middleware, from its new ancestry.
// 6. `announce`: tell every ancestor's listeners, nearest first, outside every
//    lock, re-check that no ancestor closed meanwhile, then move ANNOUNCING →
//    ANNOUNCED — or deliver the detach a dispose deferred meanwhile
//    (`AttachAnnouncer.kt`).
//
// Phases 5 and 6 cannot fail the attach: a failure there is reported through
// the owner's `uncaughtObserverHandler`.

/** One store to attach: its tree state, and the name, origin and key its node takes. */
internal class AttachTarget(
    val attachment: TreeLeafAttachment,
    val name: String,
    val origin: NameOrigin,
    val key: Any?,
)

/**
 * The attach of [targets] under [parentNode] — [owner]'s node, a group of
 * [owner]'s, or a keyed branch of [owner]'s (with its [keyedEntry]) —
 * declared by [owner]'s property `declarationName` (the `store { }`
 * child's, the group's or the keyed branch's name), registered in
 * [registry], [owner]'s children.
 */
internal class ChildAttach(
    private val owner: Store<*>,
    private val registry: ChildRegistry,
    declarationName: String,
    private val parentNode: StoreNode,
    private val targets: List<AttachTarget>,
    private val keyedEntry: LeafEntry?,
) {
    private val leaves = targets.map { it.attachment.node }

    /** `Owner.property`, for messages. */
    private val label = "${owner.displayName}.$declarationName"

    /** `Owner/property`: the place a store attached here hangs, as the double-parent refusal names it. */
    private val place = "${owner.displayName}/$declarationName"

    /** The leaves [publish] registered: every target's, except a group member that disposed meanwhile. */
    private var attached: List<LeafNode> = emptyList()

    /** The attachments of the owner's ancestor chain (the owner's first), captured by [link]. */
    private var ancestorAttachments: List<TreeLeafAttachment> = emptyList()

    /** What [link] overwrote, for [unlinkLocked]. */
    private val linked = ArrayList<Linked>()

    private class Linked(
        val target: AttachTarget,
        val edge: ParentEdge,
        val name: String,
        val origin: NameOrigin,
    )

    /**
     * Phase 3. Under `treeStructureLock`: take each store's parent edge,
     * refuse a cycle, write the nodes' place, capture the owner's ancestors.
     *
     * @throws IllegalStateException if a store already has a parent, is
     *   disposed, or the attach would make a store its own ancestor; nothing
     *   is linked then.
     */
    fun link() =
        treeStructureLock.withLock {
            var done = false
            try {
                for (target in targets) linkOneLocked(target)
                ancestorAttachments = ancestorsOf(parentNode).mapNotNull { it.attachment }
                done = true
            } finally {
                if (!done) unlinkLocked(linked.toList())
            }
        }

    private fun linkOneLocked(target: AttachTarget) {
        val node = target.attachment.node
        val child = target.attachment.storeRef
        check(child != null && !child.isDisposed) { disposedMessage() }
        val edge = ParentEdge(parentNode, owner, registry, keyedEntry, place)
        if (!target.attachment.parentEdge.compareAndSet(null, edge)) {
            val existing = target.attachment.parentEdge.value
            error(
                "${child.displayName} already belongs to ${existing?.place}; it cannot also be declared under " +
                    "$place — a store has one parent",
            )
        }
        linked.add(Linked(target, edge, node.name, node.nameOrigin))
        check(!parentNode.isUnder(node)) {
            "$label would make ${child.displayName} its own ancestor: " +
                (parentNode.pathNodesFrom(node).map { it.name } + node.name).joinToString("/")
        }
        node.parent = parentNode
        node.name = target.name
        node.nameOrigin = target.origin
        node.key = target.key
    }

    private fun disposedMessage() = "$label: the child disposed while it was being attached"

    /**
     * Under `treeStructureLock`: undo every edge and field [link] wrote for
     * [undo]. A store whose own dispose already cleared the edge gets its
     * fields back too (a disposed store keeps no place it never took).
     */
    private fun unlinkLocked(undo: List<Linked>) {
        for (linkedOne in undo.asReversed()) {
            val attachment = linkedOne.target.attachment
            val node = attachment.node
            val ours = attachment.parentEdge.compareAndSet(linkedOne.edge, null)
            if (ours || (attachment.parentEdge.value == null && node.parent === parentNode)) {
                node.parent = null
                node.name = linkedOne.name
                node.nameOrigin = linkedOne.origin
                node.key = null
            }
        }
        linked.removeAll(undo.toSet())
    }

    /**
     * Phase 4. Register the attach — every leaf ANNOUNCING, then
     * [registryStep] makes the entry live over the leaves it is given —
     * under the owner's registry lock, inside a seqlock bump of the owner's
     * ancestors. While a leaf's previous detach is still being delivered
     * (its phase is not UNANNOUNCED yet), nothing is written and the
     * registration is retried. A store that disposed since [link] is never
     * registered (its own dispose sets `isDisposed` before it takes this
     * lock, so a store read live here finds its entry live when it detaches):
     * a group leaves it out; a single child fails the attach.
     *
     * @throws IllegalStateException if the owner's registry closed (its
     *   store disposed), or a single child disposed; [link] is undone first.
     */
    fun publish(registryStep: (List<LeafNode>) -> Unit) {
        while (true) {
            bumpWriters(ancestorAttachments)
            val outcome =
                try {
                    registry.lock.withLock { registerLocked(registryStep) }
                } finally {
                    endBumps(ancestorAttachments)
                }
            when (outcome) {
                Outcome.Done -> {
                    undoDead()
                    return
                }
                Outcome.Closed, Outcome.ChildDisposed -> {
                    treeStructureLock.withLock { unlinkLocked(linked.toList()) }
                    error(if (outcome == Outcome.Closed) "${owner.displayName} disposed" else disposedMessage())
                }
                Outcome.Busy -> threadYield()
            }
        }
    }

    private enum class Outcome { Done, Busy, Closed, ChildDisposed }

    private fun registerLocked(registryStep: (List<LeafNode>) -> Unit): Outcome {
        if (registry.disposed) return Outcome.Closed
        val live = leaves.filter { it.attachment?.storeRef?.isDisposed == false }
        return when {
            parentNode !is Branch && live.size != leaves.size -> Outcome.ChildDisposed
            live.any { it.attachPhase.value != ATTACH_UNANNOUNCED } -> Outcome.Busy
            else -> {
                registryStep(live)
                for (leaf in live) leaf.attachPhase.value = ATTACH_ANNOUNCING
                attached = live
                Outcome.Done
            }
        }
    }

    /** After a group's registration: undo the link of every member that disposed before it. */
    private fun undoDead() {
        val dead = linked.filter { it.target.attachment.node !in attached }
        if (dead.isNotEmpty()) treeStructureLock.withLock { unlinkLocked(dead) }
    }

    /**
     * Phase 5. Sync the tree middleware of every attached store and every
     * live store under it. Listed with the disposing stores: a store that
     * finished disposing since phase 4 lists as nothing (its own dispose
     * retired its ring) instead of failing the listing, and one mid-dispose
     * syncs to an empty ring — a benign race, never reported.
     */
    fun syncRings() {
        for (leaf in attached) {
            runCatching {
                for ((member, _) in listingOf(leaf, leaf, includeDisposing = true).shape.leaves()) {
                    syncTreeRing(member)
                }
            }.onFailure(::report)
        }
    }

    /**
     * Phase 6. Tell the owner's ancestors, nearest first, that each attached
     * store — and every live store already under it — joined their subtree
     * (`AttachAnnouncer`).
     */
    fun announce() {
        val announcer = AttachAnnouncer(ancestorsOf(parentNode), ::report)
        for (leaf in attached) runCatching { announcer.announce(leaf) }.onFailure(::report)
    }

    private fun report(failure: Throwable) {
        runCatching { owner.internalReportUncaughtFailure(failure) }
    }
}
