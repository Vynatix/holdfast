@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * JVM/Android: a [ThreadContextElement] keeps the thread-local marker slot
 * coherent across dispatch — and, unlike the non-JVM interceptor fallback,
 * survives nested `withContext(otherDispatcher)` sections (context elements
 * are inherited; the interceptor slot is not).
 */
internal actual fun frameMarkerContext(
    marker: FrameMarker,
    delegate: ContinuationInterceptor?,
): CoroutineContext = FrameMarkerElement(marker)

private class FrameMarkerElement(
    private val marker: FrameMarker,
) : ThreadContextElement<FrameMarker?> {
    override val key: CoroutineContext.Key<FrameMarkerElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): FrameMarker? = FrameMarkers.install(marker)

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: FrameMarker?,
    ) {
        FrameMarkers.install(oldState)
    }

    companion object Key : CoroutineContext.Key<FrameMarkerElement>
}
