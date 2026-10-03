@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.CycleStep
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreLock
import com.vynatix.holdfast.displayName

/**
 * One keyed store's slot under a [KeyedBranch], from the claim of its key
 * by the `create`/`getOrCreate` whose factory run attached first to its
 * detachment. An entry exists only once a factory returned (the factory
 * runs holding nothing, `KeyedConstruction.kt`): it is created Constructing
 * by [ChildRegistry.claimKey], with [constructionLock] taken, and the
 * claiming thread holds that lock through attach phases 3–6, which run no
 * user code — so a lookup or a losing creator on another thread parks on it
 * instead of spinning, and never waits on user code.
 */
internal class LeafEntry(
    val branch: KeyedBranch<*, *>,
    val key: Any,
    val leafName: String,
) {
    val constructionLock = StoreLock()

    /** The thread attaching this entry's store, `null` once that attach ended. */
    @kotlin.concurrent.Volatile
    var constructingThreadId: Long? = null

    @kotlin.concurrent.Volatile
    var phase: Phase = Phase.Constructing

    /** The produced store's node, set by the claim. */
    @kotlin.concurrent.Volatile
    var leaf: LeafNode? = null

    sealed interface Phase {
        data object Constructing : Phase

        class Live(
            val store: Store<*>,
        ) : Phase

        data object Detached : Phase
    }
}

/**
 * One child declaration of [owner] — `store { }`, `group { }` or
 * `keyed<K, S> { }` — named [name], in declaration order in [owner]'s
 * [ChildRegistry]. A `store`/`group` child is materialized on first need:
 * its lambda runs holding no lock or latch, marked on the reading thread
 * only (`InitializerGraph.runMarked`, so a same-thread re-entry — directly,
 * through another child or through a state initializer — throws a cycle
 * error naming the chain), and racing first reads may each run it; the
 * first to claim the entry attaches its result and every other reader gets
 * that (`TreeMaterialize.kt`). A keyed entry is live from its declaration.
 *
 * [ownerAttachment] is the owner's tree state, captured at `provideDelegate`:
 * materialization reaches the owner's registry and node through it, never
 * through the owner's attachment slot (closed first by `dispose()`) — and
 * never as `Store.registry`, which is the owner's STATE registry.
 */
internal class ChildEntry(
    val ownerAttachment: TreeLeafAttachment,
    val name: String,
    val origin: NameOrigin,
    val kind: Kind,
    /** `() -> Any` for [Kind.Store]; `GroupScope.() -> List<Store<*>>` for [Kind.Group]; unused for [Kind.Keyed]. */
    val lambda: Any,
    /**
     * For [Kind.Store]/[Kind.Group]: whether the owner's dispose disposes the
     * stores the winning run built ([owned]) or only releases them. A keyed
     * branch keeps its own (`KeyedBranch.onParentDispose`).
     */
    val onParentDispose: KeyedDisposal = KeyedDisposal.Release,
) : CycleStep {
    /** The declaring store, captured while it is live (its tree state drops it on dispose). */
    val owner: Store<*> = checkNotNull(ownerAttachment.storeRef) { "a child declared on a disposed store" }

    /** The owner's children. */
    val registry: ChildRegistry get() = ownerAttachment.registry

    /** The owner's node. */
    val ownerNode: LeafNode get() = ownerAttachment.node

    /** `Owner.name`, for messages. */
    val label: String get() = "${owner.displayName}.$name"

    override fun describeForCycle(): String = "child declaration '${owner.displayName}.$name'"

    /**
     * Held by the thread that claimed this entry (Declared → Constructing,
     * under the registry lock) from the claim through attach phases 3–7,
     * which run no user code; a racing reader parks on it, then reads the
     * entry again. Taken by a fresh claim under the registry lock, and
     * released under it together with the phase change that ends the claim,
     * so a claimer never finds the lock held by a finished claim.
     */
    val attachLock = StoreLock()

    /** The thread holding [attachLock] for a claim, `null` outside one. */
    @kotlin.concurrent.Volatile
    var attachingThreadId: Long? = null

    /** The claimer's result while it attaches (answered to its own thread's re-reads, e.g. from a listener). */
    @kotlin.concurrent.Volatile
    var attaching: Any? = null

    enum class Kind { Store, Group, Keyed }

    enum class Phase { Declared, Constructing, Live, Detached }

    @kotlin.concurrent.Volatile
    var phase: Phase = Phase.Declared

    /** The child's node: its [LeafNode], the group's [Branch] or the [KeyedBranch]; `null` until materialized. */
    @kotlin.concurrent.Volatile
    var node: StoreNode? = null

    /** What the delegate answers — the child (`S`), the [Branch] or the [KeyedBranch] — written LAST. */
    @kotlin.concurrent.Volatile
    var produced: Any? = null

    /**
     * The stores the claiming run built on its own thread ([RunBuilt]) —
     * the child, or the group members it constructed — when
     * [onParentDispose] is [KeyedDisposal.Dispose]; empty otherwise. Written
     * by the claim under the registry lock, cleared there when the claim
     * fails back to Declared; never cleared by [ChildRegistry.close], so the
     * owner's dispose reads it after closing (step 9 of `StoreDetach`).
     */
    @kotlin.concurrent.Volatile
    var owned: List<Store<*>> = emptyList()

    /** [node] while the child is attached: what path resolution and listings read. */
    val liveNode: StoreNode? get() = node.takeIf { phase == Phase.Live }
}

