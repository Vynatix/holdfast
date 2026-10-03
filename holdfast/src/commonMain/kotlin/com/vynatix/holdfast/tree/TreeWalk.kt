@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.CaptureStats
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.platform.threadYield

// Cross-registry walks of a store tree. Each store keeps its own children
// (`ChildRegistry`), so a listing of a subtree takes one registry lock at a
// time — a parent's, released, then each child's — and is validated by a
// seqlock on the walk's receiver: every listing-visible structural change
// (an attach's registration, a dispose's detach and close) bumps the
// `structuralWriters` of the mutated store's attachment and of every
// ancestor's, captured under `treeStructureLock` when the change began, then
// bumps their `structuralGeneration` and drops the writer count again. A
// listing that saw a writer, or whose generation moved, walks again
// (`threadYield()` between attempts, as `captureConsistent` does). No user
// code, listener or listing runs inside a bump window — it holds only the
// registry lock being mutated — so a reader never waits on user code.
//
// Window (documented, not closed): a store whose attach is between its
// structure link (attach phase 3) and its registration (phase 4) is already
// under its new parent over the live `parent` links but not yet listed.
//
// Walks reach a store's tree state through `LeafNode.attachment` only —
// never through the store's attachment slot, which its `dispose()` closes
// first, before the tree hears of it.

/** Open a structural write on every one of [targets] (see the file comment). */
internal fun bumpWriters(targets: List<TreeLeafAttachment>) {
    for (attachment in targets) attachment.structuralWriters.incrementAndGet()
}

/**
 * Close the write [bumpWriters] opened on the same [targets]: generation
 * first, then writers, so a reader that sees no writer also sees the new
 * generation. Call it in a `finally`.
 */
internal fun endBumps(targets: List<TreeLeafAttachment>) {
    for (attachment in targets) {
        attachment.structuralGeneration.incrementAndGet()
        attachment.structuralWriters.decrementAndGet()
    }
}

/**
 * One moment's subtree, copied one registry lock at a time so a capture can
 * run outside every lock: [node], its [name] as listed (read under the
 * registry lock of the store that lists it — the walk's top under its own),
 * its [store] (a [LeafNode]'s; `null` for a [Branch]/[KeyedBranch], and for
 * a top node whose store is gone) and its [children]: a store node's
 * declared children in declaration order (a `store { }` child's leaf, a live
 * group's [Branch], a [KeyedBranch]); a group's live members in listing
 * order; a keyed branch's live entries in creation order.
 */
