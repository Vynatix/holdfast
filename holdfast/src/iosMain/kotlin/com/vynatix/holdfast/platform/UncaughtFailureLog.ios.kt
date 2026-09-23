package com.vynatix.holdfast.platform

internal actual fun logUncaughtFailure(
    message: String,
    error: Throwable,
) {
    // One println, so reports from concurrent commits do not interleave.
    runCatching { println("$message\n${error.stackTraceToString()}") }
}
