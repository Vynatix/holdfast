package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global slot IS the thread-local slot.
private var mintLocal: Any? = null

internal actual fun currentMintLocal(): Any? = mintLocal

internal actual fun setMintLocal(value: Any?) {
    mintLocal = value
}
