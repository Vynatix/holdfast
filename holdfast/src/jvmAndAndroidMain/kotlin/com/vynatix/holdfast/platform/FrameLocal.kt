package com.vynatix.holdfast.platform

private val frameLocal = ThreadLocal<Any?>()

internal actual fun currentFrameLocal(): Any? = frameLocal.get()

internal actual fun setFrameLocal(value: Any?) {
    if (value == null) {
        // Remove instead of set(null) so short-lived threads don't retain an
        // empty ThreadLocal entry after their last frame exits.
        frameLocal.remove()
    } else {
        frameLocal.set(value)
    }
}
