@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.Transaction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * iOS/wasmJs implementation of [withFanoutMarker], where no
 * `ThreadContextElement` exists. See [withSlotIntercepted].
 */
internal suspend fun <T> withFanoutMarkerIntercepted(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T = withSlotIntercepted(roots, FanoutMarkers::install, EmptyCoroutineContext, block)

/**
 * Run [block] with a core thread-local slot holding [value] on every thread
 * it resumes on, where no `ThreadContextElement` exists (iOS/wasmJs): the
 * fanout marker ([withFanoutMarker]) and the settle scope
 * ([withSettleScope]). `withContext` with a bracketing interceptor would
 * dispatch before [block] starts, so [block] runs as an UNDISPATCHED child
 * instead, with [context] added: its first segment runs right here, with
 * [value] installed by hand (through [install], which returns the prior
 * value) around it, and every later resumption goes through
 * [SlotBracketingInterceptor], which installs [value] for that resumption.
 */
internal suspend fun <T, M : Any> withSlotIntercepted(
    value: M,
    install: (M?) -> M?,
    context: CoroutineContext,
    block: suspend () -> T,
): T =
    coroutineScope {
        val interceptor = SlotBracketingInterceptor(coroutineContext[ContinuationInterceptor], value, install)
        val prior = install(value)
        val running =
            try {
                async(context + interceptor, start = CoroutineStart.UNDISPATCHED) { block() }
            } finally {
                install(prior)
            }
        running.await()
    }
