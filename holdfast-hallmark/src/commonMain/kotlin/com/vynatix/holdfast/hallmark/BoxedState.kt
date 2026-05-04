package com.vynatix.vault.validation

import com.vynatix.validation.Boxed
import com.vynatix.validation.Validator
import com.vynatix.vault.StateDelegate
import com.vynatix.vault.Vault

/**
 * Declare a state property whose value is a [Boxed] [O] validated by the
 * supplied [validator]. Sugar for `state(transformer = ValidatingTransformer(v))
 * { v of initial() }` — eliminates the duplicate validator reference.
 *
 * ```kotlin
 * class UserVault : Vault<UserVault>() {
 *     val email by boxed(EmailValidator) { "init@example.com" }
 * }
 *
 * vault action {
 *     email mutate (EmailValidator of "alice@example.com")
 * }
 * ```
 *
 * If [initial] returns a primitive that itself fails validation,
 * [com.vynatix.validation.ValidationException] is thrown lazily on first
 * read of the state.
 *
 * Note: an `assign` infix that writes a raw primitive directly
 * (`email assign "..."`) was deferred to a future release — it would require
 * either Vault context-receiver support or a Vault-core change to expose the
 * mutator from outside `action { }` scope.
 */
fun <V : Vault<V>, P : Any, O : Boxed<P>> Vault<V>.boxed(validator: Validator<P, O>, initial: () -> P): StateDelegate<O> =
    state(transformer = ValidatingTransformer(validator)) { validator of initial() }
