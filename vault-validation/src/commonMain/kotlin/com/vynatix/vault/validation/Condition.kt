package com.vynatix.vault.validation

/**
 * A check that combines a [primitive] with the variation's array of [Rule]s.
 *
 * Conditions add a layer of indirection over the rules so that callers can swap
 * "all rules pass", "any rule passes", or custom multi-rule logic without
 * rewriting [Variation]. See [Civilizer.allConditions] / [Civilizer.anyConditions]
 * for the standard combinators.
 */
fun interface Condition<PRIMITIVE, RULE : Rule<PRIMITIVE>> {
    fun run(primitive: PRIMITIVE, rule: Array<out RULE>): Boolean
}
