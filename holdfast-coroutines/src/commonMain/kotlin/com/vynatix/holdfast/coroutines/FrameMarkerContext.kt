@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.Transaction
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Build the [CoroutineContext] that keeps the core thread-local [FrameMarker]
 * slot coherent with a `suspendAtomic` body across coroutine dispatch: the
 * marker is installed on every resume and the previous value restored on
 * every suspend, so the non-suspending `Store.action`/`mutate` entry points
 * can enforce frame enrollment regardless of dispatcher thread hops.
 *
 * Platform split: on JVM/Android this is a `kotlinx.coroutines.ThreadContextElement`
 * (which survives nested `withContext(otherDispatcher)` sections); on iOS and
 * wasmJs — where `ThreadContextElement` is not available — it is a delegating
 * [ContinuationInterceptor] ([SlotBracketingInterceptor], built by
 * [slotBracketingInterceptor] so the dispatcher's timer is kept). The
 * interceptor occupies the context's single interceptor slot, so a nested
 * `withContext(Dispatchers.X)` inside the body REPLACES it there: writes in
 * that section are not policed (a documented enforcement gap on those
 * platforms, not a false positive — the same class of gap as
 * `GlobalScope.launch` escaping the frame).
 *
 * [delegate] is the caller's current interceptor (used only by the
 * interceptor-based actuals; the marker element actual ignores it).
 */
internal expect fun frameMarkerContext(
    marker: FrameMarker,
    delegate: ContinuationInterceptor?,
): CoroutineContext

/**
 * Run [block] — a suspending commit phase — with the core thread-local
 * commit-fanout marker ([FanoutMarkers]) naming [roots] on whatever thread
 * each of its resumptions lands: its observer fanout, bridge publishes, event
 * emits and frame observers. A blocking `action`/`atomic` on one of those
 * stores from inside the commit is then recognised as nested and refused,
 * instead of waiting for the serializer the commit itself holds.
 *
 * Starts [block] on the calling thread without a dispatch, so a commit whose
 * body never suspended still does not yield the thread while it holds the
 * store. Platform split as in [frameMarkerContext]: a `ThreadContextElement`
 * on JVM/Android; [withFanoutMarkerIntercepted] on iOS and wasmJs, with the
 * same gap — a nested `withContext(Dispatchers.X)` inside the commit (in a
 * `SuspendingBridge.publishAwaited`, say) replaces the interceptor, so a
 * blocking call from that section is not recognised and still waits.
 */
internal expect suspend fun <T> withFanoutMarker(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T
