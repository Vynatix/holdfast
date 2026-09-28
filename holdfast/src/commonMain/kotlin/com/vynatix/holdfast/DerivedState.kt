@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.reflect.KProperty

// Derived states with a contract and a name (issue #20, R6 and R9; plan
// decisions D14, D15 and D17). `derivedState` and `merged` return a
// DerivedState: a read-only State whose value a recompute commits on its store
// once per entry (action, frame, `suspendAction`, `suspendAtomic`) that
// changes one of its sources, when that entry settles (SettleScope.kt),
// reading its sources from one committed cut (ComputeReads.kt). It runs on the
// machinery `derived` uses — one stable recompute task, identity-deduplicated
// queues, a never-blocking top-level attempt that hands off to a busy store —
// but its backing state is never registered: no snapshot, restore, reset,
// encode or `properties` sees it. The legacy `derived` Pair API stays as it
// is (recomputing once per source commit) until the 0.7.0 triage. Internally
// a derived state can also follow whole stores, added and removed at runtime
// (store-level edges, StoreEdges.kt) — the primitive issue #21's `Root.value`
// builds on.

/**
 * A state computed from other states and kept up to date by the library:
 * what [derivedState] and [merged] return.
 *
 * Read [value] like any [State], and observe it like a declared state: with
 * [effect], `:holdfast-coroutines`' `asFlow`/`asStateFlow`, or
 * `:holdfast-compose`'s `collectAsState`. It can be a source of another
 * [derivedState] (or of a [derived]). It cannot be written: `mutate`,
 * `update`, `bridge` and `observeFrom` on it throw [IllegalStateException].
 *
 * Declare one in a store body with `by`, which yields this DerivedState itself
 * (as `val x by state { … }` yields its State), or assign it with `=`:
 *
 * ```
 * class DraftStore : Store<DraftStore>() {
 *     val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
 *     val server by state(tags = setOf(StateTag.Remote)) { "" }
 *     val shown by merged(draft, server) { d, s -> d.ifEmpty { s } }
 * }
 * ```
 *
 * **Recompute.** Its value is computed once when it is created, reading its
 * sources from one committed cut taken just before — except where the calling
 * thread holds a pending write, which it reads as any read there does: created
 * inside an action (or frame) that has written a state its compute reads, it
 * sees that write, and is recomputed from committed values once the outermost
 * action or frame on that thread has ended, whether it commits or rolls back.
 * Then it settles after every entry that changes a source: once the outermost
 * `action`, `atomic` frame, `suspendAction` or `suspendAtomic` on the
 * committing thread has exited and released everything it took, it recomputes
 * once — however many of its sources that entry changed, on however many
 * stores, in however many nested actions or frame participants — in a
 * transaction of its own on the store it was created on (its host): the
 * host's middleware sees that transaction, and its observers fire in the
 * normal commit order — only when the value changes (`==`), as for a
 * `distinct` state. A chain of derived states settles in the same pass, each
 * after the derived states it reads. The recompute never waits for the host:
 * when another action, frame or `suspendAction` holds the host, the recompute
 * is handed to that holder, and runs when it releases. A recompute reads its
 * sources from one committed cut — never another thread's pending writes or
 * the pending writes of an action on its thread, which may still roll back,
 * and never one participant of another thread's frame applied and another not
 * yet — and a state its compute reads without listing it as a source at its
 * committed value; its compute may not write: a write, action, `atomic`,
 * `reset`, `restore` or `emit` from it throws. A `suspendAction` or
 * `suspendAtomic` holding a source's store never holds it back, even one
 * parked on its thread (Android's main thread, `runBlocking`, any thread on
 * wasmJs): it recomputes from committed values, and again after that action's
 * commit. A source value that arrives outside any entry — through `bridge` or
 * `observeFrom` — recomputes it at once on an idle host. So after the
 * committing call returns, the value can briefly lag its sources, and inside
 * an action it reflects none of that action's writes; read the sources, or a
 * [computed] state, when you need the caller's own write. A throwing compute
 * (or a middleware rejecting the recompute) rolls that recompute back and is
 * reported through the host's [Store.uncaughtObserverHandler]; the value
 * stays as it was until the next source commit. A feedback loop through its
 * observers — one writing a source of it — is cut after 1,000 recomputes in
 * one settle and reported through the host's [Store.uncaughtObserverHandler];
 * the next recompute waits in the host's post-commit queue, so the value may
 * lag its sources until the host is next used or a source changes again.
 *
 * **Not store state.** Its value lives in memory only, computed from its
 * sources: [snapshot] does not capture it, `restore`, [reset] and
 * `encode()` never write it (it recomputes from the sources they write), and
 * [Store.properties] and `taggedStates` do not list it. [State.tags] gives it
 * [StateTag.Secret] when one of its sources is Secret, and never
 * [StateTag.UserAuthored] or [StateTag.Remote].
 *
 * [dispose] stops recomputation and releases the source subscriptions (a
 * recompute already queued does not commit); the value stays readable, frozen
 * at its last recompute, and existing observers stay attached but see no
 * further change. Disposing the host store stops recomputation too, and the
 * next source commit releases the subscriptions to sources on other stores;
 * disposing twice is safe.
 *
 * Experimental (issue #20, R6).
 */
