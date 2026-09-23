package com.vynatix.holdfast.debug

// Kotlin/Native has no non-reified way to enumerate an enum's constants.
internal actual fun enumConstantsOf(value: Any): List<Any>? = null
