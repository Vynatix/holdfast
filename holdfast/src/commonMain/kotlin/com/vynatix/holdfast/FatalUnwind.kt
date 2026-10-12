@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.isFatal

// The sequence shapes of issue #61 (FatalErrors.kt) for core's commit, frame
// and dispose paths: a step of a sequence that must finish holds a fatal error
// in a FatalHold, the remaining steps run, and the sequence throws it after
// its last step. Outside `Store`, which is at its size limit.

/** Run one [step] of a sequence: a fatal error it throws is held here, any other failure is dropped. */
internal inline fun FatalHold.runIsolated(crossinline step: () -> Unit) {
    runCatching { step() }.onFailure { holdIfFatal(it) }
}

/** Run one [step] of a sequence: a fatal error it throws is held here, any other failure propagates at once. */
internal inline fun FatalHold.runHoldingFatal(crossinline step: () -> Unit) {
    runCatching { step() }.onFailure { if (!holdIfFatal(it)) throw it }
}

/**
 * Call [action] on each element, each call [isolated][runIsolated]: a failure
 * is dropped, except a fatal error, which is thrown once every element has
 * been called.
 */
internal inline fun <T> Iterable<T>.forEachIsolated(crossinline action: (T) -> Unit) {
    val fatal = FatalHold()
    for (element in this) fatal.runIsolated { action(element) }
    fatal.rethrowHeld()
}

/**
 * Run [steps], whose steps hold their fatal errors in the receiver
 * ([runHoldingFatal], [runIsolated]). A non-fatal failure still ends the
 * sequence early and propagates, unless a fatal error is held by then: that
 * one is thrown, carrying it as suppressed.
 */
internal inline fun holdingFatalErrors(crossinline steps: FatalHold.() -> Unit) {
    val fatal = FatalHold()
    val failure = runCatching { fatal.steps() }.exceptionOrNull()
    fatal.rethrowHeld(alongside = failure)
    if (failure != null) throw failure
}

/**
 * Run [cleanup] while [unwinding] is in flight, then throw a fatal error if
 * either is one: [unwinding] first, carrying a fatal error [cleanup] threw as
 * suppressed; else [cleanup]'s, carrying [unwinding]. A non-fatal failure of
 * [cleanup] is dropped. With no fatal error this returns, and the caller goes
 * on handling [unwinding].
 */
internal inline fun cleanUpUnwinding(
    unwinding: Throwable,
    crossinline cleanup: () -> Unit,
) {
    val fatal = FatalHold()
    fatal.holdIfFatal(unwinding)
    fatal.runIsolated(cleanup)
    fatal.rethrowHeld(alongside = unwinding)
}

/**
 * The fatal error among [cause] and [secondary], for a site that RETURNS what
 * its caller throws: [cause] when it is one, carrying a fatal [secondary] as
 * suppressed; else [secondary] when it is one, carrying [cause]; else `null`.
 */
internal fun fatalAmong(
    cause: Throwable,
    secondary: Throwable?,
): Throwable? {
    val fatalSecondary = secondary?.takeIf { it.isFatal() && it !== cause }
    return when {
        cause.isFatal() -> cause.also { if (fatalSecondary != null) cause.addSuppressed(fatalSecondary) }
        fatalSecondary != null -> fatalSecondary.also { fatalSecondary.addSuppressed(cause) }
        else -> null
    }
}
