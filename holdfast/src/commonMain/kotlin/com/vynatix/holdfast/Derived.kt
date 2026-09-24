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
 *    idle, the recompute runs once per changed source (coalescing across
 *    stores is not implemented yet). Chains of derived states fan out
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
 *    So a derived — on its sources' store or another — can briefly lag its
 *    sources after the committing call returns, then converges. Read the
 *    sources, or use [computed], when you need the caller's own write.
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
 */
fun <V : Store<V>, T : Any> V.derived(
    vararg sources: State<*>,
    compute: V.() -> T,
): Pair<State<T>, Disposable> {
    val self = this
    val initial = self.compute()
    val name = "__derived_${derivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerDerivedBackingState(name, initial, sources.toList())
    // ONE task per derived: its stable identity is what lets postCommit's
    // identity dedup coalesce several sources firing in one commit into a
    // single recompute.
    val recompute = DerivedRecompute(self, name, backingState, compute)

    val initialFireFlags = BooleanArray(sources.size)
    val subs =
        sources.mapIndexed { idx, src ->
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
 * The recompute task of one [derived] state. It is submitted to the host's
 * post-commit queue by identity, so it must stay a single instance for the
 * derived's lifetime.
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
private class DerivedRecompute<V : Store<V>, T : Any>(
    private val host: V,
    private val id: String,
    private val backing: MutableState<T>,
    private val compute: V.() -> T,
) : () -> Unit {
    private val disposed = atomic(false)

    fun dispose() {
        disposed.value = true
        host.withdrawPostCommit(this)
    }

    override fun invoke() {
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
                onAcquired = { host.withdrawPostCommit(this) },
            ) {
                backing mutate compute()
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
