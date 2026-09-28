@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// Store-level derivation edges (issue #20 plan PR 15, decision D21): a derived
// state that follows whole stores, not listed states — what issue #21's
// `Root.value` recomputes on, over branches whose stores attach and detach at
// runtime. `DerivedStateNode.addSourceStore(store)` adds an edge: from then
// on, every change of that store recomputes the node, settling once per
// outermost entry (SettleScope.kt) like any source change — so a two-store
// frame over two followed stores recomputes it once.
//
// A change of a store, for an edge, is one of these — each changes what a
// capture of it ([snapshot]) holds:
//  - a commit that changes one of its states or evicts a keyed entry — told
//    from the commit's fanout, before any observer (FrameCommit.kt);
//  - an inbound bridge (or `observeFrom`) write, which changes a committed
//    value without a commit;
//  - a keyed entry coming to life (creating one is no commit, but a capture
//    lists it from then on);
//  - `removeState`/`clearStates` dropping a state (a capture then re-creates
//    it from its initializer, or no longer holds it).
// A commit that only writes a derived state's own backing (`derivedState`,
// `merged`) or library machinery's sealed states (a hydrator's phase) is not
// one: neither is store state a capture holds, and a node over its own host
// would otherwise recompute on its own commits. A legacy `derived` backing is
// (captured, it is the store's state). Each change queues the recompute where
// a source change would (SourceFollower): the settle scope open on this
// thread, else behind the committing transaction, else the host. A change
// noticed inside user code that runs outside any entry — a state initializer,
// a `migrate`, a compute — lands in a settle scope too: the outermost
// first-read materialization opens one (Materialization.kt), and so does a
// restore from its plan on (restore, restoreInOneFrame), so a keyed entry
// such code creates recomputes the node once, after it, never in its middle.
//
// The followed store holds its followers in a StoreEdges attachment (the PR 10
// slot, StoreAttachments.kt), which also hears the store's `dispose()`: the
// edge is dropped then, and the node recomputes once more without it. A node
// holds the stores it follows in FollowedStores, and drops its edges when it is
// disposed — so attaching and disposing stores leaves no follower behind on a
// disposed store, and no disposed store behind in a node.
//
// Edges are added and removed from any thread. A commit whose fanout starts
// after addSourceStore returned recomputes the node; one already fanning out
// may miss the edge — but it applied before the edge was registered (the
// fanout reads the followers after its apply pass), and addSourceStore queues
// a recompute of its own after registering, which reads the store after that
// commit. So no change is ever missed; a commit racing the add at most
// recomputes the node twice. removeSourceStore is the mirror image: a commit
// already fanning out may recompute the node once more, none after it returns
// does, and the removal queues a recompute, so the value stops covering the
// store. An add and a remove of the same store racing on two threads each
// change the node's list and the store's followers as one step
// (FollowedStores.addWithEdge/removeWithEdge), so the node lists a store
// exactly when that store's edges hold it. What a node's compute READS of a followed store it reads as any
// compute does — committed values, and a consistent cut across stores only
// through [captureConsistent]; an edge only decides when it recomputes.
//
// An edge taints no node Secret and orders nothing in a settle. A derived
// state's own commit (`derivedState`/`merged`) is no change of its store, so
// a compute that reads one of a followed store lists it in `sources`
// (derivedStateOverStores): read without being listed, it lags until that
// store's next change, since the node may settle before it (at rank 0, with
// no deeper source) and is not told when it recomputes. A branch attached at
// runtime can only have the states under its derived states followed
// through the edge, so a compute reads those states rather than the
// branch's derived states.

/** The key of a store's [StoreEdges]: the followers that follow it as a whole. */
internal val STORE_EDGES = StoreAttachmentKey<StoreEdges>("store-level derivation edges")

/**
 * The derived states that follow [store] as a whole ([SourceFollower]s with
 * an edge to it), attached to [store] under [STORE_EDGES] by the first of
 * them. [changed] tells them all of a change; [onStoreDisposed] drops them,
 * each told that its source store is gone, and refuses every later [add].
 * Attachments stay for the store's life, so once its last follower is
 * removed it stays attached, empty — referencing no follower.
 */
