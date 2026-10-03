package com.vynatix.holdfast.platform

private val constructionLocal = ThreadLocal<Any?>()

internal actual fun currentConstructionLocal(): Any? = constructionLocal.get()

internal actual fun setConstructionLocal(value: Any?) {
    // Remove instead of set(null) so short-lived threads don't retain an empty
    // ThreadLocal entry after their last logged run returns.
    if (value == null) constructionLocal.remove() else constructionLocal.set(value)
}
