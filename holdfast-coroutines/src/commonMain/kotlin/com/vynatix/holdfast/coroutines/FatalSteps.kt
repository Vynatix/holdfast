@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FatalHold
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

// The step shapes of issue #61 (core's FatalErrors.kt) for this module's
// sequences whose remaining steps must still run once a fatal error is held:
// a suspending commit's fanout, a hydration decision's aftermath, an
// overlay's clear.

/**
 * Hold [error] when it is fatal (issue #61); else throw it at once — unless
 * a fatal error is held already, which is then thrown in its place, carrying
 * it as suppressed.
 */
internal fun FatalHold.holdOrThrow(error: Throwable) {
    if (!holdIfFatal(error)) {
        rethrowHeld(alongside = error)
        throw error
    }
}

/** Run [step], one step of a sequence: what it throws goes through [holdOrThrow]. */
internal inline fun FatalHold.holdingFatal(step: () -> Unit) {
    runCatching(step).onFailure(::holdOrThrow)
}

/**
 * Report [error] through [store]'s handler, as one step of a sequence: a
 * fatal error the handler throws is held; a non-fatal one ends the sequence
 * early, as documented ([holdOrThrow]).
 */
internal fun FatalHold.reportHoldingFatal(
    store: Store<*>,
    error: Throwable,
) = holdingFatal { store.internalReportUncaughtFailure(error) }
