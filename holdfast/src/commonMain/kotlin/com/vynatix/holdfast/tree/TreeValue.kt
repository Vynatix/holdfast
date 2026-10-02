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

// A `tree` handle's value (issue #21): the receiver's subtree as a derived
// state that follows the receiver and every live store under it as a whole
// (StoreEdges.kt) and recomputes ONE consistent capture (`captureTree`) once
// per outermost entry. The node lives on the handle's private host store
// (`TreeValueHost`): its recompute is a top-level action there, so the host
// lock is taken only by a settle — never under a lock of a member's — and
// reads of the value never take it (a read is the backing's value, or a
// fresh capture).
//
// The node is created on the first read or observation; until then attach
// and detach events (the receiver's membership listener hears every store
// joining or leaving its subtree, at any depth) are only recorded, and the
// creation lists the stores live at that moment. Each recompute reuses the
// previous tree's captures whose cut stamp has not moved (`CutStamp`), so a
// commit on one store recaptures one store — once per materialized value
// above it (GUIDE §17.8).

/** What a tree value's recompute did since its handle was created; read through `StoreTree.internal*`. */
internal class TreeValueCounters {
    val settles = atomic(0L)
    val captures = atomic(0L)
    val cutRetries = atomic(0L)
}

/** The value of the `tree` handle of [owner], whose node is [ownerNode] and whose children are [registry]. */
internal class TreeValue(
    private val owner: Store<*>,
    private val ownerNode: LeafNode,
    registry: ChildRegistry,
    val host: TreeValueHost,
) {
    val counters = TreeValueCounters()

    private val nodeRef = atomic<DerivedStateNode<TreeSnapshot>?>(null)

    /** The last tree a compute produced: what the next compute reuses unchanged captures of. */
    private val lastTree = atomic<TreeSnapshot?>(null)

    /** A store joined or left since the last compute began: a read may find the backing stale. */
    private val structuralPending = atomic(false)

    /** Guards [leafStores], [pendingEdges] and [disposed]. */
    private val edgeLock = SynchronizedObject()

    /**
     * The store each leaf announced to this value holds: `onDetached` may no
     * longer have it. A leaf that joined before this value's listener was
     * registered is not here; its detach falls back to the leaf's store —
     * still set for a store released by an ancestor's dispose (a live store
     * that left this subtree, whose edge nothing else drops), `null` only for
     * a store that disposed itself, whose edges its own dispose drops.
     */
    private val leafStores = HashMap<LeafNode, Store<*>>()

    /** Attach (`true`) and detach events that arrived before the node existed; replayed once it does. */
    private var pendingEdges: MutableList<Pair<Store<*>, Boolean>>? = ArrayList()

    /** Set by [disposeSync]: a membership event delivered after it is refused, and nothing is held. */
    private var disposed = false

    private val listener =
        object : LeafMembershipListener() {
            override fun onAttached(leaf: LeafNode) {
                val store = leaf.store ?: return
                structuralPending.value = true
                // Re-checked under the edge lock: a store whose own dispose dropped
                // it (before its detach delivery, which takes this lock too) is
                // never followed, so it is never retained.
                edge({ store }, added = true) {
                    (leaf.store != null).also { live -> if (live) leafStores[leaf] = store }
                }
            }

            override fun onDetached(leaf: LeafNode) {
                structuralPending.value = true
                var store: Store<*>? = null
                edge({ store }, added = false) {
                    store = leafStores.remove(leaf) ?: leaf.store
                    store != null
                }
            }
        }

    init {
        registry.addListener(listener)
    }

    /**
     * Record or apply one membership change, [bookkeeping] first, under
     * [edgeLock]; nothing more when [bookkeeping] answers `false`. The
     * bookkeeping and the node's edge change are ONE step under the lock,
     * so an attach and a detach of one leaf racing on two threads leave the
     * edge exactly as the later of the two says; the recompute the change
     * owes runs after the lock is released (it may run inline, and user
     * observers with it).
     */
    private inline fun edge(
        store: () -> Store<*>?,
        added: Boolean,
        bookkeeping: () -> Boolean,
    ) {
        val changed =
            synchronized(edgeLock) {
                // Disposed: nothing is recorded, nothing is followed, from now on.
                val target = if (!disposed && bookkeeping()) store() else null
                target?.let { nodeOrRecord(it, added)?.takeIf { node -> applyEdgeLocked(node, it, added) } }
            }
        changed?.recomputeForSourceStores()
    }

    /** Under [edgeLock]: the node to apply the edge to, or `null` once it was recorded for the node's replay. */
    private fun nodeOrRecord(
        store: Store<*>,
        added: Boolean,
    ): DerivedStateNode<TreeSnapshot>? {
        val pending = pendingEdges ?: return nodeRef.value
        // Before the node exists: a leave cancels the store's pending join and
        // is itself recorded, because the seed's listing may already have
        // listed the store (it joined before this value's listener existed,
        // or before the leave): the replay removes that edge.
        if (!added) pending.removeAll { it.first === store }
        pending += store to added
        return null
    }

    /** Under [edgeLock]: change the edge without its recompute; whether it changed. */
    private fun applyEdgeLocked(
        node: DerivedStateNode<TreeSnapshot>,
        store: Store<*>,
        added: Boolean,
    ): Boolean =
        if (added) {
            // Disposed between the attach and here: its edges are gone with it.
            !store.isDisposed && runCatching { node.addSourceStoreQuietly(store) }.getOrDefault(false)
        } else {
            node.removeSourceStoreQuietly(store)
        }

    /** The derived node, created on first use: the first capture of the subtree, outside every lock of ours. */
    fun node(): DerivedStateNode<TreeSnapshot> = nodeRef.value ?: seed()

    private fun seed(): DerivedStateNode<TreeSnapshot> {
        owner.checkNotDisposed()
        materializeSubtree(ownerNode)
        val built = buildNode()
        if (!nodeRef.compareAndSet(null, built)) {
            // Another thread seeded first; its node follows every store.
            built.dispose()
            return checkNotNull(nodeRef.value)
        }
        // Replayed under the edge lock, so a later event for the same store
        // applies after its replay, never before.
        val replayed =
            synchronized(edgeLock) {
                val replay = pendingEdges.orEmpty().also { pendingEdges = null }
                replay.count { (store, added) -> applyEdgeLocked(built, store, added) } > 0
            }
        if (replayed) built.recomputeForSourceStores()
        // Disposed while seeding: stop the node at once, keeping its first tree readable.
        if (synchronized(edgeLock) { disposed }) built.dispose()
        return built
    }

    private fun buildNode(): DerivedStateNode<TreeSnapshot> {
        while (true) {
            val live = liveStores()
            val attempt =
                runCatching {
                    host.derivedStateOverStores(VALUE_NAME, sourceStores = live) { compute() }
                }
            attempt.getOrNull()?.let { return it }
            // A listed store disposed while the node was built: list again.
            val failure = attempt.exceptionOrNull()
            if (failure !is IllegalStateException || live.none { it.isDisposed }) throw checkNotNull(failure)
        }
    }

    /** The receiver and every live store under it, receiver first. */
    private fun liveStores(): List<Store<*>> {
        val listed = liveLeavesUnder(ownerNode, ownerNode)
        return listed.map { it.second }.filter { !it.isDisposed }
    }

    /** The recompute: ONE consistent capture of the subtree, reusing the last one's unmoved captures. */
    private fun compute(): TreeSnapshot {
        structuralPending.value = false
        val previous = lastTree.value
        // Disposed meanwhile: the value freezes at the last tree rather than failing the recompute.
        if (owner.isDisposed && previous != null) return previous
        val stats = CaptureStats()
        val tree = captureTree(ownerNode, ownerNode, SnapshotScope.All, previous, stats)
        lastTree.value = tree
        counters.settles.incrementAndGet()
        counters.captures.addAndGet(stats.captured)
        counters.cutRetries.addAndGet(stats.retries)
        return tree
    }

    /**
     * The value's read: the last settled tree — after settling a pending
     * structural change when no entry is open on this thread (the recompute
     * never waits for a busy host), or, when one is, a fresh capture that is
     * not committed, so a read inside an action or observer sees the store
     * that just joined or left without opening a transaction on the host.
     * Once the receiver is disposed it is the last settled tree whatever was
     * pending.
     */
    fun read(): TreeSnapshot {
        val node = node()
        val stale = !owner.isDisposed && structuralPending.value && NoWriteRegion.current() == null
        if (stale) {
            if (SettleScopes.current() == null) node.settleNow()
            if (structuralPending.value) {
                return captureTree(ownerNode, ownerNode, SnapshotScope.All, lastTree.value, null)
            }
        }
        return node.value
    }

    /** `StoreTree.internalSettleNow`: run a queued recompute now, outside every entry, and answer the tree. */
    fun settleNow(): TreeSnapshot {
        val node = node()
        node.settleNow()
        return node.value
    }

    /**
     * The receiver disposed (step 6 of its tree dispose): stop following,
     * drop the value's observers and hold no store; never takes a
     * transaction lock. The last tree stays readable. The host is disposed
     * by the caller, where no lock of the receiver's is held.
     */
    fun disposeSync() {
        // First, so that no event lands in the bookkeeping once it is cleared.
        synchronized(edgeLock) {
            disposed = true
            leafStores.clear()
            pendingEdges = null
        }
        val node = nodeRef.value
        node?.dispose()
        node?.backing?.shutdownSilently()
    }
}

private const val VALUE_NAME = "value"
