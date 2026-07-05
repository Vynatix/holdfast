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
 * class CartStore : Store<CartStore>() {
 *     val items by state { emptyList<Line>() }
 *     val total: State<Money> = computed { items.value.sumOf { it.price * it.qty } }
 * }
 * ```
 */
fun <V : Store<V>, T : Any> V.computed(compute: V.() -> T): State<T> {
    internalCheckNotDisposed()
    val self = this
    return object : State<T> {
        override val value: T get() = self.compute()
    }
}

/**
 * Push-recomputed [State]: subscribes to each [source] via [State.effect] and
 * recomputes [compute] inside a fresh [action] on this store on every commit
 * that touches a source. The returned `State<T>` is a real [MutableState]
 * with its own observer fanout — bind it via `effect` like any other state.
 *
 * Pair returns the derived state and a [Disposable] for the upstream
 * subscriptions; dispose to stop recomputation.
 *
 * If a recompute's [compute] throws (or its commit fails), the failure is NOT
 * swallowed: it is routed to [onError] when supplied, otherwise to the store's
 * [Store.uncaughtObserverHandler] (loud by default). The derived value keeps
 * its previous value and recovers on the next source commit.
 *
 * Trade-offs:
 *  - Each source commit triggers a derived commit (extra transaction).
 *    Chains of derived states fan out cost-multiplicatively. Document & batch
 *    upstream when this matters.
 *  - Recompute runs on the source's commit thread. The per-store
 *    transactionLock serializes interleaved writes from multiple threads.
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
 * The result is a [DerivedState]; it destructures into `(state, disposable)`
 * exactly as the former `Pair` return did, so existing call sites are
 * source-compatible.
 */
fun <V : Store<V>, T : Any> V.derived(
    vararg sources: State<*>,
    onError: ((Throwable) -> Unit)? = null,
    compute: V.() -> T,
): DerivedState<T> {
    internalCheckNotDisposed()
    require(sources.isNotEmpty()) {
        "derived requires at least one source state — with no sources it would never recompute. " +
            "Pass the state { } properties this derivation reads."
    }
    val self = this
    val initial = self.compute()
    val name = "__derived_${derivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerInternalState(name, initial)

    val initialFireFlags = BooleanArray(sources.size)
    val subs =
        sources.mapIndexed { idx, src ->
            @Suppress("UNCHECKED_CAST")
            val ms =
                (src as? MutableState<Any>)
                    ?: throw IllegalArgumentException(
                        "derived source must be a `state { }` property — a computed{}/hand-rolled " +
                            "State has no observer fanout, so this derivation could never be notified " +
                            "of its changes. Derive from the underlying `state { }` properties instead.",
                    )
            ms.observe {
                // Skip the initial-fire callback so we don't double-recompute.
                if (!initialFireFlags[idx]) {
                    initialFireFlags[idx] = true
                    return@observe
                }
                // Defer the recompute past the parent's commit fanout. Running the
                // recompute action inline would re-enter the parent's pendingWrites
                // (savepoint merge), but the parent is mid-iteration. postCommit
                // queues the recompute to run as a fresh top-level action immediately
                // after the parent's iteration completes.
                self.postCommit {
                    val result =
                        self action {
                            @Suppress("UNCHECKED_CAST")
                            (backingState as State<T>) mutate self.compute()
                        }
                    // A throwing `compute` (or a commit failure) surfaces as an Error
                    // here — `action` catches it rather than throwing. Don't swallow it:
                    // route to the caller's [onError] if supplied, else to the store's
                    // uncaught-error policy (loud by default). The derived value is
                    // simply left at its previous value and recovers on the next source
                    // commit (F10).
                    if (result is TransactionResult.Error) {
                        val ex = result.exception
                        if (onError != null) onError(ex) else self.reportUncaughtObserverError(ex)
                    }
                }
            }
        }

    val composite =
        Disposable {
            subs.forEach { it.dispose() }
        }
    return DerivedState(backingState, composite)
}

/**
 * The result of [derived]: the observable derived [state] and the [disposable]
 * that tears down its source subscriptions.
 *
 * Destructures into `(state, disposable)` via [component1]/[component2], so it
 * is a drop-in replacement for the `Pair<State<T>, Disposable>` this used to
 * return.
 */
class DerivedState<T : Any>(
    val state: State<T>,
    val disposable: Disposable,
) {
    operator fun component1(): State<T> = state

    operator fun component2(): Disposable = disposable
}

/**
 * Monotonic counter for synthesizing derived backing-state property names.
 * Each call to [derived] produces a unique name like `__derived_42`.
 */
private val derivedCounter = atomic(0L)
