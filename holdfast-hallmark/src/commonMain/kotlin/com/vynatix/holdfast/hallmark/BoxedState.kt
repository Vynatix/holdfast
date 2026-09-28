package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.Validator
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StateDelegate
import com.vynatix.holdfast.StateTag
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
 * [com.vynatix.hallmark.HallmarkException] is thrown when the state is first
 * materialized, not when the store is constructed: on its first read, or
 * when `snapshot()` (which then throws) or `restore()` (which then returns
 * [com.vynatix.holdfast.TransactionResult.Error]) needs a state that was
 * never read (see [com.vynatix.holdfast.Store.state]). The experimental
 * `reset()` runs the initializer again whenever it resets the state, so it
 * fails the same way and returns [com.vynatix.holdfast.TransactionResult.Error].
 *
 * Note: an `assign` infix that writes a raw primitive directly
 * (`email assign "..."`) was deferred to a future release — it would require
 * either Store context-receiver support or a Store-core change to expose the
 * mutator from outside `action { }` scope.
 */
fun <V : Store<V>, P : Any, O : Boxed<P>> Store<V>.boxed(
    validator: Validator<P, O>,
    initial: () -> P,
): StateDelegate<O> = state(transformer = ValidatingTransformer(validator)) { validator of initial() }

/**
 * [boxed], declared through `Store.state`'s experimental overload: with a
 * [codec] that encodes the boxed value when a snapshot leaves memory (wrap a
 * primitive codec in [BoxedCodec]), and [tags] the store enforces
 * (`StateTag.Secret`, `StateTag.UserAuthored`, `StateTag.Remote`; see
 * `Store.state`).
 *
 * For a `StateTag.Secret` state, validation never quotes the rejected value:
 * a [com.vynatix.hallmark.HallmarkException] thrown by the initializer or by
 * a write keeps each violation's code, path and rule, but its message reads
 * "`<code>` rejected the value (withheld: a Secret state)" and it carries no
 * arguments, where hallmark's rules may quote the value ("got 42"). A
 * validator you call yourself (`validator of x`) is outside the store and
 * keeps hallmark's messages.
 *
 * Calls that pass neither [codec] nor [tags] resolve to the stable [boxed].
 *
 * Experimental (issue #20, R1 and R3).
 */
@ExperimentalStoreApi
fun <V : Store<V>, P : Any, O : Boxed<P>> Store<V>.boxed(
    validator: Validator<P, O>,
    codec: StateCodec<O>? = null,
    tags: Set<StateTag> = emptySet(),
    initial: () -> P,
): StateDelegate<O> {
    val secret = StateTag.Secret in tags
    return state(ValidatingTransformer(validator, withheld = secret), codec = codec, tags = tags) {
        validator.ofWithheldIf(secret, initial())
    }
}
