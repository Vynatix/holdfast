@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.atomicfu.atomic

// The machinery behind DerivedState (DerivedState.kt): checking its inputs,
// following its sources, staging its recomputed value, and refusing every
// other write to it.

/**
 * [source] resolved to the state that fires on its commits
 * ([observableBacking]), for [api] (`derived`, `derivedState`, …) to follow.
 *
 * @throws IllegalArgumentException for a [computed] state, or any State no
 *   store produced.
 */
internal fun State<*>.observableSourceFor(api: String): MutableState<*> =
    requireNotNull(observableBacking()) {
        "$api cannot follow this source: a source must be a state a store produced — a declared state " +
            "(`val x by state { … }`), a derived state or a derivedState/merged — whose commits it recomputes " +
            "on. A computed { } state has no commits to follow: list the states it reads as sources instead."
    }

/**
 * A [derivedState] source: [source] resolved as [observableSourceFor], on a
 * store that is not disposed.
 */
internal fun observedSource(
    source: State<*>,
    api: String,
): MutableState<*> {
    val backing = source.observableSourceFor(api)
    check(!backing.owningStore.isDisposed) {
        "store disposed: ${backing.owningStore.displayName}, the store of a $api source"
    }
    return backing
}

/**
 * One of [merged]'s inputs: [state] as a state this store declares.
 *
 * @throws IllegalArgumentException naming what [state] is instead.
 */
internal fun Store<*>.mergeInput(
    state: State<*>,
    role: String,
): MutableState<*> {
    val backing = state.observableBacking()
    val decl = backing?.declaration
    require(backing != null && decl?.kind == StateKind.Declared && backing.owningStore === this) {
        val what =
            when {
                backing == null -> "a computed { } state (or a State no store produced)"
                backing.owningStore !== this -> "a state of another store (${backing.owningStore.displayName})"
                decl == null -> "a state constructed by hand"
                else -> "a ${decl.kind.describe()}"
            }
        "merged on $displayName: its $role input is $what. merged(local, remote) merges two different states " +
            "that $displayName declares (`val x by state { … }`) and that are written directly — the user's side " +
            "and sync's side — so neither may be another store's, or computed from other states."
    }
    return backing
}

/** How a derived state's name refers to this source: its name, qualified when it is another store's. */
internal fun MutableState<*>.sourceName(host: Store<*>? = null): String {
    val decl = declaration ?: return "a state of ${owningStore.displayName}"
    return if (host == null || owningStore === host) decl.name else decl.qualifiedName
}

/**
 * Follow [sources] for a derived state of [host]: subscribe to each now, and
 * queue the recompute after each commit that changes one once the returned
 * follower is [armed][SourceFollower.arm]. Subscribing before the initial
 * compute runs, and arming after, is what keeps a source commit that lands
 * in between from being lost (see [SourceFollower]).
 */
internal fun <V : Store<V>> followSources(
    host: V,
    sources: List<MutableState<*>>,
): SourceFollower<V> = SourceFollower(host).also { it.start(sources) }

/**
 * The subscriptions of one derived state to its sources, and where a source
 * change queues its recompute.
 *
 * **Before the recompute exists.** The follower subscribes before the
 * derived state's initial compute runs and is [armed][arm] with the
 * recompute after it, so no source commit can fall between the two: one
 * whose fanout missed the new subscription applied its value before the
 * subscription was taken, so the compute (which runs after) reads it; one
 * whose fanout includes it reaches [onSourceChanged], which before arming
 * only records that a commit arrived ([MISSED]), and [arm] then queues one
 * catch-up recompute. Each subscription drops its first callback, which is
 * normally [MutableState.observe]'s initial one; when a racing commit's
 * callback overtakes it, the commit's is dropped instead and the initial one
 * counts as a change — one extra recompute, never a lost one.
 *
 * **Where a change queues it.** Into the settle scope open on this thread
 * ([SettleScopes]): every commit runs inside an entry (an action, a frame,
 * `suspendAction`, `suspendAtomic`, a recompute's own transaction), so a
 * source commit queues the recompute into its entry's scope, which
 * deduplicates it across every source, store and nested action of that
 * entry, and runs it once the outermost entry on the thread has released
 * everything it took. With no scope open (a scope not carried to this thread,
 * see `:holdfast-coroutines`' SettleAmbientContext.kt): inside a commit's
 * fanout on the source's store, the recompute is queued on THAT store, whose
 * holder runs it once the commit has released the store. A change outside
 * any commit and any entry — a `bridge` or `observeFrom` write, which has no
 * commit — queues it on the host instead, as `derived` does, so it runs at
 * once on an idle host rather than waiting for whoever holds the source's
 * store.
 *
 * **Whole stores.** Besides its state sources, a follower can follow whole
 * stores — store-level edges ([addSourceStore], StoreEdges.kt): any change
 * of such a store queues the recompute the same way, and so does adding or
 * removing an edge, or disposing a followed store (which drops its edge), so
 * the value covers exactly the stores followed. A change noticed inside a
 * state initializer, a schema migration or a compute (a keyed entry one
 * creates) finds a settle scope open even outside any entry — the outermost
 * first-read materialization and a restore from its plan on open one, and a
 * recompute's compute runs in its top-level attempt's — so it recomputes
 * once that code has returned, never in its middle.
 *
 * A change that finds the host disposed releases every subscription and
 * edge, so a disposed host does not stay referenced by another store's states.
 */
