package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.Validator
import com.vynatix.holdfast.StateDelegate
import com.vynatix.holdfast.Store

/**
 * Declare a state property whose value is a [Boxed] [O] validated by the
 * supplied [validator]. Sugar for `state(transformer = ValidatingTransformer(v))
 * { v of initial() }` — eliminates the duplicate validator reference.
 *
 * ```kotlin
 * class UserVault : Store<UserVault>() {
 *     val email by boxed(EmailValidator) { "init@example.com" }
 * }
 *
 * store action {
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
 * either Store context-receiver support or a Store-core change to expose the
 * mutator from outside `action { }` scope.
 */
fun <V : Store<V>, P : Any, O : Boxed<P>> Store<V>.boxed(validator: Validator<P, O>, initial: () -> P): StateDelegate<O> =
    state(transformer = ValidatingTransformer(validator)) { validator of initial() }
