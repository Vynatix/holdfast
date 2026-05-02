package com.vynatix.vault.validation

/**
 * A check that combines a [Rule] with the inbound primitive [P]. Conditions add
 * a layer of indirection over the rule so that callers can swap "all rules
 * pass", "any rule passes", or custom multi-rule logic without rewriting
 * [Variation].
 */
fun interface Condition<P, R : Rule<P>> {
    fun check(primitive: P, rule: R): Boolean
}

/** Default condition: the supplied rule must validate the primitive. */
fun <P, R : Rule<P>> allConditions(): Condition<P, R> = Condition { primitive, rule -> rule.validate(primitive) }

/**
 * Condition that delegates to [extra] in addition to the primary rule.
 *
 * `condition.check(p, rule)` returns true iff *both* `rule.validate(p)` and every
 * predicate in [extra] returns true.
 */
fun <P, R : Rule<P>> allOf(vararg extra: (P) -> Boolean): Condition<P, R> = Condition { primitive, rule ->
    rule.validate(primitive) && extra.all { it(primitive) }
}
