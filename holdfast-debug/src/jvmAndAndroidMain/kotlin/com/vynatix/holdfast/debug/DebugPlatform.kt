package com.vynatix.holdfast.debug

internal actual fun enumConstantsOf(value: Any): List<Any>? {
    val enum = value as? Enum<*> ?: return null
    return enum.declaringJavaClass.enumConstants?.toList()
}
