@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreLock
import kotlinx.atomicfu.atomic

/**
 * One keyed store's slot under a [KeyedBranch], from its reservation by
 * `create`/`getOrCreate` to its detachment. [constructionLock] is held by
 * the thread running the factory, so a `getOrCreate` on another thread
 * parks on it instead of spinning, and a detach waits for the attach fanout
 * to finish before it notifies, so a listener hears Attached strictly before
 * Detached.
 */
internal class LeafEntry(
    val branch: KeyedBranch<*, *>,
    val key: Any,
    val leaf: LeafNode,
) {
    val constructionLock = StoreLock()

    /** The thread running this entry's factory, `null` outside a construction. */
    @kotlin.concurrent.Volatile
    var constructingThreadId: Long? = null

    @kotlin.concurrent.Volatile
    var phase: Phase = Phase.Reserved

    /** Whether listeners heard `onAttached` for this entry (so an abandon after it tells them `onDetached`). */
    @kotlin.concurrent.Volatile
    var attachedNotified: Boolean = false

    sealed interface Phase {
        data object Reserved : Phase

        class Constructing(
            val store: Store<*>,
        ) : Phase

        class Live(
            val store: Store<*>,
        ) : Phase

        data object Detached : Phase
    }
}

/**
 * A root's membership: which nodes were declared under which, which keyed
 * entries exist in which phase, and which store sits at which leaf. Every
 * member is guarded by [lock], a leaf lock in the tree's lock order — taken
 * after a leaf's `transactionLock` (a store disposing from inside its own
 * action detaches under it), after a keyed entry's construction lock, and
 * never while calling into a store: callers copy what they need, release,
 * then touch stores.
 */
