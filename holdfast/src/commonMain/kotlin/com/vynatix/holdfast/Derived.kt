@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.atomicfu.atomic

/**
 * Read-time computed [State]: every read of `value` evaluates [compute].
 * Cheap, stateless, NOT observable — derived value isn't pushed to observers
 * because no upstream subscription exists. Use this for derivations that are
 * cheap to recompute and don't need observer fanout (e.g. a sum in the UI's
 * read path).
 *
 * If you need observers to fire when a source changes, use [derived] instead.
 *
 * Example:
 * ```
 * class CartVault : Store<CartVault>() {
 *     val items by state { emptyList<Line>() }
 *     val total: State<Money> = computed { items.value.sumOf { it.price * it.qty } }
 * }
 * ```
 */
fun <V : Store<V>, T : Any> V.computed(compute: V.() -> T): State<T> {
    val self = this
    return object : State<T> {
        override val value: T get() = self.compute()
    }
}

/**
 * Push-recomputed [State]: subscribes to each [source] via [State.effect] and
 * recomputes [compute] inside a fresh top-level action on this store after
 * every commit that touches a source. The returned `State<T>` is a real
 * [MutableState] with its own observer fanout — bind it via `effect` like any
 * other state.
 *
 * Pair returns the derived state and a [Disposable] for the upstream
 * subscriptions; dispose to stop recomputation (a recompute already queued
 * when you dispose does not commit).
 *
 * Trade-offs:
 *  - Each source commit triggers a derived commit (extra transaction). When
 *    the sources live on this store, one commit recomputes once however many
 *    of them it changed. When they live on another store and this store is
 *    idle, the recompute runs once per changed source (this function does
 *    not coalesce across stores; [derivedState] recomputes once per
 *    outermost entry). Chains of derived states fan out
 *    cost-multiplicatively. Document & batch upstream when this matters.
 *  - The recompute never waits for this store. For sources on this store, it
 *    runs on the committing thread once that commit's fanout has finished and
 *    its locks are released. For a source on another store while this store
 *    is idle, it runs inline on the source's commit thread, inside the
 *    source's observer fanout: under the source store's `transactionLock`,
 *    and before that commit's remaining observers, bridge publishes and
 *    events — so a slow [compute] lengthens the source's commit. If this
 *    store is busy (another action, frame or `suspendAction` holds it, even
 *    one that took it right after the source's commit) the recompute is
 *    handed to that holder and runs on the holder's thread when it releases.
 *    One exception: when the holder, or the committing call for a source on
 *    this store, is an `atomic`/`suspendAtomic` frame nested in another
 *    action or frame on the same thread, and this store is one whose root
 *    that frame opened, the queued recompute runs once the outermost action
 *    or frame on that thread has exited and released every store it took
 *    (its settle), not when the frame itself returns; inside the enclosing
 *    entry the derived still shows its old value. So a derived — on its
 *    sources' store or another — can briefly lag its sources after the
 *    committing call returns, then converges. Read the sources, or use
 *    [computed], when you need the caller's own write.
 *  - A derived with a [StateTag.Secret] state among its [sources] is Secret
 *    too ([State.tags]): its value is withheld wherever a Secret value is. A
 *    Secret state read in [compute] without being listed as a source does
 *    not taint it.
 *  - A throwing [compute] (or a middleware rejecting the recompute) rolls that
 *    recompute back and is reported through this store's
 *    [Store.uncaughtObserverHandler] (logged loudly while no handler is set);
 *    the derived keeps its previous value until the next source commit.
 *
 * Example:
 * ```
 * val (totalState, disposable) = store.derived(items, taxRate) {
 *     items.value.sumOf { it.price * it.qty } * taxRate.value
 * }
 * store { totalState effect { uiTotal.value = this } }
 * // …later:
 * disposable.dispose()
 * ```
 *
 * A source may be any state a store produced, including a [derivedState] or
 * [merged] one; the experimental [derivedState] is the read-only,
 * snapshot-free counterpart of this function.
 *
 * @throws IllegalArgumentException for a [computed] source (or any State no
 *   store produced): it has no commits to follow.
 */
