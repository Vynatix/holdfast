package com.vynatix.holdfast.platform

internal actual fun logUncaughtFailure(
    message: String,
    error: Throwable,
) {
    runCatching {
        val err = System.err
        // One hold across both writes, so reports from concurrent commits on
        // different stores do not interleave line by line.
        synchronized(err) {
            err.println(message)
            error.printStackTrace(err)
        }
    }
}
