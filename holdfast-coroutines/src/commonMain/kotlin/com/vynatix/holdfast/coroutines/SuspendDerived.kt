@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.platform.runBlockingForInitialSeed
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Suspending counterpart of [com.vynatix.holdfast.derived], seeded with an
 * explicit [initial] value and computed asynchronously — **the recommended
 * overload**, and the only one that compiles-and-runs everywhere (including
 * wasmJs and single-threaded dispatchers).
 *
 * Subscribes to each [sources] entry via the same observer machinery used by sync
 * `derived`, but the recompute body is `suspend`. The backing state starts at
 * [initial]; a first [compute] is launched immediately on `store.scope` so the
 * seed is replaced by the computed value as soon as it resolves. On any
 * subsequent source commit, a new compute is **launched on `store.scope`** — the
 * scheduling is enqueued via the store's `postCommit` queue so the launch
 * happens after the parent action's commit fanout, matching `derived`'s
 * deferral pattern. The launched coroutine runs the suspending [compute] and
 * stages the result into a synthetic backing state via an internal
 * [suspendAction]. The synthetic backing state is a real [MutableState] with its
 * own observer fanout — bind it via `effect` like any other state.
 *
 * Unlike the seedless overload, **no `runBlocking` is involved** — nothing
 * blocks the calling thread and there is no wasmJs / single-thread hazard.
 *
 * **"Later result wins" semantics under rapid source changes**: each source
 * change triggers a new launched compute. Multiple in-flight computes race;
 * the LAST [suspendAction] commit becomes the visible value. There is no
 * coalescing or in-flight cancellation between iterations — racy commits
 * serialize through the store's [Store.AsyncSerializer], and the standard
 * staged-write semantics make the final committed value the visible one.
 * Callers who need strict latest-wins semantics with no intermediate flicker
 * should debounce upstream.
 *
 * **Cancellation**:
 *  - Cancelling [Store.scope] cancels every in-flight compute (the launched
 *    coroutines are children of `store.scope`); no stale results land because
 *    a `suspendAction` whose body is cancelled rolls back instead of committing.
 *  - Calling [Disposable.dispose] on the returned handle disposes the source
 *    observer subscriptions, cancels the most recent launched job, AND
 *    unregisters the synthetic backing state (via `removeState`) so it does not
 *    leak in the store's registry. Unregistration is best-effort: if the store
 *    is already disposed, or a racing recompute still holds pending writes,
 *    `removeState` throws and the (rare, single-entry) leak is left in place.
 *
 * **Errors thrown by [compute]**: surface via the store's middleware error
 * path. The launched `suspendAction` returns a [com.vynatix.holdfast.TransactionResult.Error]
 * whose middleware chain has already seen `onTransactionError`. Since the
 * launch is fire-and-forget on `store.scope`, the error does NOT propagate to
 * any caller — install a middleware that logs/handles errors if you need
 * visibility.
 *
 * Example:
 * ```
 * val (fullName, dispose) = store.suspendDerived(firstName, lastName, initial = "") {
 *     delay(50)                       // network call, db lookup, etc.
 *     "${firstName.value} ${lastName.value}"
 * }
 * ```
 *
 * @param sources States whose commits trigger a recompute.
 * @param initial Value the derived state holds until the first [compute] lands.
 * @param compute Suspending recompute body.
 */
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    initial: T,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable> {
    internalCheckNotDisposed()
    val self = this
    val name = "__suspendDerived_${suspendDerivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerInternalState(name, initial)
    return self.wireSuspendDerived(sources, name, backingState, launchInitialCompute = true, compute)
}

