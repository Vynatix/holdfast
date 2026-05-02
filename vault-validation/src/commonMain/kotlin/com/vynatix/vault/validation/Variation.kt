package com.vynatix.vault.validation

/**
 * A single way of civilizing a primitive [P] into a [Civilizable] [O].
 *
 * Variations let one [Civilizer] handle multiple shapes of input. For instance,
 * a `NumberCivilizer` might have an `IntegerVariation` and a `FloatVariation`,
 * each with a different rule and a different `create` lambda.
 */
interface Variation<P, R : Rule<P>, O : Civilizable<P>> {
    val rule: R
    val condition: Condition<P, R>

    /** Construct the civilized object. Called only after [matches] returns true. */
    fun create(primitive: P): O

    /** Whether this variation accepts [primitive]. */
    fun matches(primitive: P): Boolean = condition.check(primitive, rule)

    companion object {
        /**
         * Build a [Variation] from a rule, a condition, and a constructor lambda.
         *
         * ```kotlin
         * val variation = Variation.of(EmailRule, allConditions()) { Email(it) }
         * ```
         */
        fun <P, R : Rule<P>, O : Civilizable<P>> of(
            rule: R,
            condition: Condition<P, R> = allConditions(),
            create: (P) -> O,
        ): Variation<P, R, O> = SimpleVariation(rule, condition, create)
    }
}

private class SimpleVariation<P, R : Rule<P>, O : Civilizable<P>>(
    override val rule: R,
    override val condition: Condition<P, R>,
    private val construct: (P) -> O,
) : Variation<P, R, O> {
    override fun create(primitive: P): O = construct(primitive)
}
