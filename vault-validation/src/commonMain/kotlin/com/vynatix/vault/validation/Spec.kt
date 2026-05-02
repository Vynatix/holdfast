package com.vynatix.vault.validation

/** Constructor lambda that turns a primitive into a wrapped object. */
typealias Factory<PRIMITIVE, OBJECT> = (PRIMITIVE) -> OBJECT

/**
 * A single way of validating a primitive [PRIMITIVE] and constructing a
 * [Validated] [OBJECT] from it.
 *
 * A spec pairs an array of [Rule]s, a [Condition] that combines them, and a
 * [Factory] that builds the wrapper once the rules pass.
 *
 * Specs let one [Validator] handle multiple shapes of input (e.g. a
 * `NumberValidator` with an integer spec and a float spec). Use
 * [Validator.createSpec] to build instances ergonomically.
 */
interface Spec<PRIMITIVE, RULE : Rule<PRIMITIVE>, OBJECT : Validated<PRIMITIVE>> {
    val rule: Array<out RULE>
    val condition: Condition<PRIMITIVE, RULE>
    val factory: Factory<PRIMITIVE, OBJECT>
}
