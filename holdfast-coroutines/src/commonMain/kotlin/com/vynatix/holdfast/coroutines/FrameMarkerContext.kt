@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
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
 * [ContinuationInterceptor] ([FrameMarkerInterceptor]). The interceptor
 * occupies the context's single interceptor slot, so a nested
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
 * Non-JVM implementation of the frame-marker propagation: a
 * [ContinuationInterceptor] that wraps every intercepted continuation so its
 * `resumeWith` brackets the real resumption with thread-local marker
 * install/restore, then hands the wrapped continuation to [delegate] (the
 * actual dispatcher) for ordinary dispatch.
 */
internal class FrameMarkerInterceptor(
    private val delegate: ContinuationInterceptor?,
    private val marker: FrameMarker,
) : AbstractCoroutineContextElement(ContinuationInterceptor),
    ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        val wrapped = MarkerBracketingContinuation(continuation, marker)
        return delegate?.interceptContinuation(wrapped) ?: wrapped
    }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        // `continuation` is what OUR interceptContinuation returned — i.e. the
        // delegate's wrapper (when a delegate exists), so pass it straight back.
        delegate?.releaseInterceptedContinuation(continuation)
    }
}

/**
 * The bracketing continuation: installs [marker] into the thread-local slot
 * for exactly the duration of one resumption (the coroutine runs inside
 * `delegate.resumeWith`), restoring the previous value when the coroutine
 * suspends again or completes. Single-threaded wasmJs gets the same property:
 * interleaved OTHER coroutines never observe this frame's marker.
 */
private class MarkerBracketingContinuation<T>(
    private val delegate: Continuation<T>,
    private val marker: FrameMarker,
) : Continuation<T> {
    override val context: CoroutineContext get() = delegate.context

    override fun resumeWith(result: Result<T>) {
        val prior = FrameMarkers.install(marker)
        try {
            delegate.resumeWith(result)
        } finally {
            FrameMarkers.install(prior)
        }
    }
}