/**
 * One store's children: its child declarations in declaration order, the
 * keyed entries of its keyed branches, and its membership listeners. Every
 * member is guarded by [lock] — taken after any store's `transactionLock`
 * (a store disposing from inside its own action detaches from its parent
 * under it), after a keyed entry's construction lock, ONE registry at a
 * time, and never while calling into a store, taking a slot lock or the
 * tree structure lock: callers copy what they need, release, then act.
 */
@Suppress("TooManyFunctions") // One lock guards one membership table; splitting the class would split the invariant.
internal class ChildRegistry(
    private val owner: Store<*>,
) {
    val lock = StoreLock()

    /** The child declarations by name, in declaration order. Guarded by [lock]. */
    private val declared = LinkedHashMap<String, ChildEntry>()

    /** Keyed entries per keyed branch, in creation order. Guarded by [lock]. */
    private val keyed = HashMap<KeyedBranch<*, *>, LinkedHashMap<Any, LeafEntry>>()

    /** Copy-on-write; read without the lock by the announcers, replaced under it. */
    @kotlin.concurrent.Volatile
    var listeners: List<LeafMembershipListener> = emptyList()
        private set

    /** Set by [close]; read without the lock by the announcers' validation. */
    @kotlin.concurrent.Volatile
    var disposed: Boolean = false
        private set

    /** Under [lock]. */
    fun checkOpen() {
        check(!disposed) { "${owner.displayName} disposed" }
    }

    /**
     * Register [entry] (and, for a keyed entry, its branch's entry table).
     *
     * @throws IllegalStateException on a closed registry or a sibling name taken.
     */
    fun declare(entry: ChildEntry) =
        lock.withLock {
            checkOpen()
            check(entry.name !in declared) {
                "${owner.displayName} already has a child named '${entry.name}'; sibling names must be unique"
            }
            declared[entry.name] = entry
            (entry.produced as? KeyedBranch<*, *>)?.let { keyed[it] = LinkedHashMap() }
        }

    /** The declaration named [name]; what [close] left on a closed registry. */
    fun declaredEntry(name: String): ChildEntry? = lock.withLock { declared[name] }

    /** Every declaration, in declaration order; what [close] left on a closed registry. */
    fun declaredEntries(): List<ChildEntry> = lock.withLock { declared.values.toList() }

    /** Under [lock]: [declaredEntries] for a walker that already holds it. */
    fun declaredEntriesLocked(): Collection<ChildEntry> = declared.values

    /**
     * The entry under [key] of [branch], live or still being attached; `null`
     * when there is none.
     *
     * @throws IllegalStateException on a closed registry.
     */
    fun keyedEntry(
        branch: KeyedBranch<*, *>,
        key: Any,
    ): LeafEntry? =
        lock.withLock {
            checkOpen()
            checkNotNull(keyed[branch]) { "${owner.displayName}: '${branch.name}' is not declared here" }[key]
        }

    /**
     * Claim [key] under [branch] for a store a factory run returned, whose
     * node is [leaf], or answer the entry already there (another run won).
     * The second value is `true` when this call claimed it — and then the
     * calling thread holds the new entry's construction lock, which the
     * construction releases once the store attached or the claim was
     * abandoned.
     *
     * @throws IllegalStateException on a closed registry.
     */
    fun claimKey(
        branch: KeyedBranch<*, *>,
        key: Any,
        leafName: String,
        leaf: LeafNode,
        threadId: Long,
    ): Pair<LeafEntry, Boolean> =
        lock.withLock {
            checkOpen()
            val entries = checkNotNull(keyed[branch]) { "${owner.displayName}: '${branch.name}' is not declared here" }
            entries[key]?.let { return@withLock it to false }
            val entry = LeafEntry(branch, key, leafName)
            // Taken under the registry lock, against the documented
            // "construction lock before registry lock" order — only
            // nominally: the entry is created here and published only by the
            // line below, so no other thread can hold or wait on this lock
            // and the take never blocks (a `tryAcquire`, so a break of that
            // reasoning fails here instead of waiting under this lock). Every
            // later take of the registry lock by a construction-lock holder
            // keeps the documented order, and no path holds the registry lock
            // while waiting on a published entry's construction lock.
            check(entry.constructionLock.tryAcquire()) {
                "${owner.displayName}: a fresh keyed entry's construction lock was already held"
            }
            entry.leaf = leaf
            entry.constructingThreadId = threadId
            entries[key] = entry
            entry to true
        }

    /** Under [lock] (attach phase 4's registry step): make [entry]'s store visible to lookups and listings. */
    fun promoteLocked(
        entry: LeafEntry,
        store: Store<*>,
    ) {
        check(entry.phase === LeafEntry.Phase.Constructing) {
            "${owner.displayName}: keyed entry under '${entry.branch.name}' left the tree during its construction"
        }
        entry.phase = LeafEntry.Phase.Live(store)
    }

    /** Drop a claim whose attach failed. Idempotent; bumps nothing (an unpromoted claim is never listed). */
    fun abandon(entry: LeafEntry) =
        lock.withLock {
            val entries = keyed[entry.branch]
            if (entries?.get(entry.key) === entry) entries.remove(entry.key)
            entry.phase = LeafEntry.Phase.Detached
        }

    /**
     * Take [leaf] out of this store's children: its store disposed. [edge]
     * is the parent edge the caller captured (never `leaf.parent`, which the
     * owner's own dispose may be resetting). `true` when the leaf was live
     * here — so the caller announces the detach; `false` on a closed
     * registry (the owner's dispose covers the leaf) and for a leaf already
     * detached. Bumps nothing (the caller does) and never nulls the store.
     */
    fun detachLeaf(
        leaf: LeafNode,
        edge: ParentEdge,
    ): Boolean =
        lock.withLock {
            if (disposed) return@withLock false
            val keyedEntry = edge.entry
            when (val parentNode = edge.parentNode) {
                is LeafNode -> detachStoreChild(leaf)
                is Branch -> detachGroupMember(parentNode, leaf)
                is KeyedBranch<*, *> -> keyedEntry != null && detachKeyedEntry(keyedEntry)
            }
        }

    /** Under [lock]. */
    private fun detachStoreChild(leaf: LeafNode): Boolean {
        val entry =
            declared.values.firstOrNull {
                it.kind == ChildEntry.Kind.Store && it.node === leaf && it.phase == ChildEntry.Phase.Live
            }
        entry?.phase = ChildEntry.Phase.Detached
        return entry != null
    }

    /** Under [lock]. The group's entry stays live while any member is. */
    private fun detachGroupMember(
        branch: Branch,
        leaf: LeafNode,
    ): Boolean {
        val entry = declared[branch.name]?.takeIf { it.node === branch } ?: return false
        return entry.phase == ChildEntry.Phase.Live && branch.live.remove(leaf)
    }

    /** Under [lock]. */
    private fun detachKeyedEntry(entry: LeafEntry): Boolean {
        if (entry.phase !is LeafEntry.Phase.Live) return false
        entry.phase = LeafEntry.Phase.Detached
        keyed[entry.branch]?.let { entries -> if (entries[entry.key] === entry) entries.remove(entry.key) }
        return true
    }

    /** The live leaf under [key]; `null` on a closed registry. */
    fun liveLeaf(
        branch: KeyedBranch<*, *>,
        key: Any,
    ): LeafNode? = lock.withLock { keyed[branch]?.get(key)?.takeIf { it.phase is LeafEntry.Phase.Live }?.leaf }

    /** The live keyed entry under [key] with its store; `null` on a closed registry. */
    fun liveEntry(
        branch: KeyedBranch<*, *>,
        key: Any,
    ): Pair<LeafEntry, Store<*>>? =
        lock.withLock {
            keyed[branch]?.get(key)?.let { entry -> (entry.phase as? LeafEntry.Phase.Live)?.let { entry to it.store } }
        }

    /** The live keyed entries of [branch] with their stores, in creation order; empty on a closed registry. */
    fun liveEntries(branch: KeyedBranch<*, *>) = lock.withLock { liveEntriesLocked(branch) }

    /** Under [lock]: the live keyed entries of [branch] with their stores, in creation order. */
    fun liveEntriesLocked(branch: KeyedBranch<*, *>): List<Pair<LeafEntry, Store<*>>> =
        keyed[branch]?.values.orEmpty().mapNotNull { entry ->
            (entry.phase as? LeafEntry.Phase.Live)?.let { entry to it.store }
        }

    /**
     * Every live child store with its node, in declaration (then creation)
     * order: each `store { }` child, each group's live members, each keyed
     * branch's live entries. A child whose store is gone is left out.
     */
    fun liveChildStores(): List<Pair<LeafNode, Store<*>>> =
        lock.withLock {
            val out = ArrayList<Pair<LeafNode, Store<*>>>()
            for (leaf in directChildLeavesLocked()) leaf.store?.let { out.add(leaf to it) }
            out
        }

    /** The declared children's live nodes, in declaration order: what `tree.children()` lists. */
    fun liveChildNodes(): List<StoreNode> = lock.withLock { declared.values.mapNotNull { it.liveNode } }

    /** Under [lock]: every directly attached child leaf, in declaration (then listing/creation) order. */
    private fun directChildLeavesLocked(): List<LeafNode> {
        val out = ArrayList<LeafNode>()
        for (entry in declared.values) {
            if (entry.phase != ChildEntry.Phase.Live) continue
            when (val node = entry.node) {
                is LeafNode -> out.add(node)
                is Branch -> node.leaves.filterTo(out) { it in node.live }
                is KeyedBranch<*, *> -> liveEntriesLocked(node).mapNotNullTo(out) { it.first.leaf }
                null -> Unit
            }
        }
        return out
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
     * The owner disposed: refuse every later registration, mark every child
     * declaration and keyed entry detached, forget what was produced, drop
     * the listeners WITHOUT telling them, and hand back the direct children's
     * nodes so the dispose can announce and release them outside this lock.
     * Touches no child's store.
     */
    fun close(): List<LeafNode> =
        lock.withLock {
            val released = directChildLeavesLocked()
            disposed = true
            for (entry in declared.values) {
                (entry.node as? Branch)?.live?.clear()
                entry.produced = null
                entry.node = null
                entry.phase = ChildEntry.Phase.Detached
            }
            for (entries in keyed.values) {
                for (entry in entries.values) entry.phase = LeafEntry.Phase.Detached
                entries.clear()
            }
            listeners = emptyList()
            released
        }
}