@Suppress("TooManyFunctions") // One lifecycle: subscribe, arm, follow, queue, settle, dispose.
internal class SourceFollower<V : Store<V>>(
    private val host: V,
) : Disposable {
    private val released = atomic(false)

    /** [UNARMED], then [MISSED] once a commit arrived before [arm], then [ARMED]. */
    private val phase = atomic(UNARMED)

    /** Set by [arm], before [phase] turns [ARMED]. */
    @kotlin.concurrent.Volatile
    private var recompute: DerivedRecompute<V, *>? = null

    @kotlin.concurrent.Volatile
    private var subscriptions: List<Disposable> = emptyList()

    /** The stores followed as a whole, and the queues other than the host's the recompute can sit in. */
    val followed = FollowedStores(host)

    /** The stores this follower follows as a whole ([addSourceStore]), in the order added. */
    val sourceStores: List<Store<*>>
        get() = followed.stores

    fun start(sources: List<MutableState<*>>) {
        followed.noteStateSources(sources)
        subscriptions = sources.map { follow(it) }
        // Released while subscribing (a concurrent dispose saw the list
        // before it was assigned): drop what was just subscribed. Disposing
        // a subscription twice is harmless.
        if (released.value) subscriptions.forEach { it.dispose() }
    }

    /**
     * Start queueing [task] on source changes. It is queued once now when a
     * source commit arrived since [start], or when [catchUp] says the initial
     * compute read values that may still change without a commit (see
     * `createDerivedState`): into this thread's settle scope when one is
     * open, so it runs once the entry holding those values has settled;
     * otherwise on the host, where an idle host runs it at once, a busy one
     * hands it to its holder, and the recompute defers itself to a blocking
     * action or frame this thread runs on a source's store.
     */
    fun arm(
        task: DerivedRecompute<V, *>,
        catchUp: Boolean,
    ) {
        recompute = task
        val missed = phase.getAndSet(ARMED) == MISSED
        // Disposed before the recompute existed: dispose saw no recompute to
        // dispose, so dispose it here (disposing it twice is harmless).
        if (released.value) {
            task.dispose()
            return
        }
        if ((missed || catchUp) && SettleScopes.current()?.enqueue(task) != true) host.postCommit(task)
    }

    /**
     * Follow [store] as a whole from now on (see StoreEdges.kt), and — with
     * [catchUp] — queue a recompute, which reads [store] after the edge is
     * registered, so a commit racing this call is never missed. `false`,
     * changing nothing, when [store] is followed already or this follower is
     * released (its derived state, or its host, disposed).
     *
     * @throws IllegalStateException if [store] is disposed.
     */
    fun addSourceStore(
        store: Store<*>,
        catchUp: Boolean = true,
    ): Boolean {
        check(!store.isDisposed) { disposedSourceStore(store) }
        if (host.isDisposed) dispose()
        // Attached before [FollowedStores]' lock is taken: the slot's lock
        // must not nest in it.
        val edges = store.storeEdgesOrNull() ?: error(disposedSourceStore(store))
        val added =
            when (followed.addWithEdge(store, edges, this)) {
                EdgeAdd.UNCHANGED -> false
                // Disposed meanwhile, its edges already told.
                EdgeAdd.DISPOSED -> error(disposedSourceStore(store))
                // Unless released meanwhile: its release takes this edge off
                // again (FollowedStores.releaseAll lists it, or refused the add).
                EdgeAdd.ADDED -> !released.value
            }
        if (added && catchUp) onStoreChanged(host)
        return added
    }

    /**
     * Stop following [store] as a whole, and queue a recompute, so the value
     * stops covering it. `false`, changing nothing, when it was not followed.
     */
    fun removeSourceStore(
        store: Store<*>,
        recomputeNow: Boolean = true,
    ): Boolean {
        if (!followed.removeWithEdge(store, this)) return false
        // A recompute a commit of the store queued behind it, which nothing
        // would withdraw any more: the one queued below (or by the caller,
        // through [recomputeForSourceStores]) covers that commit.
        if (followed.queuedOn.none { it === store }) recompute?.let { store.withdrawPostCommit(it) }
        if (recomputeNow) onStoreChanged(host)
        return true
    }

    /** Queue the recompute an [addSourceStore]/[removeSourceStore] without one owes. */
    fun recomputeForSourceStores() = onStoreChanged(host)

    /** Run the recompute now (see `DerivedStateNode.settleNow`); nothing before [arm] or after [dispose]. */
    fun settleNow() {
        if (!released.value) recompute?.invoke()
    }

    /** [store], followed as a whole, changed ([StoreEdges.changed]). */
    fun onStoreChanged(store: Store<*>) {
        if (host.isDisposed) dispose() else onSourceChanged(store, edge = true)
    }

    /**
     * [store], followed as a whole, was disposed: its edge is gone, so
     * recompute without it. Never throws: a failure goes to the host's
     * handler, and a handler that throws there is ignored, so [StoreEdges]
     * tells the next follower. The store is forgotten first, whatever fails.
     */
    fun onSourceStoreDisposed(store: Store<*>) {
        runCatching { if (followed.forget(store)) onStoreChanged(host) }
            .onFailure { failure -> if (!host.isDisposed) runCatching { host.internalReportUncaughtFailure(failure) } }
    }

    private fun follow(source: MutableState<*>): Disposable {
        val initialFire = atomic(true)
        @Suppress("UNCHECKED_CAST")
        return (source as MutableState<Any>).observe {
            // observe calls back once at once with the committed value at
            // subscription; only commits after that count (see the class KDoc
            // for a commit that overtakes it).
            if (initialFire.compareAndSet(expect = true, update = false)) return@observe
            if (host.isDisposed) {
                dispose()
            } else {
                onSourceChanged(source.owningStore, edge = false)
            }
        }
    }

    /** A source of [sourceStore] changed — a followed store as a whole, when [edge]. */
    private fun onSourceChanged(
        sourceStore: Store<*>,
        edge: Boolean,
    ) {
        while (true) {
            val current = phase.value
            if (current == ARMED) {
                recompute?.let { queue(sourceStore, it, edge) }
                return
            }
            if (current == MISSED || phase.compareAndSet(current, MISSED)) return
        }
    }

    private fun queue(
        sourceStore: Store<*>,
        task: DerivedRecompute<V, *>,
        edge: Boolean,
    ) {
        if (SettleScopes.current()?.enqueue(task) == true) return
        val inItsCommit = sourceStore.activeTransaction?.fanningOutHere() == true
        when {
            inItsCommit -> sourceStore.postCommit(task)
            // A store-level change inside an initializer, migrate or compute
            // with no settle scope open — none such is known: each of those
            // runs in a scope (see the class KDoc). Not a recompute to run in
            // the middle of that code, so a fallback, reported.
            edge && NoWriteRegion.current() != null -> task.handOffLagging(sourceStore)
            else -> host.postCommit(task)
        }
    }

    override fun dispose() {
        if (!released.compareAndSet(expect = false, update = true)) return
        subscriptions.forEach { it.dispose() }
        // Before the edges go: it withdraws itself from their stores' queues too.
        recompute?.dispose()
        followed.releaseAll(this)
    }

    /** Why a store this follower is asked to follow cannot be. */
    private fun disposedSourceStore(store: Store<*>): String =
        "store disposed: ${store.displayName}, a source store of a derived state of ${host.displayName}"

    private companion object {
        const val UNARMED = 0
        const val MISSED = 1
        const val ARMED = 2
    }
}

