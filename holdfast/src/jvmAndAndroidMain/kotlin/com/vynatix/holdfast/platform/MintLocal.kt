package com.vynatix.holdfast.platform

private val mintLocal = ThreadLocal<Any?>()

internal actual fun currentMintLocal(): Any? = mintLocal.get()

internal actual fun setMintLocal(value: Any?) {
    // Remove instead of set(null) so short-lived threads don't retain an empty
    // ThreadLocal entry after their last keyed-store factory returns.
    if (value == null) mintLocal.remove() else mintLocal.set(value)
}
