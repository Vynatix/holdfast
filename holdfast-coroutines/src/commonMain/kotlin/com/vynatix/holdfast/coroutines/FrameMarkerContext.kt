@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.Transaction
import kotlin.coroutines.CoroutineContext

/**
 * Run [block] — a `suspendAtomic` body — with [frame] added to its context and
 * the core thread-local [FrameMarker] slot holding [marker] on every thread it
 * resumes on: the marker is installed on every resume and the previous value
 * restored on every suspend, so the non-suspending `Store.action`/`mutate`
 * entry points can enforce frame enrollment regardless of dispatcher thread
 * hops.
 *
 * Starts [block] on the calling thread without a dispatch, like
 * [withFanoutMarker]: by now the frame holds every participant's serializer
 * and has installed its roots, so a dispatch here would hand the thread to
 * the coroutines queued before it while the frame holds them — even for a
 * body that never suspends. On one thread (wasmJs, a main-thread dispatcher)
 * a bare `mutate` queued meanwhile joined the frame's root (and rolled back
 * with it), and a blocking `action` queued meanwhile waited for a serializer
 * the parked frame holds.
 *
 * Platform split: on JVM/Android a `kotlinx.coroutines.ThreadContextElement`
 * (which survives nested `withContext(otherDispatcher)` sections; the
 * dispatcher does not change, so `withContext` starts [block] undispatched);
 * on iOS and wasmJs — where `ThreadContextElement` is not available —
 * [withSlotIntercepted], an undispatched child behind a delegating
 * [SlotBracketingInterceptor] (built by [slotBracketingInterceptor] so the
 * dispatcher's timer is kept). The interceptor occupies the context's single
 * interceptor slot, so a nested `withContext(Dispatchers.X)` inside the body
 * REPLACES it there: writes in that section are not policed (a documented
 * enforcement gap on those platforms, not a false positive — the same class
 * of gap as `GlobalScope.launch` escaping the frame).
 */
internal expect suspend fun <T> withFrameMarker(
    marker: FrameMarker,
    frame: CoroutineContext,
    block: suspend () -> T,
): T

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
 * store. Platform split as in [withFrameMarker]: a `ThreadContextElement`
 * on JVM/Android; [withFanoutMarkerIntercepted] on iOS and wasmJs, with the
 * same gap — a nested `withContext(Dispatchers.X)` inside the commit (in a
 * `SuspendingBridge.publishAwaited`, say) replaces the interceptor, so a
 * blocking call from that section is not recognised and still waits.
 */
internal expect suspend fun <T> withFanoutMarker(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T
