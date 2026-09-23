@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Transaction
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * wasmJs: `ThreadContextElement` is unavailable, so propagate via the
 * delegating [SlotBracketingInterceptor]. The bracketing keeps the single-thread
 * global slot scoped to this frame's actual resumptions, so interleaved other
 * coroutines never observe the marker.
 */
internal actual fun frameMarkerContext(
    marker: FrameMarker,
    delegate: ContinuationInterceptor?,
): CoroutineContext = SlotBracketingInterceptor(delegate, marker, FrameMarkers::install)

/** wasmJs: an undispatched child behind the same interceptor. See [withFanoutMarker]. */
internal actual suspend fun <T> withFanoutMarker(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T = withFanoutMarkerIntercepted(roots, block)
