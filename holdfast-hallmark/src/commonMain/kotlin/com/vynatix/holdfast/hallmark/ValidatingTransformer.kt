package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.Validator
import com.vynatix.holdfast.Transformer

/**
 * A Store [Transformer] that re-validates a [Boxed]'s primitive against its
 * [Validator] on every write. A failed validation throws [HallmarkException]
 * inside the transformer's `set`, which propagates to the enclosing
 * `action { … }` and rolls every state mutation in the transaction back.
 *
 * Why use this when [Validator.of] already throws? Defence in depth: a caller
 * that constructs a [Boxed] directly (bypassing the validator, e.g. via
 * `data class copy`) still has its invariant enforced when the value lands
 * in the store.
 *
 * Wired automatically by [boxed]. Ship it standalone if you need a custom
 * `state(transformer = …) { … }` declaration.
 *
 * Hallmark's rules may quote the rejected value in the exception's message
 * ("must be at least 1000; got 42"). The `boxed(validator, tags = …)` and
 * `boxedHandle(validator, tags = …)` overloads withhold it for a
 * `StateTag.Secret` state; a transformer you construct yourself does not know
 * its state's tags and keeps hallmark's messages, so declare a Secret boxed
 * state through those overloads.
 */
class ValidatingTransformer<P : Any, O : Boxed<P>> internal constructor(
    private val validator: Validator<P, O>,
    /** Whether a rejection withholds the value (a Secret state's transformer). */
    private val withheld: Boolean,
) : Transformer<O> {
    constructor(validator: Validator<P, O>) : this(validator, withheld = false)

    override fun set(value: O): O {
        validator.ofWithheldIf(withheld, value.value)
        return value
    }

    override fun get(value: O): O = value
}
