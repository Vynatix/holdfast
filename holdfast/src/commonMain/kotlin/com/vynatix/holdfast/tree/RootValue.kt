@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.CaptureStats
import com.vynatix.holdfast.DerivedStateNode
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NoWriteRegion
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.derivedStateOverStores
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// `Root.value` (issue #21 decisions T3/T4): the tree as a derived state that
// follows every leaf store as a whole (StoreEdges.kt — the #20 PR 15
// primitive built for this) and recomputes ONE consistent capture
// (`captureTree`) once per outermost entry: a two-store `atomic` frame, an
// action with nested actions, a `restore`/`reset` over a subtree each settle
// it exactly once, after every lock they took is released, on the derived
// machinery's settle scope. The node lives on the root's private host store
// (`RootHost`): its recompute is a top-level action there, so the host lock
// is taken only by a settle — never under a leaf's lock — and reads of
// `value` never take it (a read is the backing's value, or a fresh capture).
//
// The node is created on the first read or observation (T1: declaring a
// tree runs no leaf code); until then attach and detach events are only
// recorded, and the creation lists the leaves live at that moment. Each
// recompute reuses the previous tree's leaf captures whose cut stamp has not
// moved (`CutStamp`), so a commit on one leaf recaptures one leaf.

/** What a `Root.value` recompute did since the root was created; read through `Root.internal*` for tests. */
internal class RootValueCounters {
    val settles = atomic(0L)
    val captures = atomic(0L)
    val cutRetries = atomic(0L)
}

internal class RootValue(
    val root: Root,
) {
    val host = RootHost(root)
    val state = RootValueState(this)
    val counters = RootValueCounters()

    private val nodeRef = atomic<DerivedStateNode<TreeSnapshot>?>(null)

    /** The last tree a compute produced: what the next compute reuses unchanged leaves of. */
    private val lastTree = atomic<TreeSnapshot?>(null)

    /** A leaf joined or left since the last compute began: a read may find the backing stale. */
    private val structuralPending = atomic(false)

    /** Guards [leafStores] and, while the node is not yet created, [pendingEdges]. */
    private val edgeLock = SynchronizedObject()

    /** The store each live leaf holds: `onDetached` no longer has it. */
    private val leafStores = HashMap<LeafNode, Store<*>>()

    /** Attach (`true`) and detach events that arrived before the node existed; replayed once it does. */
    private var pendingEdges: MutableList<Pair<Store<*>, Boolean>>? = ArrayList()

    private val listener =
        object : LeafMembershipListener() {
            override fun onAttached(leaf: LeafNode) {
                val store = leaf.store ?: return
                structuralPending.value = true
                edge(store, added = true) { leafStores[leaf] = store }
            }

            override fun onDetached(leaf: LeafNode) {
                structuralPending.value = true
                var store: Store<*>? = null
                edge(null, added = false) { store = leafStores.remove(leaf) }
                store?.let { edge(it, added = false) {} }
            }
        }

    init {
        root.registry.addListener(listener)
    }

    /** Record or apply one membership change, [bookkeeping] first, under [edgeLock]. */
    private inline fun edge(
        store: Store<*>?,
        added: Boolean,
        bookkeeping: () -> Unit,
    ) {
        val node =
            synchronized(edgeLock) {
                bookkeeping()
                if (store == null) return
                val pending = pendingEdges
                if (pending != null) {
                    // Before the node exists: a leave cancels the leaf's pending
                    // join (and holds no reference to a store that is gone).
                    if (added) pending += store to true else pending.removeAll { it.first === store }
                    return
                }
                nodeRef.value
            }
        if (node != null && store != null) applyEdge(node, store, added)
    }

    private fun applyEdge(
        node: DerivedStateNode<TreeSnapshot>,
        store: Store<*>,
        added: Boolean,
    ) {
        if (added) {
            // Disposed between the attach and here: its edges are gone with it.
            if (!store.isDisposed) runCatching { node.addSourceStore(store) }
        } else {
            node.removeSourceStore(store)
        }
    }

    /** The derived node, created on first use: the first capture of the tree, outside every lock of ours. */
    fun node(): DerivedStateNode<TreeSnapshot> = nodeRef.value ?: seed()

    private fun seed(): DerivedStateNode<TreeSnapshot> {
        root.checkNotDisposed()
        val built = buildNode()
        return if (nodeRef.compareAndSet(null, built)) {
            val replay = synchronized(edgeLock) { pendingEdges.also { pendingEdges = null } }
            for ((store, added) in replay.orEmpty()) applyEdge(built, store, added)
            built
        } else {
            // Another thread seeded first; its node follows every leaf.
            built.dispose()
            checkNotNull(nodeRef.value)
        }
    }

    private fun buildNode(): DerivedStateNode<TreeSnapshot> {
        while (true) {
            val live = liveLeafStores()
            val attempt =
                runCatching {
                    host.derivedStateOverStores(VALUE_NAME, sourceStores = live) { compute() }
                }
            attempt.getOrNull()?.let { return it }
            // A listed leaf disposed while the node was built: list again.
            val failure = attempt.exceptionOrNull()
            if (failure !is IllegalStateException || live.none { it.isDisposed }) throw checkNotNull(failure)
        }
    }

    private fun liveLeafStores(): List<Store<*>> =
        root.registry
            .nodesPreorder(root)
            .mapNotNull { (it as? LeafNode)?.store }
            .filter { !it.isDisposed }

    /** The recompute: ONE consistent capture of the whole tree, reusing the last one's unmoved leaves. */
    private fun compute(): TreeSnapshot {
        structuralPending.value = false
        val previous = lastTree.value
        // Disposed meanwhile: the value freezes at the last tree rather than failing the recompute.
        if (root.isDisposed && previous != null) return previous
        val stats = CaptureStats()
        val tree = captureTree(root, root, SnapshotScope.All, previous, stats)
        lastTree.value = tree
        counters.settles.incrementAndGet()
        counters.captures.addAndGet(stats.captured)
        counters.cutRetries.addAndGet(stats.retries)
        return tree
    }

    /**
     * `Root.value.value`: the last settled tree — after settling a pending
     * structural change when no entry is open on this thread (the recompute
     * never waits for a busy host), or, when one is, a fresh capture that is
     * not committed, so a read inside an action or observer sees the leaf
     * that just joined or left without opening a transaction on the host.
     * Once the root is disposed it is the last settled tree whatever was
     * pending: nothing settles any more, and a fresh capture would throw.
     */
    fun read(): TreeSnapshot {
        val node = node()
        val stale = !root.isDisposed && structuralPending.value && NoWriteRegion.current() == null
        if (stale) {
            if (SettleScopes.current() == null) node.settleNow()
            if (structuralPending.value) return captureTree(root, root, SnapshotScope.All, lastTree.value, null)
        }
        return node.value
    }

    /** `Root.internalSettleNow`: run a queued recompute now, outside every entry, and answer the tree. */
    fun settleNow(): TreeSnapshot {
        val node = node()
        node.settleNow()
        return node.value
    }

    /**
     * `Root.dispose()`: stop following, drop the value's observers, dispose
     * the host, and hold no leaf store — the registry told no `onDetached`
     * for them, and one disposed later is never heard of here. The last
     * tree stays readable (its leaf captures keep what their reuse needs).
     */
    fun dispose() {
        val node = nodeRef.value
        node?.dispose()
        node?.backing?.shutdownSilently()
        host.dispose()
        synchronized(edgeLock) {
            leafStores.clear()
            pendingEdges = null
        }
    }
}

private const val VALUE_NAME = "value"
