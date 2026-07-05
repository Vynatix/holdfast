package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global counter IS the thread-local counter.
private var bareInvokeDepth: Int = 0

internal actual fun bareInvokeDepth(): Int = bareInvokeDepth

internal actual fun setBareInvokeDepth(value: Int) {
    bareInvokeDepth = value
}