@ExperimentalStoreApi
sealed interface DerivedState<T : Any> :
    State<T>,
    Disposable {
    /**
     * `val x by derivedState(…) { … }`: returns this DerivedState itself, so
     * the property's type is `DerivedState<T>` and every read of it returns
     * this same instance.
     */
    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): DerivedState<T> = this
}

/**
 * A [DerivedState] of this store, computed by [compute] from [sources] and
 * recomputed after each action, frame, `suspendAction` or `suspendAtomic`
 * that changes one of them: once, when the outermost one on the committing
 * thread has exited, however many sources it changed (see [DerivedState] for
 * when and where the recompute runs).
 *
 * ```
 * class CartStore : Store<CartStore>() {
 *     val items by state { emptyList<Int>() }
 *     val taxRate by state { 1.0 }
 *     val total by derivedState(items, taxRate) { items.value.sum() * taxRate.value }
 * }
 * ```
 *
 * [sources] are what it recomputes on: states of any store — declared states,
 * [derived] states, and other derived states — but not a [computed] one, which
 * has no commits to follow. [compute] reads the states it needs; a state it
 * reads without listing it as a source does not trigger a recompute, and does
 * not taint it [StateTag.Secret]. [compute] runs once here, on the calling
 * thread, for the initial value.
 *
 * Experimental (issue #20, R6).
 *
 * @throws IllegalStateException if this store, or the store of one of
 *   [sources], is disposed.
 * @throws IllegalArgumentException if [sources] is empty or holds a [computed]
 *   state (or any State no store produced).
 */
@ExperimentalStoreApi
fun <V : Store<V>, T : Any> V.derivedState(
    vararg sources: State<*>,
    compute: V.() -> T,
): DerivedState<T> {
    checkNotDisposed()
    require(sources.isNotEmpty()) {
        "derivedState on $displayName needs at least one source: it recomputes after a commit that changes a " +
            "source, so with none it would never change. Use a state, or computed { }, for a value without sources."
    }
    val backings = sources.map { observedSource(it, "derivedState") }
    val name = "derivedState(${backings.joinToString { it.sourceName(this) }})"
    return createDerivedState(this, name, backings, compute = compute)
}

/**
 * A [DerivedState] merging [local] and [remote], two declared states of this
 * store, through [merge]; recomputed once per outermost action (or frame) that
 * changes either of them (see [DerivedState]).
 *
 * It names the split between what the user writes and what sync writes:
 * [local] holds the user's side (tag it [StateTag.UserAuthored]), [remote]
 * the synced side (tag it [StateTag.Remote]), and adopting a fetch writes
 * [remote] only — so an adoption can never clobber what the user wrote, and
 * the merged value follows both. Adopting several writes to [remote] in one
 * action recomputes the merged value once.
 *
 * ```
 * class DraftStore : Store<DraftStore>() {
 *     val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
 *     val server by state(tags = setOf(StateTag.Remote)) { "" }
 *     val shown by merged(draft, server) { d, s -> d.ifEmpty { s } }
 * }
 * ```
 *
 * Those tags are advice: `merged` does not check them, so inputs tagged
 * otherwise, or untagged, are merged all the same. The merged state carries
 * neither tag (a merge of user-authored and synced data is neither), and is
 * [StateTag.Secret] when [local] or [remote] is.
 * [merge] runs once here, on the calling thread, for the initial value.
 *
 * Experimental (issue #20, R6).
 *
 * @throws IllegalStateException if this store is disposed.
 * @throws IllegalArgumentException unless [local] and [remote] are two
 *   different states declared on this store (`val x by state { … }`) — not
 *   another store's, not a [derived], derived or [computed] state.
 */
