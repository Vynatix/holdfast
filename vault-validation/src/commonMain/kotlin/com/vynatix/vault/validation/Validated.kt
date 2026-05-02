package com.vynatix.vault.validation

/**
 * A typed wrapper around a primitive [PRIMITIVE] whose value has been validated
 * at the system boundary.
 *
 * The pattern: instead of passing raw `String` / `Long` / etc. through your
 * domain — risking primitive obsession and uncaught invariant violations —
 * validate the primitive once into a [Validated] wrapper, and pass the wrapper
 * thereafter. Pair with [Validator] to perform the validation, and with
 * [ValidatingTransformer] to enforce the invariant on every Vault write.
 */
interface Validated<PRIMITIVE> {
    val value: PRIMITIVE
}
