package com.vynatix.holdfast

import com.vynatix.holdfast.platform.isFatal

// Fatal errors propagate (issue #61). A catch-all in the library must never
// report, convert or drop a throwable that means the runtime itself is failing
// (`platform/FatalErrors.kt`: a `VirtualMachineError` on JVM/Android, a
// `kotlin.OutOfMemoryError` on iOS/wasmJs). Before #61 a swallowed
// StackOverflowError surfaced, at best, as a misleading refusal on the
// thread's next action (#37).
//
// Two shapes:
// - a catch-all around ONE wrapped call (a body, a callback, a codec) throws a
//   fatal error again before its normal handling: `rethrowIfFatal()` /
//   `Result.rethrowFatal()` in core, `internalRethrowIfFatal()` /
//   `internalRethrowFatal()` from companion modules;
// - a catch-all that is ONE STEP of a sequence whose remaining steps must still
//   run for the store to stay consistent (an unwind's error hooks and rollback,
//   a commit's fanout, dispose's teardown steps, a frame's participants, a
//   settle) holds the first fatal error in a [FatalHold] instead of handling
//   it, runs the remaining steps, then throws it.

/** Whether this throwable is fatal and must propagate past every catch-all (issue #61). */
@StoreInternalApi
fun Throwable.internalIsFatal(): Boolean = isFatal()

/**
 * Throw this throwable again if it is fatal (issue #61): the first statement of
 * a companion module's catch-all around a single wrapped call, before it
 * reports, converts or drops what it caught.
 */
@StoreInternalApi
fun Throwable.internalRethrowIfFatal() {
    if (isFatal()) throw this
}

/** This result, after throwing its failure again if that failure is fatal (issue #61). */
@StoreInternalApi
fun <T> Result<T>.internalRethrowFatal(): Result<T> {
    exceptionOrNull()?.internalRethrowIfFatal()
    return this
}

/**
 * Holds the first fatal error (issue #61) one step of a sequence caught, so the
 * remaining steps still run, and throws it once they have: a catch-all inside
 * the sequence calls [holdIfFatal] first and skips its normal handling when it
 * answers `true`; the sequence calls [rethrowHeld] after its last step (inside
 * a `finally` it replaces the throwable in flight). A later fatal error is
 * added to the first as suppressed. One per sequence, used on one thread.
 */
@StoreInternalApi
class FatalHold {
    private var held: Throwable? = null

    /** Whether a fatal error is held. */
    val isHolding: Boolean get() = held != null

    /**
     * Whether [error] is fatal. A fatal [error] is now held (or, when one
     * already is, added to it as suppressed) and the caller must not report,
     * convert or drop it.
     */
    fun holdIfFatal(error: Throwable): Boolean {
        if (!error.isFatal()) return false
        val first = held
        when {
            first == null -> held = error
            // A throwable cannot suppress itself, and the JVM may hand out one
            // preallocated OutOfMemoryError again and again.
            first !== error -> first.addSuppressed(error)
        }
        return true
    }

    /**
     * Throw the held fatal error, if any, with [alongside] (the throwable the
     * sequence was unwinding for, such as a cancellation) added to it as
     * suppressed.
     */
    fun rethrowHeld(alongside: Throwable? = null) {
        val fatal = held ?: return
        if (alongside != null && alongside !== fatal) fatal.addSuppressed(alongside)
        throw fatal
    }
}
