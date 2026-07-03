@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * wasmJs: `ThreadContextElement` is unavailable, so propagate via the
 * delegating [FrameMarkerInterceptor]. The bracketing keeps the single-thread
 * global slot scoped to this frame's actual resumptions, so interleaved other
 * coroutines never observe the marker.
 */
internal actual fun frameMarkerContext(
    marker: FrameMarker,
    delegate: ContinuationInterceptor?,
): CoroutineContext = FrameMarkerInterceptor(delegate, marker)