/**
 * Stage [value] as [backing]'s write in this store's active transaction:
 * the top-level transaction a derived state's recompute opened on its host.
 * The one write a [DerivedState]'s backing accepts; every other one goes
 * through the store's write entrypoints, which refuse it
 * ([refuseDerivedStateWrite]).
 */
internal fun <T : Any> Store<*>.stageRecomputedValue(
    backing: MutableState<T>,
    value: T,
) {
    val txn = checkNotNull(activeTransaction) { "a derived state's recompute runs inside its own transaction" }
    check(txn.stagePendingWrite(backing, backing.beforeSet(value))) {
        "the recompute transaction of ${backing.declaration?.qualifiedName} is closed to writes"
    }
}

/**
 * Throw when [state] is a [DerivedState], or the backing state of one: a
 * write to it (`mutate`, `update`, `bridge`, `observeFrom`) would be
 * overwritten by the next recompute, and its sources would no longer explain
 * its value.
 */
internal fun refuseDerivedStateWrite(state: State<*>) {
    val backing =
        when (state) {
            is DerivedStateNode<*> -> state.backing
            is MutableState<*> -> state.takeIf { it.declaration?.kind == StateKind.ReadOnlyDerived }
            else -> null
        } ?: return
    throw IllegalStateException(
        "Cannot write ${backing.declaration?.qualifiedName}: it is a derived state (derivedState or merged), " +
            "whose value is computed from its sources after each commit that changes one, so it is read-only — " +
            "mutate, update, bridge and observeFrom on it are refused. Write one of its sources instead, or " +
            "declare a state (`val x by state { … }`) for a value you write yourself.",
    )
}
