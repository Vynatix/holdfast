package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global slot IS the thread-local slot.
private var frameLocal: Any? = null

internal actual fun currentFrameLocal(): Any? = frameLocal

internal actual fun setFrameLocal(value: Any?) {
    frameLocal = value
}
