@file:OptIn(HoldfastInternalApi::class)

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
 * class CartVault : Holdfast<CartVault>() {
 *     val items by state { emptyList<Line>() }
 *     val total: State<Money> = computed { items.value.sumOf { it.price * it.qty } }
 * }
 * ```
 */
fun <V : Holdfast<V>, T : Any> V.computed(compute: V.() -> T): State<T> {
    val self = this
    return object : State<T> {
        override val value: T get() = self.compute()
    }
}

/**
 * Push-recomputed [State]: subscribes to each [source] via [State.effect] and
 * recomputes [compute] inside a fresh [action] on this vault on every commit
 * that touches a source. The returned `State<T>` is a real [MutableState]
 * with its own observer fanout — bind it via `effect` like any other state.
 *
 * Pair returns the derived state and a [Disposable] for the upstream
 * subscriptions; dispose to stop recomputation.
 *
 * Trade-offs:
 *  - Each source commit triggers a derived commit (extra transaction).
 *    Chains of derived states fan out cost-multiplicatively. Document & batch
 *    upstream when this matters.
 *  - Recompute runs on the source's commit thread. The per-vault
 *    transactionLock serializes interleaved writes from multiple threads.
 *
 * Example:
 * ```
 * val (totalState, disposable) = vault.derived(items, taxRate) {
 *     items.value.sumOf { it.price * it.qty } * taxRate.value
 * }
 * vault { totalState effect { uiTotal.value = this } }
 * // …later:
 * disposable.dispose()
 * ```
 */
fun <V : Holdfast<V>, T : Any> V.derived(vararg sources: State<*>, compute: V.() -> T): Pair<State<T>, Disposable> {
    val self = this
    val initial = self.compute()
    val name = "__derived_${derivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerInternalState(name, initial)

    val initialFireFlags = BooleanArray(sources.size)
    val subs = sources.mapIndexed { idx, src ->
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
            // queues the recompute to run as a fresh top-level action immediately
            // after the parent's iteration completes.
            self.postCommit {
                self action {
                    @Suppress("UNCHECKED_CAST")
                    (backingState as State<T>) mutate self.compute()
                }
            }
        }
    }

    val composite = Disposable {
        subs.forEach { it.dispose() }
    }
    return backingState to composite
}

/**
 * Monotonic counter for synthesizing derived backing-state property names.
 * Each call to [derived] produces a unique name like `__derived_42`.
 */
private val derivedCounter = atomic(0L)