internal class StoreEdges(
    private val store: Store<*>,
) : StoreAttachment {
    private val lock = SynchronizedObject()

    /** Replaced as a whole under [lock]; read without it. */
    @kotlin.concurrent.Volatile
    private var followers: List<SourceFollower<*>> = emptyList()

    /** Set once, by [onStoreDisposed]; guarded by [lock]. */
    private var closed = false

    /** How many followers follow [store] right now. */
    val followerCount: Int
        get() = followers.size

    /** Add [follower] — once: by identity — unless [store] is disposed (`false`). */
    fun add(follower: SourceFollower<*>): Boolean =
        synchronized(lock) {
            if (!closed && followers.none { it === follower }) followers = followers + follower
            !closed
        }

    fun remove(follower: SourceFollower<*>) {
        synchronized(lock) { followers = followers.filterNot { it === follower } }
    }

    /** [store] changed: queue each follower's recompute (never runs one inside a commit's fanout). */
    fun changed() {
        for (follower in followers) follower.onStoreChanged(store)
    }

    /**
     * [store] was disposed: drop every follower, and tell each (its node
     * recomputes without the store), each isolated — a failure goes to its
     * host's handler, a handler that throws there is ignored (as `dispose()`
     * ignores one for an attachment), and the next follower is still told.
     */
    override fun onStoreDisposed() {
        val gone =
            synchronized(lock) {
                closed = true
                followers.also { followers = emptyList() }
            }
        for (follower in gone) runCatching { follower.onSourceStoreDisposed(store) }
    }
}

/**
 * The stores one [SourceFollower] follows as a whole ([stores], in the order
 * added), and the post-commit queues other than its host's that its
 * recompute can sit in ([queuedOn]): its state sources' stores and its
 * followed stores. Once [releaseAll] has run it follows nothing more.
 */
internal class FollowedStores(
    private val host: Store<*>,
) {
    private val lock = SynchronizedObject()

    /** The stores of the follower's state sources, other than its host; guarded by [lock]. */
    private var stateSourceStores: List<Store<*>> = emptyList()

    /** Guarded by [lock]. */
    private var closed = false

    @kotlin.concurrent.Volatile
    var stores: List<Store<*>> = emptyList()
        private set

    @kotlin.concurrent.Volatile
    var queuedOn: List<Store<*>> = emptyList()
        private set

    /** Note the stores of [sources], the follower's state sources. */
    fun noteStateSources(sources: List<MutableState<*>>) {
        synchronized(lock) {
            stateSourceStores = sources.map { it.owningStore }.filter { it !== host }.distinct()
            refreshQueuedOn()
        }
    }

    /**
     * Follow [store] and add [follower] to its [edges] (attached already), as
     * ONE step under [lock]: so an add and a remove of the same store racing
     * on two threads leave [store] listed exactly when its edges hold
     * [follower]. Lock order: this [lock], then the edges' own, which never
     * calls a follower while held.
     */
    fun addWithEdge(
        store: Store<*>,
        edges: StoreEdges,
        follower: SourceFollower<*>,
    ): EdgeAdd =
        synchronized(lock) {
            when {
                closed || stores.any { it === store } -> EdgeAdd.UNCHANGED
                !edges.add(follower) -> EdgeAdd.DISPOSED
                else -> {
                    stores = stores + store
                    refreshQueuedOn()
                    EdgeAdd.ADDED
                }
            }
        }

    /**
     * Stop following [store] and take [follower] off its edges, as one step
     * under [lock] (see [addWithEdge]): `false` when it was not followed.
     */
    fun removeWithEdge(
        store: Store<*>,
        follower: SourceFollower<*>,
    ): Boolean =
        synchronized(lock) {
            val removed = forgetLocked(store)
            // Answers null once [store] is disposed: its edges drop every
            // follower then themselves (StoreEdges.onStoreDisposed).
            if (removed) store.attachmentSlot.get(STORE_EDGES)?.remove(follower)
            removed
        }

    /**
     * Stop following [store], whose edges dropped the follower already (the
     * store was disposed): `false` when it was not followed.
     */
    fun forget(store: Store<*>): Boolean = synchronized(lock) { forgetLocked(store) }

    /** Follow nothing more, and take [follower] off every store it followed. */
    fun releaseAll(follower: SourceFollower<*>) {
        val all =
            synchronized(lock) {
                closed = true
                stores.also {
                    stores = emptyList()
                    refreshQueuedOn()
                }
            }
        for (store in all) store.attachmentSlot.get(STORE_EDGES)?.remove(follower)
    }

    /** The caller holds [lock]. */
    private fun forgetLocked(store: Store<*>): Boolean {
        val kept = stores.filterNot { it === store }
        val removed = kept.size != stores.size
        if (removed) {
            stores = kept
            refreshQueuedOn()
        }
        return removed
    }

    /** The caller holds [lock]. Identity: a store never overrides `equals`. */
    private fun refreshQueuedOn() {
        queuedOn = (stateSourceStores + stores.filter { it !== host }).distinct()
    }
}

