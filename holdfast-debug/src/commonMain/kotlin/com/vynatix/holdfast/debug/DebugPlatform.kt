package com.vynatix.holdfast.debug

/**
 * Enum constants of [value]'s declaring enum class, or `null` when [value] is
 * not an enum or the platform cannot enumerate them without reflection
 * (Kotlin/Native). Used by [LiteralParser] to accept enum names in `set`.
 */
internal expect fun enumConstantsOf(value: Any): List<Any>?
