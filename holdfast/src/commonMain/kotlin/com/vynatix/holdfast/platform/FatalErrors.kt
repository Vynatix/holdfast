package com.vynatix.holdfast.platform

/**
 * Whether this throwable means the runtime itself is failing, so that no
 * catch-all may report, convert or drop it: it must propagate (issue #61).
 *
 * - JVM and Android: a `VirtualMachineError` (`StackOverflowError`,
 *   `OutOfMemoryError`, `InternalError`, `UnknownError`).
 * - iOS and wasmJs: a `kotlin.OutOfMemoryError`. A stack overflow never
 *   reaches Kotlin code there: Kotlin/Native dies on the guard page, and V8
 *   makes it uncatchable by wasm code, so no `catch` or `finally` runs.
 *
 * Never an `AssertionError`, a `NotImplementedError`, a
 * `CancellationException` or any other `Error`. The test is on this throwable
 * only, never on its causes: call it before the site wraps what it caught.
 */
internal expect fun Throwable.isFatal(): Boolean

/**
 * Throw this throwable again if it [isFatal]. The first statement of a
 * catch-all that handles a single wrapped call, before it reports, converts
 * or drops what it caught.
 */
internal fun Throwable.rethrowIfFatal() {
    if (isFatal()) throw this
}

/** This result, after throwing its failure again if that failure [isFatal]. */
internal fun <T> Result<T>.rethrowFatal(): Result<T> {
    exceptionOrNull()?.rethrowIfFatal()
    return this
}
