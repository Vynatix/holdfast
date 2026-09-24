@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.SettleScope
import com.vynatix.holdfast.SettleScopes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// The settle scope of a suspending entry (issue #20, R9; plan decision D17).
//
// Core settles derived states once per outermost entry on a thread
// (`SettleScope`): an action, a frame, or a recompute's own transaction opens
// a thread-local scope, every commit inside it queues its derived-state
// recomputes there, and the entry runs them once it has released everything.
// A `suspendAction` or `suspendAtomic` is an entry too, but its body — and its
// commit — may resume on any thread. So its scope travels with the coroutine:
// a context element carries it, and the platform propagation below installs
// it in the thread-local slot on every resumption and restores the previous
// value on every suspension, exactly as the frame marker travels
// (FrameMarkerContext.kt). A commit that fans out on another thread than the
// one that opened the entry still queues into the entry's scope, and a
// suspending entry nested in another — or in a blocking action on its thread,
// through `runBlocking` — joins the outer scope.

/**
 * The settle scope a suspending entry runs in, carried in its coroutine
 * context so a nested suspending entry finds and joins it on any thread.
 */
internal class SettleAmbientContext(
    val scope: SettleScope,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SettleAmbientContext>
}

/**
 * Run [block] — a `suspendAction` or `suspendAtomic`, from the point where it
 * starts taking the store — as an entry: inside the open settle scope this
 * coroutine carries, or that a blocking entry on this thread opened (joined,
 * so it does not settle here), or else inside a new scope that settles when
 * [block] has returned or thrown, on whatever thread it ends on, with the
 * scope still installed, so the recomputes it runs join it.
 *
 * Unless it joins the scope this coroutine already carries, it first checks
 * the caller for cancellation, on every platform: called from an
 * already-cancelled coroutine it throws that [CancellationException] before
 * [block] takes anything. [block] then runs inside a `withContext` (on
 * JVM/Android; an undispatched child on iOS and wasmJs), whose child job
 * would turn a caller cancelled while [block] runs into a
 * [CancellationException] even once [block] has returned — after its commit,
 * which ran under `NonCancellable`. So once [block] has returned (or thrown),
 * that outcome is what this call returns (or throws): a caller cancelled
 * after the body returned still gets the committed [TransactionResult], and
 * sees its cancellation at its next suspension point.
 *
 * Read `coroutineContext` (the owner `Job` of a serializer acquire, say)
 * inside [block], so that a suspending entry nested in it — which joins, and
 * reads it there too — sees the same one, and a nested acquire of a mutex this
 * entry holds fails fast instead of waiting for itself.
 */
internal suspend fun <T> settlingSuspended(block: suspend () -> T): T {
    val carried = coroutineContext[SettleAmbientContext]?.scope
    if (carried != null && carried.isOpen) return block()
    // Before taking anything, on every platform: the iOS/wasmJs actual starts
    // [block] as an UNDISPATCHED child, which (unlike withContext) runs even
    // when the caller is already cancelled.
    coroutineContext.ensureActive()
    val joined = SettleScopes.current()
    val scope = joined ?: SettleScopes.open()
    var outcome: Result<T>? = null
    try {
        withSettleScope(scope) {
            outcome =
                try {
                    runCatching { block() }
                } finally {
                    if (joined == null) scope.settle()
                }
        }
    } catch (ce: CancellationException) {
        // The settle-scope child was cancelled with the caller. Before [block]
        // ran there is nothing to keep; after, its outcome stands (a body the
        // cancellation reached threw — and rolled back — on its own).
        if (outcome == null) throw ce
    }
    return checkNotNull(outcome).getOrThrow()
}

/**
 * Run [block] with [scope] carried in its context ([SettleAmbientContext]) and
 * installed as the settle scope of every thread it resumes on.
 *
 * Platform split as in [frameMarkerContext]: a `ThreadContextElement` on
 * JVM/Android; [withSlotIntercepted] on iOS and wasmJs, with the same gap — a
 * nested `withContext(Dispatchers.X)` inside [block] replaces the
 * interceptor, so a commit fanning out in that section does not see the
 * scope: its derived states settle at that commit's own entry instead (once
 * per commit, not once per outer entry).
 */
internal expect suspend fun <T> withSettleScope(
    scope: SettleScope,
    block: suspend () -> T,
): T