@Suppress("TooManyFunctions") // One lock guards one membership table; splitting the class would split the invariant.
internal class TreeRegistry(
    private val root: Root,
) {
    val lock = StoreLock()

    /** Declared branch and keyed-branch nodes, in declaration order. Guarded by [lock]. */
    private val declared = ArrayList<StoreNode>()

    /** Declared child nodes (branches and keyed branches) per parent, in declaration order. Guarded by [lock]. */
    private val childrenOf = HashMap<StoreNode, ArrayList<StoreNode>>()

    /** Keyed entries per keyed branch, in creation order. Guarded by [lock]. */
    private val keyed = HashMap<KeyedBranch<*, *>, LinkedHashMap<Any, LeafEntry>>()

    /** The leaf a store sits at, by the store's `lockOrderKey`. Guarded by [lock]. */
    private val leafByStoreKey = HashMap<Long, LeafNode>()

    /** Bumped by every declaration, promotion and detachment; readers compare it to know whether membership moved. */
    val structuralGeneration = atomic(0L)

    /** Copy-on-write; read without the lock by the fanouts, replaced under it. */
    @kotlin.concurrent.Volatile
    var listeners: List<LeafMembershipListener> = emptyList()

    @kotlin.concurrent.Volatile
    var disposed: Boolean = false
        private set

    private fun checkOpen() {
        check(!disposed) { "root '${root.name}' disposed" }
    }

    /** Register [branch] (its leaves already carry their stores) under [parent]. */
    fun registerBranch(
        branch: Branch,
        parent: StoreNode,
    ) = lock.withLock {
        checkOpen()
        registerChild(branch, parent)
        for (leaf in branch.leaves) leafByStoreKey[leaf.storeKey] = leaf
        structuralGeneration.incrementAndGet()
    }

    fun registerKeyed(
        branch: KeyedBranch<*, *>,
        parent: StoreNode,
    ) = lock.withLock {
        checkOpen()
        registerChild(branch, parent)
        keyed[branch] = LinkedHashMap()
        structuralGeneration.incrementAndGet()
    }

    private fun registerChild(
        node: StoreNode,
        parent: StoreNode,
    ) {
        val siblings = childrenOf.getOrPut(parent) { ArrayList() }
        check(siblings.none { it.name == node.name }) {
            "root '${root.name}': '${parent.name}' already has a child named '${node.name}'; " +
                "sibling names must be unique"
        }
        siblings.add(node)
        declared.add(node)
    }

    /**
     * Reserve [key] under [branch] for the calling `create`, or answer the
     * entry already there so `getOrCreate` can join or wait for it. The
     * second value is `true` when this call reserved it.
     */
    fun reserveOrExisting(
        branch: KeyedBranch<*, *>,
        key: Any,
        leafName: String,
    ): Pair<LeafEntry, Boolean> =
        lock.withLock {
            checkOpen()
            val entries =
                checkNotNull(keyed[branch]) { "keyed branch '${branch.name}' is not declared on root '${root.name}'" }
            entries[key]?.let { return@withLock it to false }
            val entry = LeafEntry(branch, key, LeafNode(root, branch, leafName, NameOrigin.Key, key))
            entries[key] = entry
            entry to true
        }

    /** Make [entry]'s store visible to lookups and captures: the last step of a successful `create`. */
    fun promote(
        entry: LeafEntry,
        store: Store<*>,
    ) = lock.withLock {
        checkOpen()
        check(entry.phase is LeafEntry.Phase.Constructing) {
            "root '${root.name}': keyed entry under '${entry.branch.name}' left the tree during its construction"
        }
        entry.phase = LeafEntry.Phase.Live(store)
        entry.leaf.storeKey = store.lockOrderKey
        leafByStoreKey[store.lockOrderKey] = entry.leaf
        structuralGeneration.incrementAndGet()
    }

    /** Drop a reservation whose construction failed. Idempotent. */
    fun abandon(entry: LeafEntry) =
        lock.withLock {
            val entries = keyed[entry.branch]
            if (entries?.get(entry.key) === entry) entries.remove(entry.key)
            entry.phase = LeafEntry.Phase.Detached
            if (entry.leaf.storeKey != 0L) leafByStoreKey.remove(entry.leaf.storeKey)
            entry.leaf.storeRef = null
        }

    /**
     * Take [leaf]'s store out of the tree (its dispose, or the root's).
     * Returns `true` when the leaf was live — a branch leaf still holding its
     * store, or a keyed entry that had been promoted — so the caller notifies
     * the detach; `false` for an entry still under construction, whose
     * `create` cleans up itself, and for a leaf already detached.
     */
    fun detachLeaf(leaf: LeafNode): Boolean =
        lock.withLock {
            val store = leaf.storeRef
            leaf.storeRef = null
            if (leaf.storeKey != 0L) leafByStoreKey.remove(leaf.storeKey)
            val wasLive =
                when (val parent = leaf.parent) {
                    is KeyedBranch<*, *> -> detachKeyedEntry(parent, leaf)
                    else -> store != null
                }
            if (wasLive) structuralGeneration.incrementAndGet()
            wasLive
        }

    /** Under [lock]. `true` iff the entry at [leaf] had been promoted. */
    private fun detachKeyedEntry(
        branch: KeyedBranch<*, *>,
        leaf: LeafNode,
    ): Boolean {
        val entries = keyed[branch]
        val key = leaf.key
        val entry = if (entries != null && key != null) entries[key]?.takeIf { it.leaf === leaf } else null
        if (entry == null) return false
        val live = entry.phase is LeafEntry.Phase.Live
        entry.phase = LeafEntry.Phase.Detached
        entries?.remove(key)
        return live
    }

    fun liveStore(
        branch: KeyedBranch<*, *>,
        key: Any,
    ): Store<*>? =
        lock.withLock {
            checkOpen()
            (keyed[branch]?.get(key)?.phase as? LeafEntry.Phase.Live)?.store
        }

    /** The live keyed entries of [branch], in creation order. */
    fun liveEntries(branch: KeyedBranch<*, *>): List<Pair<Any, Store<*>>> =
        lock.withLock {
            checkOpen()
            keyed[branch]?.values.orEmpty().mapNotNull { entry ->
                (entry.phase as? LeafEntry.Phase.Live)?.let { entry.key to it.store }
            }
        }

    fun leafOf(store: Store<*>): LeafNode? =
        lock.withLock {
            checkOpen()
            leafByStoreKey[store.lockOrderKey]
        }

    /** The branches and keyed branches declared directly under [node], in declaration order. */
    fun childNodes(node: StoreNode): List<StoreNode> = lock.withLock { childrenOf[node]?.toList() ?: emptyList() }

    /**
     * The tree's nodes in pre-order: each node, then its live leaves, then
     * its declared children recursively. Snapshot of one moment under the lock.
     */
    fun nodesPreorder(from: StoreNode): List<StoreNode> =
        lock.withLock {
            val out = ArrayList<StoreNode>()

            fun walk(node: StoreNode) {
                out.add(node)
                out.addAll(liveLeavesUnlocked(node))
                childrenOf[node]?.forEach(::walk)
            }
            walk(from)
            out
        }

    /** Under [lock]: a branch's listed stores still attached, or a keyed branch's promoted entries. */
    private fun liveLeavesUnlocked(node: StoreNode): List<LeafNode> =
        when (node) {
            is Branch -> node.leaves.filter { it.storeRef != null }
            is KeyedBranch<*, *> ->
                keyed[node]?.values.orEmpty().mapNotNull { entry ->
                    entry.leaf.takeIf { entry.phase is LeafEntry.Phase.Live }
                }
            is Root, is LeafNode -> emptyList()
        }

    fun addListener(listener: LeafMembershipListener) =
        lock.withLock {
            checkOpen()
            listeners = listeners + listener
        }

    fun removeListener(listener: LeafMembershipListener) =
        lock.withLock {
            listeners = listeners.filterNot { it === listener }
        }

    /**
     * Refuse every later registration and lookup, drop the listeners, and
     * hand back every live leaf so the root can release the stores'
     * attachments outside this lock. Called once, by `Root.dispose`.
     */
    fun close(): List<LeafNode> =
        lock.withLock {
            disposed = true
            val leaves = ArrayList<LeafNode>()
            for (node in declared) leaves.addAll(liveLeavesUnlocked(node))
            for (entries in keyed.values) {
                for (entry in entries.values) entry.phase = LeafEntry.Phase.Detached
                entries.clear()
            }
            leafByStoreKey.clear()
            listeners = emptyList()
            structuralGeneration.incrementAndGet()
            leaves
        }
}
