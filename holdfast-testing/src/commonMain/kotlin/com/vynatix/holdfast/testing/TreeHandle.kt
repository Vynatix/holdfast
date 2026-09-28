@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.internal.PrivilegedHooks
import com.vynatix.holdfast.testing.internal.TreeRecorder
import com.vynatix.holdfast.testing.matcher.StoreHandleGroup
import com.vynatix.holdfast.tree.LeafNode
import com.vynatix.holdfast.tree.Root
import com.vynatix.holdfast.tree.StoreNode
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A tracked tree ([StoreTestScope.trackTree]): every leaf of [root] — the
 * ones attached when tracking began and every keyed store created since —
 * is tracked as a [StoreHandle] with [captureMode], and a tree middleware
 * records every leaf transaction with its node into one [timeline] of
 * [TreeEvent]s, in observation order across the tree.
 *
 * Teardown (see [StoreTestScope]) removes that middleware from the root and
 * the per-store recorders from the leaves — every middleware the test
 * installed stays — then, unless `resetAtTeardown = false`, resets the tree
 * as one frame so the next test finds the initial values (a leaf disposed
 * in the body is skipped; a vetoed reset fails the test naming the leaf and
 * the veto, unless the body already failed). The root itself is never
 * disposed.
 */
@ExperimentalStoreApi
class TreeHandle internal constructor(
    val root: Root,
    val captureMode: Capture,
    private val scope: StoreTestScope,
    internal val resetAtTeardown: Boolean,
) {
    internal val recorder = TreeRecorder(captureMode)
    private val membersLock = SynchronizedObject()

    /** Every store that has been a member while tracked, by identity, in attach order. */
    private val members = mutableListOf<Store<*>>()

    internal val listener: Disposable

    init {
        // Hear of later joins first, then adopt the current members: a store
        // attaching in between is adopted twice, harmlessly (track is idempotent).
        listener = PrivilegedHooks.addMembershipListener(root) { leaf -> leaf.store?.let(::adopt) }
        root.children(root).forEach(::adopt)
        root.middlewares(recorder)
    }

    private fun adopt(store: Store<*>) {
        synchronized(membersLock) { if (members.none { it === store }) members.add(store) }
        scope.trackAny(store, captureMode)
    }

    private fun isMember(store: Store<*>): Boolean = synchronized(membersLock) { members.any { it === store } }

    /** Every recorded hook of every leaf, in observation order. */
    val timeline: List<TreeEvent> get() = recorder.snapshot()

    /**
     * The events of the subtree at [node]: a leaf's own, or every leaf's
     * under a branch, keyed branch or the root — a keyed store disposed
     * since still listed under its branch.
     */
    fun events(node: StoreNode): List<TreeEvent> = timeline.filter { it.node.within(node) }

    /**
     * The [StoreHandle] of [store], tracked with [captureMode] when it joined
     * the tree — the same handle `track(store)` answers.
     *
     * @throws IllegalArgumentException if [store] was never a member of this tree while tracked.
     */
    fun <S : Store<S>> handle(store: S): StoreHandle<S> {
        require(isMember(store)) {
            "${store::class.simpleName ?: "the store"} was never a member of root '${root.name}' while it was tracked"
        }
        return scope.track(store, captureMode)
    }

    /** The handles of the live leaves under [node], grouped for the cross-store matchers (`shouldCommitTogether`). */
    fun group(node: StoreNode): StoreHandleGroup = StoreHandleGroup(root.children(node).map { scope.trackAny(it, captureMode) })

    /**
     * The frame ids of every committed frame root under [node], in
     * observation order, each once — the one string the fixture hands out,
     * since `Transaction.frameId` is one. A root that errored after its
     * `completed` hook (a frame vetoed on a later participant) is not counted.
     */
    fun committedFrameIds(node: StoreNode): List<String> {
        val events = events(node)
        val errored = events.filter { it.phase == TreeEvent.Phase.Errored }.map { it.transaction }
        return events
            .filter { it.phase == TreeEvent.Phase.Completed && errored.none { txn -> txn === it.transaction } }
            .mapNotNull { it.transaction.frameId }
            .distinct()
    }

    /** The committed frame ids of each live leaf under [node]: what `shouldCommitTogether` intersects. */
    internal fun committedFrameIdsPerLeaf(node: StoreNode): List<Pair<LeafNode, List<String>>> =
        root
            .children(node)
            .mapNotNull { store -> root.nodeOf(store) }
            .map { leaf -> leaf to committedFrameIds(leaf) }

    /** Consume every pending `TransactionResult.Error` of every leaf handle (`StoreHandle.consumeAllPendingErrors`). */
    fun consumeAllPendingErrors() {
        val all = synchronized(membersLock) { members.toList() }
        for (store in all) scope.handleFor(store)?.consumeAllPendingErrors()
    }

    override fun toString(): String = "TreeHandle(${root.name})"
}

/** Whether this node is [node] or lies under it. */
private fun StoreNode.within(node: StoreNode): Boolean = this === node || isUnder(node)
