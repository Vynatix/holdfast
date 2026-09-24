@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Transaction
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * iOS: `ThreadContextElement` is unavailable, so propagate via the delegating
 * [SlotBracketingInterceptor]. See [frameMarkerContext]'s expect KDoc for the
 * nested-`withContext(dispatcher)` enforcement gap this implies.
 */
internal actual fun frameMarkerContext(
    marker: FrameMarker,
    delegate: ContinuationInterceptor?,
): CoroutineContext = SlotBracketingInterceptor(delegate, marker, FrameMarkers::install)

/** iOS: an undispatched child behind the same interceptor. See [withFanoutMarker]. */
internal actual suspend fun <T> withFanoutMarker(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T = withFanoutMarkerIntercepted(roots, block)
