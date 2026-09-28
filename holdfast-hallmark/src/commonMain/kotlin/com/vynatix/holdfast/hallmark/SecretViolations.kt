@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.HallmarkResult
import com.vynatix.hallmark.NonEmptyList
import com.vynatix.hallmark.Validator
import com.vynatix.hallmark.Violation
import com.vynatix.holdfast.StoreInternalApi

// Validation of a `StateTag.Secret` boxed state (issue #20, R3). Hallmark's
// rules may quote the rejected value in a violation's message and arguments
// ("must be at least 1000; got 42"), and HallmarkException's message joins
// those messages — so a failed write of a secret would print the secret in
// every log line and test report that shows the exception. For a Secret
// state the boxed path throws the same HallmarkException with the same
// violations, minus the value: each keeps its code, path and rule, and loses
// its message and arguments. `:holdfast-hallmark-coroutines`'
// `suspendValidateAndMutate` withholds through the same [withheld].

/** The message a withheld [Violation] carries instead of the rule's. */
private fun withheldMessage(violation: Violation): String =
    "${violation.code ?: "a validation rule"} rejected the value (withheld: a Secret state)"

/**
 * These violations without the rejected value: code, path and rule kept,
 * message replaced by "`<code>` rejected the value (withheld: a Secret
 * state)", arguments dropped. What a companion module throws, in a
 * [HallmarkException], for a rejected value of a `StateTag.Secret` state.
 */
@StoreInternalApi
fun NonEmptyList<Violation>.withheld(): NonEmptyList<Violation> =
    NonEmptyList.of(map { Violation(message = withheldMessage(it), path = it.path, code = it.code, rule = it.rule) })

/**
 * [primitive] validated into its boxed form, like `validator of primitive`,
 * but a rejection throws a [HallmarkException] whose violations are
 * [withheld] when [secret]: its message never quotes the value.
 */
internal fun <P : Any, O> Validator<P, O>.ofWithheldIf(
    secret: Boolean,
    primitive: P,
): O {
    if (!secret) return this of primitive
    return when (val result = validate(primitive)) {
        is HallmarkResult.Success -> result.value
        is HallmarkResult.Failure -> throw HallmarkException(result.violations.withheld())
    }
}
