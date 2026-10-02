@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.internal.PrivilegedHooks
import com.vynatix.holdfast.testing.internal.TreeRecorder
import com.vynatix.holdfast.testing.matcher.StoreHandleGroup
import com.vynatix.holdfast.tree.LeafNode
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.StoreTree
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A tracked tree ([StoreTestScope.track] of a [StoreTree]): [root] — the
 * store the [tree] handle was obtained from — and every store of its
 * subtree — the ones attached when tracking began and every store that
 * joined since — is tracked as a [StoreHandle] with [captureMode], and a
 * tree middleware records every member transaction with its node into one
 * [timeline] of [TreeEvent]s, in observation order across the tree.
 *
 * Teardown (see [StoreTestScope]) removes the per-store recorders from the
 * members, then, unless `resetAtTeardown = false`, resets [root] and its
 * subtree as one frame — [root]'s own states included — so the next test
 * finds the initial values — with the tree middleware still installed, so a
 * vetoed reset fails the test naming the store and the veto (unless the
 * body already failed); a store disposed in the body is skipped — and only
 * then removes the tree middleware from [root]. Every middleware the test
 * installed stays. A store still held when
 * teardown runs (a `suspendAction`/`suspendAtomic` body parked in un-joined
 * work, a thread inside an action, a holder of the leaf's serializer) is
 * re-probed for a bounded time — about a second of real time, so a
 * transient holder such as an in-flight `suspendDerived` recompute on
 * `Store.scope` is waited out and the reset runs — and one still held after
 * that skips the whole tree's reset and FAILS the test, naming the tree and
 * the held leaves: the reset is one frame over every leaf, waiting longer
 * would hang the test thread (the only one that could resume a parked
 * body), and a silent skip would leak the body's values into the next
 * test. Join such work before the body ends, or opt out with
 * `resetAtTeardown = false` for a test that parks it deliberately. No store
 * is ever disposed.
 */
@ExperimentalStoreApi
class TreeHandle internal constructor(
    /** The handle this fixture tracks. */
    val tree: StoreTree,
    /** The store [tree] was obtained from: the receiver of the tracked subtree. */
    val root: Store<*>,
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
        // The tree middleware first: its ring refuses an install from inside
        // a member's transaction or an atomic frame, and a refused track(tree)
        // must leave nothing behind. Then hear of later joins, then adopt the
        // current members (the receiver first): a store attaching in between
        // is adopted twice, harmlessly (track is idempotent). A later step
        // that throws unwinds the earlier ones.
        tree.middlewares(recorder)
        listener =
            runCatching { PrivilegedHooks.addMembershipListener(root) { leaf -> leaf.store?.let(::adopt) } }
                .onFailure { runCatching { tree.removeMiddleware(recorder) } }
                .getOrThrow()
        runCatching { tree.stores().forEach(::adopt) }
            .onFailure {
                runCatching { listener.dispose() }
                runCatching { tree.removeMiddleware(recorder) }
            }.getOrThrow()
    }

    private fun adopt(store: Store<*>) {
        synchronized(membersLock) { if (members.none { it === store }) members.add(store) }
        scope.trackAny(store, captureMode)
    }

    private fun isMember(store: Store<*>): Boolean = synchronized(membersLock) { members.any { it === store } }

    /** Every recorded hook of every member, in observation order. */
    val timeline: List<TreeEvent> get() = recorder.snapshot()

    /**
     * The events of the subtree at [node]: a store's own and its
     * descendants', or every store's under a group or keyed branch — a store
     * disposed since still listed under the node it had.
     */
    fun events(node: StoreNode): List<TreeEvent> = timeline.filter { it.node.isUnder(node) }

    /**
     * The [StoreHandle] of [store], tracked with [captureMode] when it joined
     * the tree — the same handle `track(store)` answers.
     *
     * @throws IllegalArgumentException if [store] was never a member of this tree while tracked.
     */
    fun <S : Store<S>> handle(store: S): StoreHandle<S> {
        require(isMember(store)) {
            "${store::class.simpleName ?: "the store"} was never a member of tree '${tree.node.name}' " +
                "while it was tracked"
        }
        return scope.track(store, captureMode)
    }

    /**
     * The handles of the live stores of the subtree at [node], grouped for
     * the cross-store matchers (`shouldCommitTogether`).
     */
    fun group(node: StoreNode): StoreHandleGroup = StoreHandleGroup(leafHandles(node))

    private fun leafHandles(node: StoreNode): List<StoreHandle<*>> {
        val stores = tree.stores(node)
        return stores.map { scope.trackAny(it, captureMode) }
    }

    /**
     * The frame ids of every committed frame root of the subtree at [node], in
     * observation order, each once — the one string the fixture hands out,
     * since `Transaction.frameId` is one. A root that errored after its
     * `completed` hook (a frame vetoed on a later participant) is not counted.
     */
    fun committedFrameIds(node: StoreNode): List<String> = committedFrameIdsOf(events(node))

    /**
     * [committedFrameIds] of [leaf]'s store ALONE: a store node's subtree
     * also holds its descendants' events, which would let a parent that
     * never joined a frame look as if it committed with its children.
     */
    private fun ownCommittedFrameIds(leaf: LeafNode) = committedFrameIdsOf(timeline.filter { it.node === leaf })

    /** The committed frame ids of each live store of the subtree at [node]: what `shouldCommitTogether` intersects. */
    internal fun committedFrameIdsPerLeaf(node: StoreNode): List<Pair<LeafNode, List<String>>> =
        tree
            .stores(node)
            .mapNotNull { store -> tree.nodeOf(store) }
            .map { leaf -> leaf to ownCommittedFrameIds(leaf) }

    /**
     * Consume every pending `TransactionResult.Error` of every member handle
     * (`StoreHandle.consumeAllPendingErrors`).
     */
    fun consumeAllPendingErrors() {
        val all = synchronized(membersLock) { members.toList() }
        for (store in all) scope.handleFor(store)?.consumeAllPendingErrors()
    }

    override fun toString(): String = "TreeHandle(${tree.node.name})"
}

/** The distinct frame ids of the committed roots among [events], in observation order. */
private fun committedFrameIdsOf(events: List<TreeEvent>): List<String> {
    val errored = events.filter { it.phase == TreeEvent.Phase.Errored }.map { it.transaction }
    return events
        .filter { it.phase == TreeEvent.Phase.Completed && errored.none { txn -> txn === it.transaction } }
        .mapNotNull { it.transaction.frameId }
        .distinct()
}
