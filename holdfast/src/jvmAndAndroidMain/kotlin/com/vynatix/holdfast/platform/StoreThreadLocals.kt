package com.vynatix.holdfast.platform

private val initializerLocal = ThreadLocal<Any?>()

internal actual fun currentInitializerLocal(): Any? = initializerLocal.get()

internal actual fun setInitializerLocal(value: Any?) {
    // Remove instead of set(null) so short-lived threads don't retain an empty
    // ThreadLocal entry after their last initializer returns.
    if (value == null) initializerLocal.remove() else initializerLocal.set(value)
}
