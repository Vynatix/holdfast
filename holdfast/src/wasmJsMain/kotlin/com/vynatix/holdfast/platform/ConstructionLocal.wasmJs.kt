package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global slot IS the thread-local slot.
private var constructionLocal: Any? = null

internal actual fun currentConstructionLocal(): Any? = constructionLocal

internal actual fun setConstructionLocal(value: Any?) {
    constructionLocal = value
}