@ExperimentalStoreApi
fun <V : Store<V>, L : Any, R : Any, T : Any> V.merged(
    local: State<L>,
    remote: State<R>,
    merge: (L, R) -> T,
): DerivedState<T> {
    checkNotDisposed()
    val localState = mergeInput(local, "local")
    val remoteState = mergeInput(remote, "remote")
    require(localState !== remoteState) {
        "merged on $displayName got ${localState.sourceName()} as both its local and its remote input: it merges " +
            "two different states, the user's side (local) and sync's side (remote)."
    }
    val name = "merged(${localState.sourceName()}, ${remoteState.sourceName()})"
    return createDerivedState(this, name, listOf(localState, remoteState)) { merge(local.value, remote.value) }
}

/**
 * The [MutableState] that holds this state's committed value and fires its
 * observers: a [MutableState] itself, a [DerivedState]'s backing state, or
 * `null` for a State nothing can observe (a [computed] state, or any State no
 * store produced). Every observation path — [effect], `derived` and
 * [derivedState] sources, `:holdfast-coroutines`' flows and `suspendDerived`,
 * `:holdfast-testing`'s timeline lookups — resolves a State through this, so a
 * [DerivedState] observes like a declared state.
 *
 * A DerivedState's backing is read-only for everyone but its recompute: a
 * write to it through any store throws.
 */
@StoreInternalApi
fun <T : Any> State<T>.observableBacking(): MutableState<T>? {
    @Suppress("UNCHECKED_CAST")
    return when (this) {
        is MutableState<*> -> this as MutableState<T>
        is DerivedStateNode<*> -> backing as MutableState<T>
        else -> null
    }
}

/**
 * The one [DerivedState] implementation: a read-only view of [backing], an
 * unregistered `distinct` [MutableState] of the host store that only the
 * derived state's recompute writes ([stageRecomputedValue]). [follower]
 * follows its sources; disposing the node releases it and the recompute.
 *
 * It can also follow whole stores — store-level edges (StoreEdges.kt), for
 * issue #21's `Root.value` over branches that attach and detach at runtime:
 * [addSourceStore] and [removeSourceStore], from any thread.
 */
internal class DerivedStateNode<T : Any>(
    val backing: MutableState<T>,
    private val follower: SourceFollower<*>,
) : DerivedState<T> {
    override val value: T
        get() = backing.value

    /** The stores this node follows as a whole, in the order added; a disposed one is dropped. */
    val sourceStores: List<Store<*>>
        get() = follower.sourceStores

    /**
     * Recompute after every change of [store] from now on — every commit that
     * changes a state a capture of it holds or evicts a keyed entry, every
     * inbound bridge write, every keyed entry that comes to life, every
     * state `removeState`/`clearStates` drops — settling once per outermost
     * entry like any source change (one noticed inside a state initializer,
     * a `migrate` or a compute settles once that code has returned), until
     * [removeSourceStore], the node's [dispose], or [store]'s `dispose()`
     * (which drops the edge and recomputes once more). Queues one recompute
     * now, which reads [store] after the edge exists, so a commit racing this
     * call is never missed (see StoreEdges.kt). `false`, changing nothing,
     * when [store] is followed already or the node (or its host) is disposed.
     *
     * Call it once [store] is constructed, holding no per-state lock (not
     * from a `Bridge.publish`): it attaches to [store]'s attachment slot, and
     * outside any entry its recompute can run at once, reading [store].
     *
     * @throws IllegalStateException if [store] is disposed.
     */
    fun addSourceStore(store: Store<*>): Boolean = follower.addSourceStore(store)

    /**
     * Stop recomputing on [store]'s changes, and queue one recompute, so the
     * value stops covering it. A commit of [store] already fanning out may
     * still recompute the node once more; none after this returns does.
     * `false`, changing nothing, when [store] was not followed.
     */
    fun removeSourceStore(store: Store<*>): Boolean = follower.removeSourceStore(store)

    override fun dispose() {
        follower.dispose()
    }

    /** Names the derived state — `DerivedState(CartStore.derivedState(items))` — never its value. */
    override fun toString(): String = "DerivedState(${backing.declaration?.qualifiedName})"
}

