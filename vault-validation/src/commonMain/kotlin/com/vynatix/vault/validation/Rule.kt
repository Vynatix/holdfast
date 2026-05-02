package com.vynatix.vault.validation

/**
 * A predicate over a primitive [PRIMITIVE] used to decide whether the primitive
 * is acceptable for civilizing into a [Civilizable].
 *
 * Multiple rules can be attached to a single [Variation]; the [Condition]
 * decides how to combine them (`allConditions()` requires every rule to pass;
 * `anyConditions()` requires at least one).
 */
fun interface Rule<PRIMITIVE> {
    fun validate(primitive: PRIMITIVE): Boolean
}
