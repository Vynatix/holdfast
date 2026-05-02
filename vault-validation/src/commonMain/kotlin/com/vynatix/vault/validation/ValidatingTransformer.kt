package com.vynatix.vault.validation

import com.vynatix.vault.Transformer

/**
 * A [Transformer] that re-validates a [Validated]'s primitive against its
 * [Validator] on every write, throwing [IllegalArgumentException] (and rolling
 * back the enclosing transaction) when no spec matches.
 *
 * Why use this when [Validator.of] already throws? Two reasons:
 *  1. Defence in depth — a caller that constructs a [Validated] directly
 *     (bypassing the validator, e.g. via a `data class copy`) still has its
 *     invariant enforced when the value lands in the vault.
 *  2. Atomicity — a failed write inside an `action { … }` rolls back every
 *     other state mutation in the same transaction, not just this one.
 *
 * Example:
 * ```kotlin
 * class UserVault : Vault<UserVault>() {
 *     val email by state(transformer = ValidatingTransformer(EmailValidator)) {
 *         EmailValidator of "init@example.com"
 *     }
 * }
 *
 * vault action { email mutate (EmailValidator of "new@example.com") }   // OK
 * vault action { email mutate Email("not-an-email") }                   // rolls back
 * ```
 *
 * The [get] side is identity — the stored object already carries its primitive.
 */
class ValidatingTransformer<PRIMITIVE, RULE : Rule<PRIMITIVE>, OBJECT : Validated<PRIMITIVE>>(
    private val validator: Validator<PRIMITIVE, RULE, OBJECT>,
) : Transformer<OBJECT> {
    override fun set(value: OBJECT): OBJECT {
        // Re-run the validator on value.value; this throws IllegalArgumentException
        // if no spec matches, which propagates to the action and rolls back.
        validator of value.value
        return value
    }

    override fun get(value: OBJECT): OBJECT = value
}
