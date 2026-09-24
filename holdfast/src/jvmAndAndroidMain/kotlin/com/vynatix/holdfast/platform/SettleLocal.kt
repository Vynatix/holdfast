package com.vynatix.holdfast.platform

private val settleLocal = ThreadLocal<Any?>()

internal actual fun currentSettleLocal(): Any? = settleLocal.get()

internal actual fun setSettleLocal(value: Any?) {
    // Remove instead of set(null) so a thread whose coroutine carried a scope
    // does not retain an empty ThreadLocal entry. (A blocking entry leaves its
    // settled scope in the slot instead — see `settling` — so each action on a
    // thread replaces an entry rather than re-inserting one.)
    if (value == null) settleLocal.remove() else settleLocal.set(value)
}

private val computeReadsLocal = ThreadLocal<Any?>()

internal actual fun currentComputeReadsLocal(): Any? = computeReadsLocal.get()

internal actual fun setComputeReadsLocal(value: Any?) {
    if (value == null) computeReadsLocal.remove() else computeReadsLocal.set(value)
}