internal class TreeShape(
    val node: StoreNode,
    val name: String,
    val store: Store<*>?,
    val children: List<TreeShape>,
) {
    /** Every store in this shape, in pre-order. */
    fun stores(): List<Store<*>> = leaves().map { it.second }

    /** Every store node with its store, in pre-order (this shape's own first, when it has a store). */
    fun leaves(): List<Pair<LeafNode, Store<*>>> {
        val out = ArrayList<Pair<LeafNode, Store<*>>>()
        collect(out)
        return out
    }

    /** Every node in this shape, in pre-order. */
    fun nodes(): List<StoreNode> {
        val out = ArrayList<StoreNode>()

        fun walk(shape: TreeShape) {
            out.add(shape.node)
            shape.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    private fun collect(into: MutableList<Pair<LeafNode, Store<*>>>) {
        val leaf = node as? LeafNode
        if (leaf != null && store != null) into.add(leaf to store)
        for (child in children) child.collect(into)
    }
}

/**
 * What one seqlock-validated listing saw: the [shape] of the listed node's
 * subtree; [memberKeys], the `lockOrderKey` of the receiver and of every
 * live store anywhere under the receiver (not only under the listed node);
 * and [chain], the nodes from the receiver down to the listed node over the
 * live links, both included, each with its name as read in the same
 * window — all as of one structural generation.
 */
internal class TreeListing(
    val shape: TreeShape,
    val memberKeys: Set<Long>,
    val chain: List<Pair<StoreNode, String>>,
)

/**
 * List the subtree at [node] — [ownerNode] or a node under it — as of one
 * structural generation of [ownerNode]'s store: walk top-down, one registry
 * lock at a time, and walk again while a structural write on the receiver's
 * subtree is open or has completed meanwhile ([stats] counts the retries).
 * The top [node] is always listed (with no store when its store is gone, or
 * disposing and not [includeDisposing]); below it, a store whose tree state
 * is gone, or whose store reference was dropped by its dispose, is skipped
 * with its subtree, and a store that is disposed but still holds its
 * reference is skipped unless [includeDisposing] (the dispose walk, which
 * must reach the children of a child mid-dispose).
 *
 * @throws IllegalStateException if [ownerNode]'s store finished disposing
 *   (with [includeDisposing], an empty listing of [node] instead).
 * @throws IllegalArgumentException if [node] is not [ownerNode] or under it.
 */
internal fun listingOf(
    ownerNode: LeafNode,
    node: StoreNode,
    includeDisposing: Boolean = false,
    stats: CaptureStats? = null,
): TreeListing {
    val ownerAttachment = ownerNode.attachment
    if (ownerAttachment == null) {
        check(includeDisposing) { "${ownerNode.name}'s store disposed" }
        return TreeListing(TreeShape(node, node.name, null, emptyList()), emptySet(), emptyList())
    }
    while (true) {
        val generation = ownerAttachment.structuralGeneration.value
        if (ownerAttachment.structuralWriters.value == 0) {
            val chain = node.pathNodesFrom(ownerNode).map { it to it.name }
            val walker = TreeWalker(includeDisposing)
            val whole = walker.top(ownerNode)
            val shape = if (node === ownerNode) whole else walker.shapes[node] ?: walker.top(node)
            val settled =
                ownerAttachment.structuralWriters.value == 0 &&
                    ownerAttachment.structuralGeneration.value == generation
            if (settled) return TreeListing(shape, walker.memberKeys + ownerNode.storeKey, chain)
            stats?.let { it.retries++ }
        }
        threadYield()
    }
}

/** Every node of the subtree at [node] in pre-order, as of one listing. */
internal fun nodesPreorder(
    ownerNode: LeafNode,
    node: StoreNode,
): List<StoreNode> = listingOf(ownerNode, node).shape.nodes()

/** Every live store node of the subtree at [node] with its store, in tree order, as of one listing. */
internal fun liveLeavesUnder(
    ownerNode: LeafNode,
    node: StoreNode,
): List<Pair<LeafNode, Store<*>>> = listingOf(ownerNode, node).shape.leaves()

/** One child a parent's registry listed under its lock, with the names read there. */
private sealed interface ListedChild {
    class Leaf(
        val leaf: LeafNode,
        val name: String,
    ) : ListedChild

    class Group(
        val node: StoreNode,
        val members: List<Pair<LeafNode, String>>,
    ) : ListedChild
}

/** One walk of [listingOf]: copies under one registry lock at a time, recurses outside it. */
private class TreeWalker(
    private val includeDisposing: Boolean,
) {
    val memberKeys = HashSet<Long>()
    val shapes = HashMap<StoreNode, TreeShape>()

    /** The shape of a walk's top [node]: its own name read under its own registry lock, else as is. */
    fun top(node: StoreNode): TreeShape =
        when (node) {
            is LeafNode -> storeShape(node, name = null) ?: TreeShape(node, node.name, null, emptyList())
            is Branch -> groupShape(node, node.registry.lock.withLock { membersLocked(node) })
            is KeyedBranch<*, *> -> groupShape(node, node.registry.lock.withLock { entriesLocked(node) })
        }

    /** The walk's view of [leaf]'s store, or `null` when the walk skips it. */
    private fun walkable(leaf: LeafNode): Pair<TreeLeafAttachment, Store<*>>? {
        val attachment = leaf.attachment
        val store = attachment?.storeRef
        val skip = attachment == null || store == null || (!includeDisposing && store.isDisposed)
        return if (skip) null else checkNotNull(attachment) to checkNotNull(store)
    }

    private fun storeShape(
        leaf: LeafNode,
        name: String?,
    ): TreeShape? {
        val (attachment, store) = walkable(leaf) ?: return null
        memberKeys += store.lockOrderKey
        val registry = attachment.registry
        val (ownName, listed) = registry.lock.withLock { (name ?: leaf.name) to childrenLocked(registry) }
        val children =
            listed.map { child ->
                when (child) {
                    is ListedChild.Leaf -> storeShape(child.leaf, child.name)
                    is ListedChild.Group -> groupShape(child.node, child.members)
                }
            }
        return TreeShape(leaf, ownName, store, children.filterNotNull()).also { shapes[leaf] = it }
    }

    private fun groupShape(
        node: StoreNode,
        members: List<Pair<LeafNode, String>>,
    ): TreeShape {
        val children = members.mapNotNull { (leaf, name) -> storeShape(leaf, name) }
        return TreeShape(node, node.name, null, children).also { shapes[node] = it }
    }

    /** Under [registry]'s lock: its live children, in declaration order, with their names. */
    private fun childrenLocked(registry: ChildRegistry): List<ListedChild> =
        registry.declaredEntriesLocked().mapNotNull { entry ->
            when (val node = entry.liveNode) {
                is LeafNode -> ListedChild.Leaf(node, node.name)
                is Branch -> ListedChild.Group(node, membersLocked(node))
                is KeyedBranch<*, *> -> ListedChild.Group(node, entriesLocked(node))
                null -> null
            }
        }

    /** Under the group's registry lock: its live members, in listing order. */
    private fun membersLocked(branch: Branch): List<Pair<LeafNode, String>> =
        branch.leaves.filter { it in branch.live }.map { it to it.name }

    /** Under the branch's registry lock: its live entries' leaves, in creation order. */
    private fun entriesLocked(branch: KeyedBranch<*, *>): List<Pair<LeafNode, String>> =
        branch.registry.liveEntriesLocked(branch).mapNotNull { (entry, _) -> entry.leaf?.let { it to it.name } }
}

/**
 * Tell every store in [chain] (nearest first; a store whose tree state is
 * gone is skipped) that each of [leaves] joined its subtree, leaf by leaf,
 * outside every registry lock. A throwing listener is handed to [failed]
 * and the others are still told.
 */
internal fun announceAttached(
    chain: List<LeafNode>,
    leaves: List<LeafNode>,
    failed: (Throwable) -> Unit,
) = deliver(chain, leaves, failed) { listener, leaf -> listener.onAttached(leaf) }

/** [announceAttached] for `onDetached`. */
internal fun announceDetached(
    chain: List<LeafNode>,
    leaves: List<LeafNode>,
    failed: (Throwable) -> Unit,
) = deliver(chain, leaves, failed) { listener, leaf -> listener.onDetached(leaf) }

private inline fun deliver(
    chain: List<LeafNode>,
    leaves: List<LeafNode>,
    failed: (Throwable) -> Unit,
    call: (LeafMembershipListener, LeafNode) -> Unit,
) {
    for (leaf in leaves) {
        for (ancestor in chain) {
            val listeners = ancestor.attachment?.registry?.listeners ?: continue
            for (listener in listeners) runCatching { call(listener, leaf) }.onFailure(failed)
        }
    }
}
