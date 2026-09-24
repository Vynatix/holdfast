@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.reflect.KProperty

// Derived states with a contract and a name (issue #20, R6; plan decisions D14
// and D15). `derivedState` and `merged` return a DerivedState: a read-only
// State whose value a recompute commits on its store after each commit that
// changes one of its sources. It runs on the machinery `derived` uses — one
// stable recompute task, identity-deduplicated post-commit queues, a
// never-blocking top-level attempt that hands off to a busy store — but its
// backing state is never registered: no snapshot, restore, reset, encode or
// `properties` sees it. The legacy `derived` Pair API stays as it is until the
// 0.7.0 triage.

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
 * **Recompute.** Its value is computed once when it is created — reading its
 * sources as any read on the calling thread does, so created inside an
 * action (or frame) on its host or on a source's store it sees that action's
 * pending writes, and is recomputed from committed values once that action
 * ends, whether it commits or rolls back — then again after every commit
 * that changes a source, in a transaction of its own on the store it was
 * created on (its host): the host's middleware sees that transaction, and
 * its observers fire in the normal commit order — only when the value
 * changes (`==`), as for a `distinct` state. However many of its sources one
 * commit changes, that commit recomputes it once, including sources on
 * another store. The recompute runs once the commit that changed a source
 * has fanned out and released its store, and never waits for the host: when
 * another action, frame or `suspendAction` holds the host, the recompute is
 * handed to that holder, and runs when it releases. A recompute reads
 * committed values only — never the pending writes of an action on its
 * thread, which may still roll back — and its compute may not write: a
 * write, action, `atomic`, `reset`, `restore` or `emit` from it throws. When
 * the thread it runs on is running a blocking action or `atomic` frame on a
 * source's store (a source commit on one store nested in an action on
 * another), it runs once that action ends instead, so it runs once. A
 * `suspendAction` or `suspendAtomic` holding a source's store never holds it
 * back, even one parked on its thread (Android's main thread, `runBlocking`,
 * any thread on wasmJs): it recomputes at once from committed values, and
 * again after that action's commit. A source value that arrives outside a
 * commit — through `bridge` or `observeFrom` — recomputes it at once on an
 * idle host. So after the committing call returns, the value can briefly
 * lag its sources; read the sources, or a [computed] state, when you need
 * the caller's own write. A throwing compute (or a middleware rejecting the
 * recompute) rolls that recompute back and is reported through the host's
 * [Store.uncaughtObserverHandler]; the value stays as it was until the next
 * source commit.
 *
 * **Known gaps, until frames settle derivations from a committed cut (issue
 * #20, R9).** A derived state whose sources live on more than one store can
 * be computed from a torn pair: a recompute that races another thread's
 * `atomic(...)` frame over its sources can read one participant's committed
 * values and the other's previous ones; that frame's own recompute then
 * corrects it. And the initial compute reads a state it does not list as a
 * source the way any read on its thread does: created on a thread holding an
 * action on that state's store, it reads the action's uncommitted writes,
 * and if that action rolls back, the value stays as computed until the next
 * commit that changes a source.
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
 * recomputed after each commit that changes one of them: once per commit,
 * however many sources it changes (see [DerivedState] for when and where the
 * recompute runs).
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
    return createDerivedState(this, name, backings, compute)
}

/**
 * A [DerivedState] merging [local] and [remote], two declared states of this
 * store, through [merge]; recomputed once per commit that changes either of
 * them (see [DerivedState]).
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
 * derived state's recompute writes ([stageRecomputedValue]). [release] stops
 * following the sources and disposes the recompute.
 */
internal class DerivedStateNode<T : Any>(
    val backing: MutableState<T>,
    private val release: Disposable,
) : DerivedState<T> {
    override val value: T
        get() = backing.value

    override fun dispose() {
        release.dispose()
    }

    /** Names the derived state — `DerivedState(CartStore.derivedState(items))` — never its value. */
    override fun toString(): String = "DerivedState(${backing.declaration?.qualifiedName})"
}

/**
 * Build a [DerivedState] of [host] named [name]: follow [sources], compute its
 * initial value, create its unregistered backing, then arm the follower with
 * its recompute.
 *
 * The follower subscribes BEFORE the initial compute, so a source commit
 * landing while the compute runs is recomputed once armed rather than lost
 * (see [SourceFollower]). And when this thread holds a transaction on the
 * host or on a source's store, the compute may have read that transaction's
 * pending writes, which can still roll back: arming then queues one recompute,
 * which reads committed values. It runs once that transaction ends (the host
 * hands it to its holder; a blocking holder of a source's store is deferred
 * to), except under a `suspendAction` or `suspendAtomic` on a source's store
 * other than the host, parked or running: then it runs at once.
 */
private fun <V : Store<V>, T : Any> createDerivedState(
    host: V,
    name: String,
    sources: List<MutableState<*>>,
    compute: V.() -> T,
): DerivedState<T> {
    val follower = followSources(host, sources)
    // A throwing compute throws to the caller with nothing left subscribed.
    var computed = false
    val initial =
        try {
            host.compute().also { computed = true }
        } finally {
            if (!computed) follower.dispose()
        }
    val readOwnPendingWrites = (sources.map { it.owningStore } + host).any { it.heldByThisThread() }
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
    // A source commit fanning out on another store queues the recompute on
    // that store's post-commit queue, so the recompute withdraws itself from
    // all of them.
    val queuedOn = sources.map { it.owningStore }.filter { it !== host }.distinct()
    val recompute =
        DerivedRecompute(host, name, compute, queuedOn, committedReads = true) { value ->
            stageRecomputedValue(backing, value)
        }
    follower.arm(recompute, catchUp = readOwnPendingWrites)
    return DerivedStateNode(backing, follower)
}
