package com.vynatix.vault.validation

import com.vynatix.vault.Transformer

/**
 * A [Transformer] that re-civilizes a [Civilizable]'s primitive against its
 * [Civilizer] on every write, throwing [IllegalArgumentException] (and rolling
 * back the enclosing transaction) when no variation matches.
 *
 * Why use this when [Civilizer.of] already throws? Two reasons:
 *  1. Defence in depth — a caller that constructs a [Civilizable] directly
 *     (bypassing the civilizer, e.g. via a `data class copy`) still has its
 *     invariant enforced when the value lands in the vault.
 *  2. Atomicity — a failed write inside an `action { … }` rolls back every
 *     other state mutation in the same transaction, not just this one.
 *
 * Example:
 * ```kotlin
 * class UserVault : Vault<UserVault>() {
 *     val email by state(transformer = CivilizingTransformer(EmailCivilizer)) {
 *         EmailCivilizer of "init@example.com"
 *     }
 * }
 *
 * vault action { email mutate (EmailCivilizer of "new@example.com") }   // OK
 * vault action { email mutate Email("not-an-email") }                   // rolls back
 * ```
 *
 * The [get] side is identity — the stored object already carries its primitive.
 */
class CivilizingTransformer<PRIMITIVE, RULE : Rule<PRIMITIVE>, OBJECT : Civilizable<PRIMITIVE>>(
    private val civilizer: Civilizer<PRIMITIVE, RULE, OBJECT>,
) : Transformer<OBJECT> {
    override fun set(value: OBJECT): OBJECT {
        // Re-run the civilizer on value.value; this throws IllegalArgumentException
        // if no variation matches, which propagates to the action and rolls back.
        civilizer of value.value
        return value
    }

    override fun get(value: OBJECT): OBJECT = value
}
