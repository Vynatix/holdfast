package com.vynatix.vault.validation

import com.vynatix.validation.Boxed
import com.vynatix.validation.Validator
import com.vynatix.vault.State
import com.vynatix.vault.StateDelegate
import com.vynatix.vault.Vault
import kotlin.reflect.KProperty

/**
 * Bundles a Vault [State] with the [Validator] that gates it. Returned by
 * [boxedHandle].
 *
 * Inside a `vault action { ... }` block the handle exposes:
 *  - [state] — the underlying Vault state, used as the receiver for `mutate`.
 *  - [validator] — the static validator, used to civilize raw primitives.
 *  - [civilize] — convenience: civilize a primitive into the wrapped form.
 *
 * Usage:
 * ```kotlin
 * class UserVault : Vault<UserVault>() {
 *     val email by boxedHandle(EmailValidator) { "init@example.com" }
 * }
 *
 * vault action {
 *     email.state mutate email.civilize("alice@example.com")
 * }
 * ```
 *
 * The two-step `state mutate civilize(...)` pattern is the closest we can
 * get to a one-line `email assign "..."` infix without enabling Kotlin
 * context parameters in the compiler. `civilize` is just sugar for
 * `validator of primitive` — same throw semantics.
 */
data class BoxedHandle<P : Any, O : Boxed<P>>(val state: State<O>, val validator: Validator<P, O>) {
    /** Civilize [primitive] through the bundled validator. Throws on rejection. */
    fun civilize(primitive: P): O = validator of primitive
}

/**
 * Property delegate that returns a [BoxedHandle] (state + validator) on every
 * read.
 */
class BoxedHandleDelegate<P : Any, O : Boxed<P>> internal constructor(
    private val backing: StateDelegate<O>,
    private val validator: Validator<P, O>,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): BoxedHandle<P, O> =
        BoxedHandle(backing.getValue(thisRef, property), validator)
}

/**
 * Like [boxed], but the resulting property is a [BoxedHandle] (carrying both
 * the underlying state and the validator) instead of a bare [State]. Useful
 * when the validator instance is awkward to reference at the mutate site.
 *
 * If you don't need the bundled validator at the call site, prefer [boxed].
 */
fun <V : Vault<V>, P : Any, O : Boxed<P>> Vault<V>.boxedHandle(validator: Validator<P, O>, initial: () -> P): BoxedHandleDelegate<P, O> =
    BoxedHandleDelegate(
        backing = state(transformer = ValidatingTransformer(validator)) { validator of initial() },
        validator = validator,
    )
