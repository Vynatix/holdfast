package com.vynatix.vault.validation

/**
 * Validates raw primitives [PRIMITIVE] and wraps them into typed domain
 * objects [OBJECT] at the boundary of your system.
 *
 * A validator holds a [specs] collection. [of] picks the first spec whose
 * [Condition] returns true for the supplied primitive, then runs that spec's
 * [Factory] to construct the wrapper. If no spec matches, [of] throws
 * [IllegalArgumentException] — which, when invoked inside a Vault
 * transaction, rolls the transaction back atomically.
 *
 * **Quick start**:
 * ```kotlin
 * data class Email(override val value: String) : Validated<String>
 *
 * object EmailValidator : Validator<String, Rule<String>, Email> {
 *     private val nonEmpty = Rule<String> { it.isNotBlank() }
 *     private val containsAt = Rule<String> { it.contains('@') }
 *     override val specs = listOf(
 *         createSpec({ Email(it) }, allConditions(), nonEmpty, containsAt),
 *     )
 * }
 *
 * val email: Email = EmailValidator of "alice@example.com"
 * ```
 *
 * Pair with [ValidatingTransformer] to enforce the same invariant on every
 * Vault state write.
 */
interface Validator<PRIMITIVE, RULE : Rule<PRIMITIVE>, OBJECT : Validated<PRIMITIVE>> {
    val specs: Collection<Spec<PRIMITIVE, RULE, OBJECT>>

    /**
     * Validate [value] and wrap it into [OBJECT]. Throws
     * [IllegalArgumentException] if no spec matches.
     */
    infix fun of(value: PRIMITIVE): OBJECT {
        val safeValue = requireNotNull(value) { "Cannot validate a null primitive" }
        val match = findSpecForValue(safeValue) ?: noSpecMatched(safeValue, specs)
        return match.factory(safeValue)
    }

    /** Validate, returning null on rejection. */
    fun ofOrNull(value: PRIMITIVE): OBJECT? = findSpecForValue(value)?.factory?.invoke(value)

    /** Whether at least one spec accepts [value]. */
    fun validate(value: PRIMITIVE): Boolean = findSpecForValue(value) != null

    /** Combinator: every rule in the spec must pass. */
    fun <P, R : Rule<P>> allConditions(): Condition<P, R> = Condition { p, rules -> rules.all { it.validate(p) } }

    /** Combinator: at least one rule in the spec must pass. */
    fun <P, R : Rule<P>> anyConditions(): Condition<P, R> = Condition { p, rules -> rules.any { it.validate(p) } }

    /**
     * Build a [Spec] anonymously. The rules are passed as a vararg; the
     * supplied [condition] decides how to combine them (typically
     * [allConditions] or [anyConditions]).
     */
    fun createSpec(
        factory: Factory<PRIMITIVE, OBJECT>,
        condition: Condition<PRIMITIVE, RULE>,
        vararg rule: RULE,
    ): Spec<PRIMITIVE, RULE, OBJECT> = object : Spec<PRIMITIVE, RULE, OBJECT> {
        override val rule: Array<out RULE> = rule
        override val factory: Factory<PRIMITIVE, OBJECT> = factory
        override val condition: Condition<PRIMITIVE, RULE> = condition
    }

    private fun findSpecForValue(value: PRIMITIVE): Spec<PRIMITIVE, RULE, OBJECT>? = specs.find { it.condition.run(value, it.rule) }

    private fun noSpecMatched(value: PRIMITIVE, specs: Collection<Spec<PRIMITIVE, RULE, OBJECT>>): Nothing {
        val ruleNames = specs.flatMap { s -> s.rule.map { it::class.simpleName ?: "Rule" } }
        throw IllegalArgumentException("No spec matched value $value in ${ruleNames.joinToString()}")
    }
}