/** What [FollowedStores.addWithEdge] did. */
internal enum class EdgeAdd {
    /** The store is followed now, and its edges hold the follower. */
    ADDED,

    /** Nothing: the store was followed already, or the follower is released. */
    UNCHANGED,

    /** Nothing: the store is disposed, or being disposed. */
    DISPOSED,
}

/**
 * This store's [StoreEdges], attached first when it has none; `null` when the
 * store is disposed, or being disposed.
 */
internal fun Store<*>.storeEdgesOrNull(): StoreEdges? {
    val attached = runCatching { internalAttachIfAbsent(STORE_EDGES) { StoreEdges(this) } }
    return attached.getOrNull()
}

/**
 * The store this applied commit wrote, told of it when the commit changed
 * store state a capture holds (see the top of this file): any state but a
 * derived state's backing or a sealed one, or an eviction. Called from the
 * commit's fanout ([Transaction.fanOutApplied]), before any observer; it only
 * queues recomputes (into the settle scope open on this thread, or behind
 * the committing transaction), so it runs no user code.
 */
internal fun AppliedWrites.tellStoreEdges() {
    val store = committed.firstOrNull()?.first?.owningStore ?: evicted.firstOrNull()?.owningStore ?: return
    val edges = store.attachmentSlot.get(STORE_EDGES) ?: return
    if (evicted.isNotEmpty() || committed.any { (state, _) -> state.isCapturedStoreState }) edges.changed()
}

/**
 * Tell this store's store-level edges that it changed outside any commit: an
 * inbound bridge write, a keyed entry that came to life, or a state
 * `removeState`/`clearStates` dropped ([shutDownDematerialized]). Lock-free
 * when nothing follows the store.
 */
internal fun Store<*>.tellStoreEdges() {
    attachmentSlot.get(STORE_EDGES)?.changed()
}

/**
 * Shut down [states], which `removeState`/`clearStates` just dropped, then
 * tell this store's edges: a capture now re-creates them from their
 * initializers (or, for a legacy derived backing or an internal state, no
 * longer holds them), which changes what it holds. Called outside the
 * registry's lock. The edges are told even when a shutdown throws (a
 * bridge's `dispose`), which then propagates.
 */
internal fun Store<*>.shutDownDematerialized(states: Collection<MutableState<*>>) {
    try {
        states.forEach { it.shutdownSilently() }
    } finally {
        if (states.any { it.isCapturedStoreState }) tellStoreEdges()
    }
}

/** Whether this state is store state a capture holds: not a derived state's backing, and not a sealed state. */
private val MutableState<*>.isCapturedStoreState: Boolean
    get() = declaration?.kind.let { it != StateKind.ReadOnlyDerived && it != StateKind.Sealed }
