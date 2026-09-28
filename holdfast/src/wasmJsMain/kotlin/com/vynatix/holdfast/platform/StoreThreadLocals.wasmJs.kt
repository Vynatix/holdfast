package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global slot IS the thread-local slot.
private var initializerLocal: Any? = null

internal actual fun currentInitializerLocal(): Any? = initializerLocal

internal actual fun setInitializerLocal(value: Any?) {
    initializerLocal = value
}
