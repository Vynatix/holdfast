package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

// The iOS/wasmJs carrier of a core thread-local slot across coroutine
// resumptions — the frame marker (withFrameMarker), the fanout marker
// (withFanoutMarker) and the settle scope (withSettleScope) — where no
// `ThreadContextElement` exists. In its own file: the common
// FrameMarkerContext.kt holds the expects, and a JVM actual file of that name
// already owns the `FrameMarkerContextKt` facade class.

/**
 * Non-JVM implementation of the marker propagation: a
 * [ContinuationInterceptor] that wraps every intercepted continuation so its
 * `resumeWith` brackets the real resumption with a thread-local install of
 * [value] (through [install], which returns the prior value) and a restore,
 * then hands the wrapped continuation to [delegate] (the actual dispatcher)
 * for ordinary dispatch. Serves both the frame marker and the fanout marker.
 * Built through [slotBracketingInterceptor], which keeps the dispatcher's
 * timer.
 */
internal open class SlotBracketingInterceptor<M : Any>(
    private val delegate: ContinuationInterceptor?,
    private val value: M,
    private val install: (M?) -> M?,
) : AbstractCoroutineContextElement(ContinuationInterceptor),
    ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        val wrapped = SlotBracketingContinuation(continuation, value, install)
        return delegate?.interceptContinuation(wrapped) ?: wrapped
    }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        // `continuation` is what OUR interceptContinuation returned — i.e. the
        // delegate's wrapper (when a delegate exists), so pass it straight back.
        delegate?.releaseInterceptedContinuation(continuation)
    }
}

/**
 * The [SlotBracketingInterceptor] carrying [value] over [delegate] — one that
 * also keeps time when [delegate] does. `delay` and `withTimeout` take their
 * timer from the context's `ContinuationInterceptor` ([Delay]), which this
 * interceptor replaces: without forwarding the dispatcher's, every wait
 * inside the entry fell back to kotlinx.coroutines' real-time default — a
 * test dispatcher's virtual clock stood still while a suspending body's
 * `delay` (or the hydration gate's back-off) waited real milliseconds, and
 * under iOS's `Dispatchers.Main` a delay resumed from the default timer
 * thread instead of the main run loop's. A [delegate] without a timer (a
 * plain dispatcher, or none) gets the plain interceptor, and its delays the
 * default timer — as they would without any interceptor.
 */
@OptIn(InternalCoroutinesApi::class)
internal fun <M : Any> slotBracketingInterceptor(
    delegate: ContinuationInterceptor?,
    value: M,
    install: (M?) -> M?,
): ContinuationInterceptor =
    if (delegate is Delay) {
        DelayingSlotBracketingInterceptor(delegate, delegate, value, install)
    } else {
        SlotBracketingInterceptor(delegate, value, install)
    }

/**
 * A [SlotBracketingInterceptor] over a dispatcher that keeps time: forwards
 * the timer's scheduling to [timer] (the same object as its delegate), so a
 * resumption a delay or a timeout schedules comes back through the
 * dispatcher — and through the bracketing, which installs [value] for it.
 * [Delay]'s own `delay(time)` member keeps its default, which schedules
 * through [scheduleResumeAfterDelay] here.
 */
@OptIn(InternalCoroutinesApi::class)
private class DelayingSlotBracketingInterceptor<M : Any>(
    delegate: ContinuationInterceptor,
    private val timer: Delay,
    value: M,
    install: (M?) -> M?,
) : SlotBracketingInterceptor<M>(delegate, value, install),
    Delay {
    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) = timer.scheduleResumeAfterDelay(timeMillis, continuation)

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = timer.invokeOnTimeout(timeMillis, block, context)
}

/**
 * The bracketing continuation: installs [value] into the thread-local slot
 * for exactly the duration of one resumption (the coroutine runs inside
 * `delegate.resumeWith`), restoring the previous value when the coroutine
 * suspends again or completes. Single-threaded wasmJs gets the same property:
 * interleaved OTHER coroutines never observe this coroutine's marker.
 */
private class SlotBracketingContinuation<T, M : Any>(
    private val delegate: Continuation<T>,
    private val value: M,
    private val install: (M?) -> M?,
) : Continuation<T> {
    override val context: CoroutineContext get() = delegate.context

    override fun resumeWith(result: Result<T>) {
        val prior = install(value)
        try {
            delegate.resumeWith(result)
        } finally {
            install(prior)
        }
    }
}
