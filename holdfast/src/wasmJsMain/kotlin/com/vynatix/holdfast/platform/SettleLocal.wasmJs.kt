package com.vynatix.holdfast.platform

// wasmJs is single-threaded by assumption (currentThreadId() == 0 for every
// caller), so the process-global slot IS the thread-local slot.
private var settleLocal: Any? = null

internal actual fun currentSettleLocal(): Any? = settleLocal

internal actual fun setSettleLocal(value: Any?) {
    settleLocal = value
}

private var computeReadsLocal: Any? = null

internal actual fun currentComputeReadsLocal(): Any? = computeReadsLocal

internal actual fun setComputeReadsLocal(value: Any?) {
    computeReadsLocal = value
}
