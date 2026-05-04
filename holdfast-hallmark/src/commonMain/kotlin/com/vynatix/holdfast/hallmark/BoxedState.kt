package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.Validator
import com.vynatix.holdfast.StateDelegate
import com.vynatix.holdfast.Holdfast

/**
 * Declare a state property whose value is a [Boxed] [O] validated by the
 * supplied [validator]. Sugar for `state(transformer = ValidatingTransformer(v))
 * { v of initial() }` — eliminates the duplicate validator reference.
 *
 * ```kotlin
 * class UserVault : Holdfast<UserVault>() {
 *     val email by boxed(EmailValidator) { "init@example.com" }
 * }
 *
 * vault action {
 *     email mutate (EmailValidator of "alice@example.com")
 * }
 * ```
 *
 * If [initial] returns a primitive that itself fails validation,
 * [com.vynatix.hallmark.HallmarkException] is thrown lazily on first
 * read of the state.
 *
 * Note: an `assign` infix that writes a raw primitive directly
 * (`email assign "..."`) was deferred to a future release — it would require
 * either Holdfast context-receiver support or a Holdfast-core change to expose the
 * mutator from outside `action { }` scope.
 */
fun <V : Holdfast<V>, P : Any, O : Boxed<P>> Holdfast<V>.boxed(validator: Validator<P, O>, initial: () -> P): StateDelegate<O> =
    state(transformer = ValidatingTransformer(validator)) { validator of initial() }
