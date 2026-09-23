@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Transaction
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
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

/**
 * JVM/Android: a [ThreadContextElement] for the fanout-marker slot. The
 * dispatcher does not change, so `withContext` starts [block] undispatched on
 * this thread. See [withFanoutMarker].
 */
internal actual suspend fun <T> withFanoutMarker(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T = withContext(FanoutMarkerElement(roots)) { block() }

private class FanoutMarkerElement(
    private val roots: Set<Transaction>,
) : ThreadContextElement<Set<Transaction>?> {
    override val key: CoroutineContext.Key<FanoutMarkerElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Set<Transaction>? = FanoutMarkers.install(roots)

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Set<Transaction>?,
    ) {
        FanoutMarkers.install(oldState)
    }

    companion object Key : CoroutineContext.Key<FanoutMarkerElement>
}
