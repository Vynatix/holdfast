package com.vynatix.holdfast.platform

private val bareInvokeDepthLocal = ThreadLocal<Int>()

internal actual fun bareInvokeDepth(): Int = bareInvokeDepthLocal.get() ?: 0

internal actual fun setBareInvokeDepth(value: Int) {
    if (value == 0) {
        // Remove instead of set(0) so short-lived threads don't retain an empty
        // ThreadLocal entry after their last invoke exits.
        bareInvokeDepthLocal.remove()
    } else {
        bareInvokeDepthLocal.set(value)
    }
}
