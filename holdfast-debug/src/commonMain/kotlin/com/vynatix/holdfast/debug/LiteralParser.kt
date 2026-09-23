package com.vynatix.holdfast.debug

/**
 * Parses a shell literal into the runtime type of a state's *current* value.
 *
 * The one rule that keeps `set` type-safe: the parser yields exactly the class
 * of [parseLike]'s `current` argument, which is by construction a subtype of
 * the state's erased `T`, so the unchecked `mutate` cast in [applyWrites] can
 * never admit a wrong-typed value. Anything not in the table below is refused
 * with a pointer to registered verbs — a data class, a list or a value class
 * cannot be rebuilt safely from a string.
 */
internal object LiteralParser {
    sealed interface Outcome {
        data class Ok(
            val value: Any,
        ) : Outcome

        data class Fail(
            val message: String,
        ) : Outcome
    }

    @Suppress("CyclomaticComplexMethod")
    fun parseLike(
        current: Any,
        literal: String,
    ): Outcome =
        when (current) {
            is Boolean ->
                when (literal.lowercase()) {
                    "true" -> Outcome.Ok(true)
                    "false" -> Outcome.Ok(false)
                    else -> fail("Boolean", literal, "expected true or false")
                }
            is Int -> literal.toIntOrNull()?.let(Outcome::Ok) ?: fail("Int", literal)
            is Long -> literal.removeSuffix("L").toLongOrNull()?.let(Outcome::Ok) ?: fail("Long", literal)
            is Short -> literal.toShortOrNull()?.let(Outcome::Ok) ?: fail("Short", literal)
            is Byte -> literal.toByteOrNull()?.let(Outcome::Ok) ?: fail("Byte", literal)
            is Double -> literal.toDoubleOrNull()?.let(Outcome::Ok) ?: fail("Double", literal)
            is Float -> literal.removeSuffix("f").toFloatOrNull()?.let(Outcome::Ok) ?: fail("Float", literal)
            is Char -> literal.singleOrNull()?.let(Outcome::Ok) ?: fail("Char", literal, "expected one character")
            is String -> Outcome.Ok(unquote(literal))
            is Enum<*> -> parseEnum(current, literal)
            else ->
                Outcome.Fail(
                    "unsupported type ${current::class.simpleName ?: "?"}: `set` handles primitives, " +
                        "String and enums; register a verb with HoldfastDebug.verb(...) for anything else",
                )
        }

    private fun parseEnum(
        current: Enum<*>,
        literal: String,
    ): Outcome {
        val constants = enumConstantsOf(current)
        val type = current::class.simpleName
        return when {
            constants == null -> Outcome.Fail("enum $type cannot be resolved by name on this platform; register a verb")
            else -> {
                val names = constants.map { (it as Enum<*>).name }
                val exact = constants.firstOrNull { (it as Enum<*>).name == literal }
                val loose = constants.filter { (it as Enum<*>).name.equals(literal, ignoreCase = true) }
                when {
                    exact != null -> Outcome.Ok(exact)
                    loose.size == 1 -> Outcome.Ok(loose.single())
                    else -> Outcome.Fail("'$literal' is not a $type; one of: ${names.joinToString(", ")}")
                }
            }
        }
    }

    /** `""` and `''` denote the empty string; anything else is taken verbatim. */
    private fun unquote(literal: String): String =
        when (literal) {
            "\"\"", "''" -> ""
            else -> literal
        }

    private fun fail(
        type: String,
        literal: String,
        hint: String = "not a valid $type",
    ): Outcome = Outcome.Fail("'$literal' → $hint")
}
