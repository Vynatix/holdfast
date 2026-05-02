package com.vynatix.vault.validation

/**
 * A predicate over a primitive [PRIMITIVE] used to decide whether the primitive
 * is acceptable for wrapping into a [Boxed].
 *
 * Multiple rules can be attached to a single [Spec]; the [Condition] decides
 * how to combine them (`allConditions()` requires every rule to pass;
 * `anyConditions()` requires at least one).
 */
fun interface Rule<PRIMITIVE> {
    fun validate(primitive: PRIMITIVE): Boolean
}
