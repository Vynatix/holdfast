@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.Transaction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.ContinuationInterceptor

/**
 * iOS/wasmJs implementation of [withFanoutMarker], where no
 * `ThreadContextElement` exists. `withContext` with a bracketing interceptor
 * would dispatch before [block] starts, so [block] runs as an UNDISPATCHED
 * child instead: its first segment runs right here, with the marker
 * installed by hand around it, and every later resumption goes through
 * [SlotBracketingInterceptor], which installs the marker for that resumption.
 */
internal suspend fun <T> withFanoutMarkerIntercepted(
    roots: Set<Transaction>,
    block: suspend () -> T,
): T =
    coroutineScope {
        val interceptor =
            SlotBracketingInterceptor(coroutineContext[ContinuationInterceptor], roots, FanoutMarkers::install)
        val prior = FanoutMarkers.install(roots)
        val running =
            try {
                async(interceptor, start = CoroutineStart.UNDISPATCHED) { block() }
            } finally {
                FanoutMarkers.install(prior)
            }
        running.await()
    }