fun <V : Store<V>, T : Any> V.derived(
    vararg sources: State<*>,
    compute: V.() -> T,
): Pair<State<T>, Disposable> {
    val self = this
    // Resolve every source before anything runs or registers, so a refused
    // source (a computed one) leaves no backing state and no subscription.
    val observed = sources.map { it.observableSourceFor("derived") }
    val initial = self.compute()
    val name = "__derived_${derivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerDerivedBackingState(name, initial, sources.toList())
    // ONE task per derived: its stable identity is what lets postCommit's
    // identity dedup coalesce several sources firing in one commit into a
    // single recompute.
    val recompute = DerivedRecompute(self, name, compute) { value -> backingState mutate value }

    val initialFireFlags = BooleanArray(sources.size)
    val subs =
        observed.mapIndexed { idx, src ->
            @Suppress("UNCHECKED_CAST")
            (src as MutableState<Any>).observe {
                // Skip the initial-fire callback so we don't double-recompute.
                if (!initialFireFlags[idx]) {
                    initialFireFlags[idx] = true
                    return@observe
                }
                // Defer the recompute past the parent's commit fanout. Running the
                // recompute action inline would re-enter the parent's pendingWrites
                // (savepoint merge), but the parent is mid-iteration. postCommit
                // queues the recompute (deduplicated) while this store has a
                // transaction active, and runs it right away otherwise — for a
                // source on another store, once per changed source.
                self.postCommit(recompute)
            }
        }

    val composite =
        Disposable {
            subs.forEach { it.dispose() }
            recompute.dispose()
        }
    return backingState to composite
}

/**
 * The recompute task of one [derived] state (or [DerivedState]): runs
 * [compute] and hands the result to [commit], which stages it into the
 * derived's backing state, in a top-level action on [host]. It is submitted to
 * post-commit queues (and a [DerivedState]'s to settle scopes) by identity, so
 * it must stay a single instance for the derived's lifetime. [queuedOn] lists
 * the stores other than [host] whose queues it may have been put on (a
 * [DerivedState]'s, by a source commit outside any entry, or deferring to a
 * holder); it withdraws itself from all of them, and while this thread runs a
 * blocking action or frame on one of them it defers to that holder, so it
 * runs once, after it ends. With [cutSources] (a [DerivedState]'s, its
 * sources), [compute] runs in a [ComputingFrame] and reads the sources from
 * one committed cut ([ComputeReads]): it reads committed values only, so it
 * never commits a value built from another transaction's pending writes — a
 * blocking one it does not defer to, or a `suspendAction` parked on this
 * thread — nor one source after another thread's frame applied and another
 * before; and a write from it throws.
 *
 * It commits through [Store.tryTopLevelAction] and never blocks or spins: a
 * busy host gets the task handed to its post-commit queue, and the host's
 * current holder runs it after releasing. The old recompute ran a blocking
 * `action` on the host, which — for a host other than the source's store —
 * happened inside the source's commit fanout while the source's
 * `transactionLock` was held. A host held with no transaction visible yet
 * (its serializer taken, its action not yet in its body) therefore stalled
 * the source's commit, which deadlocked if that holder then needed the
 * source store; and a host whose serializer was held by a coroutine that
 * needed this very thread spun forever.
 */
