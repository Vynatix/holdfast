package com.vynatix.vault.validation

/** Constructor lambda that turns a primitive into a civilized object. */
typealias Declaration<PRIMITIVE, OBJECT> = (PRIMITIVE) -> OBJECT

/**
 * A single way of civilizing a primitive [PRIMITIVE] into a [Civilizable]
 * [OBJECT].
 *
 * A variation pairs an array of [Rule]s, a [Condition] that combines them, and
 * a [Declaration] that builds the civilized object once the rules pass.
 *
 * Variations let one [Civilizer] handle multiple shapes of input (e.g. a
 * `NumberCivilizer` with `IntegerVariation` and `FloatVariation`). Use
 * [Civilizer.createVariation] to build instances ergonomically.
 */
interface Variation<PRIMITIVE, RULE : Rule<PRIMITIVE>, OBJECT : Civilizable<PRIMITIVE>> {
    val rule: Array<out RULE>
    val condition: Condition<PRIMITIVE, RULE>
    val declaration: Declaration<PRIMITIVE, OBJECT>
}
