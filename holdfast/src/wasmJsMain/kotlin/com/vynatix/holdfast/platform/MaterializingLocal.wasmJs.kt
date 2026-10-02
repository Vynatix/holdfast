package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global slot IS the thread-local slot.
private var materializingLocal: Any? = null

internal actual fun currentMaterializingLocal(): Any? = materializingLocal

internal actual fun setMaterializingLocal(value: Any?) {
    materializingLocal = value
}