internal class DerivedRecompute<V : Store<V>, T : Any>(
    private val host: V,
    private val id: String,
    private val compute: V.() -> T,
    private val queuedOn: List<Store<*>> = emptyList(),
    private val cutSources: List<MutableState<*>>? = null,
    private val commit: V.(T) -> Unit,
) : () -> Unit,
    SettleTask {
    private val disposed = atomic(false)

    /** One more than the deepest derived state among [cutSources]: it settles after every one of them. */
    override val settleRank: Int = settleRankOver(cutSources.orEmpty())

    override fun settle() = invoke()

    /**
     * A feedback loop kept one settle running it: leave it in [host]'s
     * post-commit queue for the host's next holder — an idle host has none
     * yet, so the value may lag its sources until then — and report that
     * (hand-off first, so a throwing handler cannot skip it). Value-free.
     */
    override fun deferPastSettle(report: Boolean) {
        host.handOffPostCommit(this)
        if (report && !host.isDisposed) {
            host.internalReportUncaughtFailure(
                IllegalStateException(
                    "derived state ${host.displayName}.$id recomputed $MAX_SETTLE_RUNS times in one settle — a " +
                        "feedback loop (an observer of it writing one of its sources?); the next recompute is left " +
                        "in ${host.displayName}'s post-commit queue for its next holder, so its value may lag its " +
                        "sources until then",
                ),
            )
        }
    }

    fun dispose() {
        disposed.value = true
        withdrawEverywhere()
    }

    private fun withdrawEverywhere() {
        host.withdrawPostCommit(this)
        queuedOn.forEach { it.withdrawPostCommit(this) }
    }

    override fun invoke() {
        if (disposed.value) return
        // This thread runs a blocking action or frame on a source's store (the
        // recompute runs from another source store's drain, inside it): that
        // action's commit, if it changes a source, queues this recompute
        // again, and a frame's other participants are still to commit. Defer
        // to that holder, which drains after it ends — on commit and on
        // rollback alike (the hand-off invariant in Store.tryTopLevelAction) —
        // so the recompute runs once, never from a torn pair. The compute
        // reads a committed cut (cutSources), so this is about running once,
        // not about what it reads. A recompute a settle scope runs meets no
        // such holder: the scope settles once its entry released everything.
        // A suspending holder is never deferred to: its recorded owner thread
        // is only where it started, and one parked on this thread (Android's
        // main thread, runBlocking, any thread on wasmJs) could hold the
        // recompute back for as long as its body suspends.
        // internalOwnsActiveTransaction is false while one holds the store.
        // postCommit, not a bare hand-off, for its lost-wakeup guard. The host
        // needs no such check: a transaction there makes the attempt below
        // busy, which hands off the same way. Legacy `derived` has no
        // [queuedOn].
        val held = queuedOn.firstOrNull { it.internalOwnsActiveTransaction() }
        if (held != null) {
            held.postCommit(this)
            return
        }
        val first = attempt()
        if (first is TopLevelAttempt.Busy || first is TopLevelAttempt.BusyNoTxn) {
            // Hand off, then retry once: the retry closes the window in which
            // the holder released and drained between our attempt and our
            // enqueue. If the retry is busy too, some holder held the store
            // after the enqueue and drains after it releases — a retry that
            // took part of the store itself and backed out drains too, unless
            // the store has another holder by then (see
            // Store.tryTopLevelAction) — so returning here strands nothing.
            // Guarded by DerivedHandoffTest's "holder that releases between
            // the busy attempt and the hand-off" and "lock-only holder" cases.
            host.handOffPostCommit(this)
            attempt()
        }
    }

    private fun attempt(): TopLevelAttempt {
        if (disposed.value) return TopLevelAttempt.Disposed
        val attempt =
            host.tryTopLevelAction(
                id,
                // Withdraw a queued copy (a hand-off) once the store is ours,
                // before any source is read, so a source commit landing after
                // the read queues a fresh recompute rather than being absorbed
                // by this one. Before the middleware chain, so a middleware
                // rejecting this recompute cannot leave the copy queued for the
                // drain to run (and report) a second time.
                onAcquired = { withdrawEverywhere() },
            ) {
                val sources = cutSources
                val value =
                    if (sources != null) {
                        val name = "${host.displayName}.$id"
                        NoWriteRegion.runCompute(name) { ComputeReads.recompute(sources) { compute() } }
                    } else {
                        compute()
                    }
                commit(value)
            }
        val result = (attempt as? TopLevelAttempt.Ran)?.result
        if (result is TransactionResult.Error && !host.isDisposed) {
            // Previously swallowed by the drain's runCatching, silently freezing
            // the derived. Reported like any other post-commit side-effect failure.
            host.internalReportUncaughtFailure(result.exception)
        }
        return attempt
    }
}

/**
 * Monotonic counter for synthesizing derived backing-state property names.
 * Each call to [derived] produces a unique name like `__derived_42`.
 */
private val derivedCounter = atomic(0L)
