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
 * Suspending counterpart of [com.vynatix.holdfast.derived].
 *
 * Subscribes to each [sources] entry via the same observer machinery used by sync
 * `derived`, but the recompute body is `suspend`. On any source commit, a new
 * compute is **launched on `store.scope`** — the scheduling is enqueued via the
 * store's `postCommit` queue so the launch happens after the parent action's
 * commit fanout, matching `derived`'s deferral pattern. The launched coroutine
 * runs the suspending [compute] and stages the result into a synthetic backing
 * state via an internal [suspendAction]. The synthetic backing state is a real
 * [MutableState] with its own observer fanout — bind it via `effect` like any
 * other state.
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
 *    observer subscriptions AND cancels the most recent launched job. Any
 *    older in-flight launches that race past the dispose call are still
 *    cancelled-or-rolled-back if `store.scope` later cancels; if not, their
 *    commits land harmlessly on a backing state nobody observes anymore.
 *
 * **Errors thrown by [compute]**: surface via the store's middleware error
 * path. The launched `suspendAction` returns a [com.vynatix.holdfast.TransactionResult.Error]
 * whose middleware chain has already seen `onTransactionError`. Since the
 * launch is fire-and-forget on `store.scope`, the error does NOT propagate to
 * any caller — install a middleware that logs/handles errors if you need
 * visibility. (`Throwable`s thrown synchronously from the initial eager
 * compute DO propagate to the caller of [suspendDerived]; that path runs
 * [runBlocking] to seed the backing state.)
 *
 * **Initial value**: seeded synchronously by running [compute] once via
 * [runBlocking] on the calling thread. The same call-site contract as sync
 * `derived` — the returned `State<T>` has a sensible value at construction
 * time. If [compute] suspends on a long-running operation here, the caller
 * blocks; subsequent recomputes are async-launched.
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
    val self = this

    // Seed the backing state synchronously. We use runBlocking on the calling
    // thread to evaluate the suspending compute once at registration. This
    // mirrors sync `derived`'s "initial value at construction" contract; it
    // does mean a long-running initial compute blocks the caller. Subsequent
    // recomputes are async-launched on `store.scope`.
    val initial: T = runBlockingForInitialSeed { self.compute() }
    val name = "__suspendDerived_${suspendDerivedCounter.incrementAndGet()}"
    val backingState: MutableState<T> = self.registerInternalState(name, initial)

    // Holder for the most recent launched job, so dispose() can cancel it.
    // We cancel on dispose to avoid leaking work past the consumer's lifetime;
    // older in-flight jobs (from prior source changes) are children of
    // `store.scope` and ride that scope's cancellation.
    val latestJob = atomic<Job?>(null)
    // Disposed flag: skip scheduling further recomputes after dispose.
    val disposed = atomic(false)

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
                self.postCommit {
                    if (disposed.value) return@postCommit
                    val job =
                        self.scope.launch {
                            // Stage the result via suspendAction. AsyncSerializer in
                            // suspendAction serializes racing recomputes; the LAST
                            // commit wins (see KDoc).
                            self.suspendAction {
                                @Suppress("UNCHECKED_CAST")
                                (backingState as State<T>) mutate self.compute()
                            }
                        }
                    latestJob.value = job
                }
            }
        }

    val composite =
        Disposable {
            if (!disposed.compareAndSet(expect = false, update = true)) return@Disposable
            subs.forEach { runCatching { it.dispose() } }
            latestJob.value?.let { runCatching { it.cancel() } }
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
