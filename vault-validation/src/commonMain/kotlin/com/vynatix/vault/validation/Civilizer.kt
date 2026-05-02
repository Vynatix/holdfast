package com.vynatix.vault.validation

/**
 * Civilizes raw primitives [PRIMITIVE] into typed domain objects [OBJECT] at
 * the boundary of your system.
 *
 * A civilizer holds a [variations] collection. [of] picks the first variation
 * whose [Condition] returns true for the supplied primitive, then runs that
 * variation's [Declaration] to construct the civilized object. If no variation
 * matches, [of] throws [IllegalArgumentException] — which, when invoked inside a
 * Vault transaction, rolls the transaction back atomically.
 *
 * **Quick start**:
 * ```kotlin
 * data class Email(override val value: String) : Civilizable<String>
 *
 * object EmailCivilizer : Civilizer<String, Rule<String>, Email> {
 *     private val nonEmpty = Rule<String> { it.isNotBlank() }
 *     private val containsAt = Rule<String> { it.contains('@') }
 *     override val variations = listOf(
 *         createVariation({ Email(it) }, allConditions(), nonEmpty, containsAt),
 *     )
 * }
 *
 * val email: Email = EmailCivilizer of "alice@example.com"
 * ```
 *
 * Pair with [CivilizingTransformer] to enforce the same invariant on every
 * Vault state write.
 */
interface Civilizer<PRIMITIVE, RULE : Rule<PRIMITIVE>, OBJECT : Civilizable<PRIMITIVE>> {
    val variations: Collection<Variation<PRIMITIVE, RULE, OBJECT>>

    /**
     * Civilize [value] into [OBJECT]. Throws [IllegalArgumentException] if no
     * variation matches.
     */
    infix fun of(value: PRIMITIVE): OBJECT {
        val safeValue = requireNotNull(value) { "Cannot civilize a null primitive" }
        val match = findVariationByValue(safeValue) ?: noVariationFound(safeValue, variations)
        return match.declaration(safeValue)
    }

    /** Civilize, returning null on rejection. */
    fun ofOrNull(value: PRIMITIVE): OBJECT? = findVariationByValue(value)?.declaration?.invoke(value)

    /** Whether at least one variation accepts [value]. */
    fun validate(value: PRIMITIVE): Boolean = findVariationByValue(value) != null

    /** Combinator: every rule in the variation must pass. */
    fun <P, R : Rule<P>> allConditions(): Condition<P, R> = Condition { p, rules -> rules.all { it.validate(p) } }

    /** Combinator: at least one rule in the variation must pass. */
    fun <P, R : Rule<P>> anyConditions(): Condition<P, R> = Condition { p, rules -> rules.any { it.validate(p) } }

    /**
     * Build a [Variation] anonymously. The rules are passed as a vararg; the
     * supplied [condition] decides how to combine them (typically
     * [allConditions] or [anyConditions]).
     */
    fun createVariation(
        declaration: Declaration<PRIMITIVE, OBJECT>,
        condition: Condition<PRIMITIVE, RULE>,
        vararg rule: RULE,
    ): Variation<PRIMITIVE, RULE, OBJECT> = object : Variation<PRIMITIVE, RULE, OBJECT> {
        override val rule: Array<out RULE> = rule
        override val declaration: Declaration<PRIMITIVE, OBJECT> = declaration
        override val condition: Condition<PRIMITIVE, RULE> = condition
    }

    private fun findVariationByValue(value: PRIMITIVE): Variation<PRIMITIVE, RULE, OBJECT>? =
        variations.find { it.condition.run(value, it.rule) }

    private fun noVariationFound(value: PRIMITIVE, variations: Collection<Variation<PRIMITIVE, RULE, OBJECT>>): Nothing {
        val ruleNames = variations.flatMap { v -> v.rule.map { it::class.simpleName ?: "Rule" } }
        throw IllegalArgumentException("No variation found for value $value in ${ruleNames.joinToString()}")
    }
}
