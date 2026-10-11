@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Transaction
import kotlin.coroutines.CoroutineContext

/**
 * wasmJs: `ThreadContextElement` is unavailable, so propagate via the
 * delegating [SlotBracketingInterceptor], starting [block] undispatched
 * ([withSlotIntercepted]). The bracketing keeps the thread-local slot scoped
 * to this frame's actual resumptions, so interleaved other coroutines never
 * observe the marker. See [withFrameMarker]'s expect KDoc for the
 * nested-`withContext(dispatcher)` enforcement gap this implies.
 */
internal actual suspend fun <T> withFrameMarker(
    marker: FrameMarker,
    frame: CoroutineContext,
    block: suspend () -> T,
): T = withSlotIntercepted(marker, FrameMarkers::install, frame, block)

/** wasmJs: an undispatched child behind the same interceptor. See [withFanoutMarker]. */
internal actual suspend fun <T> withFanoutMarker(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T = withFanoutMarkerIntercepted(roots, block)
