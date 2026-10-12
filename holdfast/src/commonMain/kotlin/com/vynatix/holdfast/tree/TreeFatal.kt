@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.FatalHold
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

// Fatal errors propagate (issue #61; `FatalErrors.kt`). The tree's sequences
// whose remaining steps must run for the tree to stay consistent — an
// attach's phases 5 and 6 (`TreeAttach.kt`), a dispose's detach steps
// (`StoreDetach.kt`), a tree value's replay (`TreeValue.kt`), disposing the
// stores of a run or a branch — hold the first fatal error in a `FatalHold`
// and throw it after their last step.

/**
 * Run [step], one step of a sequence whose remaining steps must still run:
 * a fatal error it throws is held in this hold and `null` answered. Any
 * other failure propagates at once, as it would without the hold — unless a
 * fatal error is held already, which is then thrown in its place, carrying
 * it as suppressed.
 */
internal inline fun <T> FatalHold.holdingFatal(step: () -> T): T? =
    runCatching(step).getOrElse { failure ->
        if (!holdIfFatal(failure)) {
            rethrowHeld(alongside = failure)
            throw failure
        }
        null
    }

/**
 * Dispose each of [stores], in order, every one of them even after a
 * `dispose()` threw a fatal error — which is thrown once every store's ran
 * (issue #61; `dispose()` throws nothing else).
 */
internal fun disposeEach(stores: Iterable<Store<*>>) {
    val fatal = FatalHold()
    for (store in stores) fatal.holdingFatal { store.dispose() }
    fatal.rethrowHeld()
}