/**
 * A [DerivedState] of this store named [name], computed by [compute] and
 * recomputed after every change of the stores it follows as a whole — none
 * at first but [sourceStores], then whatever [DerivedStateNode.addSourceStore]
 * adds — and of its state [sources], if any (see StoreEdges.kt). What
 * [compute] reads of a followed store it reads from committed values; for
 * one consistent cut across stores it reads through [captureConsistent].
 * Otherwise a [derivedState]: [compute] runs once here, on the calling
 * thread, for the initial value, after the edges to [sourceStores] exist.
 *
 * A derived state's own commit (`derivedState`/`merged`) is no change of its
 * store, and an edge orders nothing in a settle: a derived state of a
 * followed store that [compute] reads belongs in [sources], which recomputes
 * this one on its commits and settles it after it (and taints it
 * [StateTag.Secret] when it is). Read without being listed, it lags until
 * that store's next change. A branch attached at runtime, whose derived
 * states cannot be listed up front, is read through the states under them.
 *
 * For issue #21's `Root.value`, whose branches attach and detach at runtime.
 *
 * @throws IllegalStateException if this store, one of [sourceStores] or the
 *   store of one of [sources] is disposed.
 * @throws IllegalArgumentException if one of [sources] is a [computed] state
 *   (or any State no store produced).
 */
internal fun <V : Store<V>, T : Any> V.derivedStateOverStores(
    name: String,
    sourceStores: List<Store<*>> = emptyList(),
    sources: List<State<*>> = emptyList(),
    compute: V.() -> T,
): DerivedStateNode<T> {
    checkNotDisposed()
    val backings = sources.map { observedSource(it, "derivedStateOverStores") }
    return createDerivedState(this, name, backings, sourceStores, compute)
}

/**
 * Build a [DerivedState] of [host] named [name]: follow [sources] (and
 * [sourceStores] as whole stores), compute its initial value, create its
 * unregistered backing, then arm the follower with its recompute.
 *
 * The follower subscribes BEFORE the initial compute, so a source commit
 * landing while the compute runs is recomputed once armed rather than lost
 * (see [SourceFollower]). The compute reads [sources] from one committed cut
 * ([ComputeReads.initial]) where this thread holds no pending write for them;
 * when it read anything uncommitted — a pending write of any state, on any
 * store, which can still roll back — arming queues one recompute, which reads
 * a committed cut: into this thread's settle scope, so it runs once the entry
 * holding that write has settled.
 */
private fun <V : Store<V>, T : Any> createDerivedState(
    host: V,
    name: String,
    sources: List<MutableState<*>>,
    sourceStores: List<Store<*>> = emptyList(),
    compute: V.() -> T,
): DerivedStateNode<T> {
    val follower = followSources(host, sources)
    // A throwing compute (or a disposed source store) throws to the caller
    // with nothing left subscribed or attached.
    var computed = false
    val (initial, readUncommitted) =
        try {
            // Edges before the compute, like the subscriptions: a change
            // landing in between queues a catch-up once armed.
            sourceStores.forEach { follower.addSourceStore(it, catchUp = false) }
            ComputeReads.initial(sources) { host.compute() }.also { computed = true }
        } finally {
            if (!computed) follower.dispose()
        }
    val backing = MutableState(initial, transformer = null, owningStore = host, distinct = true)
    backing.declaration =
        StateDeclaration(
            store = host,
            name = name,
            kind = StateKind.ReadOnlyDerived,
            initializer = { initial },
            transformer = null,
            distinct = true,
            codec = null,
            property = null,
            local = false,
            sources = sources,
            tags = derivedTags(sources),
        )
    // With no settle scope open, a source commit fanning out on another store
    // queues the recompute on that store's post-commit queue, so the recompute
    // withdraws itself from all of them — the followed stores' too, which
    // change as edges come and go.
    val followed = follower.followed
    val recompute =
        DerivedRecompute(host, name, compute, queuedOn = { followed.queuedOn }, cutSources = sources) { value ->
            stageRecomputedValue(backing, value)
        }
    follower.arm(recompute, catchUp = readUncommitted)
    return DerivedStateNode(backing, follower)
}
