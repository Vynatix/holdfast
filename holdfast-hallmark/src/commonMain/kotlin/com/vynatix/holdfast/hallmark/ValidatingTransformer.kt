package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.HallmarkResult
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
 */
class ValidatingTransformer<P : Any, O : Boxed<P>>(private val validator: Validator<P, O>) : Transformer<O> {
    override fun set(value: O): O {
        val result = validator.validate(value.value)
        if (result is HallmarkResult.Failure) {
            throw HallmarkException(result.violations)
        }
        return value
    }

    override fun get(value: O): O = value
}
