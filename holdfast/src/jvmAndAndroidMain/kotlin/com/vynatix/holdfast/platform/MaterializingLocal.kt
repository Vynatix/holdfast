package com.vynatix.holdfast.platform

private val materializingLocal = ThreadLocal<Any?>()

internal actual fun currentMaterializingLocal(): Any? = materializingLocal.get()

internal actual fun setMaterializingLocal(value: Any?) {
    // Remove instead of set(null) so short-lived threads don't retain an empty
    // ThreadLocal entry after their last materialization returns.
    if (value == null) materializingLocal.remove() else materializingLocal.set(value)
}
