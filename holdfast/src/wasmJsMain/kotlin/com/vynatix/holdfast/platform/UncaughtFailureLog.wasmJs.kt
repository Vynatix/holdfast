package com.vynatix.holdfast.platform

internal actual fun logUncaughtFailure(
    message: String,
    error: Throwable,
) {
    // println reaches the browser console (console.log) on wasmJs.
    runCatching { println("$message\n${error.stackTraceToString()}") }
}
