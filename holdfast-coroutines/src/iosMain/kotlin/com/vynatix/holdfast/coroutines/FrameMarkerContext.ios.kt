@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * iOS: `ThreadContextElement` is unavailable, so propagate via the delegating
 * [FrameMarkerInterceptor]. See [frameMarkerContext]'s expect KDoc for the
 * nested-`withContext(dispatcher)` enforcement gap this implies.
 */
internal actual fun frameMarkerContext(
    marker: FrameMarker,
    delegate: ContinuationInterceptor?,
): CoroutineContext = FrameMarkerInterceptor(delegate, marker)