/**
 * Suspending counterpart of [com.vynatix.holdfast.derived] that seeds the
 * derived state by running [compute] **once synchronously via `runBlocking`** on
 * the calling thread, so the returned `State<T>` already holds the computed
 * value at construction time (the same call-site contract as sync `derived`).
 *
 * **Prefer the [initial]-seeded overload.** This seedless form is retained for
 * the "value ready at construction" contract, but it has two sharp edges the
 * other overload does not:
 *  - **It crashes on wasmJs.** The JS event loop cannot block, so the
 *    `runBlocking`-based seed throws `UnsupportedOperationException` there.
 *  - **It can deadlock on single-threaded dispatchers.** If [compute] suspends
 *    on work that needs the *same* thread the `runBlocking` is parked on, the
 *    seed never completes.
 * If [compute] suspends on a long-running operation, the caller also simply
 * blocks until it finishes. Subsequent recomputes are async-launched on
 * `store.scope` and share all the cancellation / later-wins / error / dispose
 * semantics documented on the [initial]-seeded overload.
 *
 * `Throwable`s thrown synchronously from the eager seed compute propagate to the
 * caller of `suspendDerived`.
 *
 * Example:
 * ```
 * val (fullName, dispose) = store.suspendDerived(firstName, lastName) {
 *     delay(50)                       // network call, db lookup, etc.
 *     "${firstName.value} ${lastName.value}"
 * }
 * ```
 */
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable> {
    internalCheckNotDisposed()
    val self = this

    // Seed the backing state synchronously. We use runBlocking on the calling
    // thread to evaluate the suspending compute once at registration. This
    // mirrors sync `derived`'s "initial value at construction" contract; it does
    // mean a long-running initial compute blocks the caller — and it is
    // unavailable on wasmJs / risky on single-threaded dispatchers. Subsequent
    // recomputes are async-launched on `store.scope`.
    val initial: T = runBlockingForInitialSeed { self.compute() }
    val name = "__suspendDerived_${suspendDerivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerInternalState(name, initial)
    return self.wireSuspendDerived(sources, name, backingState, launchInitialCompute = false, compute)
}

/**
 * Shared wiring for both [suspendDerived] overloads: subscribe to [sources],
 * schedule async recomputes on source commits, and build the composite
 * [Disposable] that tears everything down (subscriptions, latest job, and the
 * synthetic backing state named [name]).
 *
 * @param launchInitialCompute when `true` (the [initial]-seeded overload),
 *   launch one recompute immediately so the seed is replaced by the computed
 *   value without waiting for a source change. The `runBlocking`-seeded overload
 *   passes `false` — its backing state already holds the computed value.
 */
private fun <V : Store<V>, T : Any> V.wireSuspendDerived(
    sources: Array<out State<*>>,
    name: String,
    backingState: MutableState<T>,
    launchInitialCompute: Boolean,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable> {
    val self = this

    // Holder for the most recent launched job, so dispose() can cancel it.
    // We cancel on dispose to avoid leaking work past the consumer's lifetime;
    // older in-flight jobs (from prior source changes) are children of
    // `store.scope` and ride that scope's cancellation.
    val latestJob = atomic<Job?>(null)
    // Disposed flag: skip scheduling further recomputes after dispose.
    val disposed = atomic(false)

    fun launchRecompute() {
        if (disposed.value) return
        val job =
            self.scope.launch {
                // Stage the result via suspendAction. AsyncSerializer in
                // suspendAction serializes racing recomputes; the LAST commit
                // wins (see KDoc).
                self.suspendAction {
                    @Suppress("UNCHECKED_CAST")
                    (backingState as State<T>) mutate self.compute()
                }
            }
        latestJob.value = job
    }

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
                if (disposed.value) return@observe
                // Defer scheduling the launch past the parent's commit fanout, same
                // as sync `derived`. `postCommit` ensures the launch is queued
                // outside any pendingWrites iteration. The launch itself is async
                // by definition (store.scope.launch), so the actual recompute runs
                // on the scope's dispatcher.
                self.postCommit { launchRecompute() }
            }
        }

    // Seed-and-compute overload: kick off the first compute now so the caller's
    // `initial` is replaced by the computed value without a source change.
    if (launchInitialCompute) launchRecompute()

    val composite =
        Disposable {
            if (!disposed.compareAndSet(expect = false, update = true)) return@Disposable
            subs.forEach { runCatching { it.dispose() } }
            latestJob.value?.let { runCatching { it.cancel() } }
            // Unregister the synthetic backing state so it doesn't leak in the
            // registry. May throw if the store is disposed or a racing recompute
            // holds pending writes — swallow (rare, bounded one-entry leak).
            runCatching { self.removeState(name) }
        }
    return backingState to composite
}

/**
 * Monotonic counter for synthesizing suspendDerived backing-state property
 * names. Each call to [suspendDerived] produces a unique name like
 * `__suspendDerived_42`. Distinct from sync `derived`'s counter so the two
 * never collide on the same store.
 */
private val suspendDerivedCounter = atomic(0L)
