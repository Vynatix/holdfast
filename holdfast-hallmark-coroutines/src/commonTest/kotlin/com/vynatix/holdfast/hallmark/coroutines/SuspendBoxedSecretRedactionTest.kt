@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.hallmark.coroutines

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.BoxedValidator
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.Spec
import com.vynatix.hallmark.SpecMode
import com.vynatix.hallmark.coroutines.asSuspend
import com.vynatix.hallmark.rules.GteRule
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.hallmark.boxed
import com.vynatix.holdfast.middleware.LoggingMiddleware
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A value hallmark's GteRule quotes when it rejects it: "must be at least 100000; got 4217". */
private const val REJECTED = 4217L

private data class Pin(
    override val value: Long,
) : Boxed<Long>

private object PinValidator : BoxedValidator<Long, Pin>() {
    override val specs = listOf(Spec(listOf(GteRule(100_000L)), SpecMode.ALL) { Pin(it) })
}

private class PinStore : Store<PinStore>() {
    val secret by boxed(PinValidator, tags = setOf(StateTag.Secret)) { 123_456L }
    val public by boxed(PinValidator) { 111_111L }
}

private fun Throwable.fullText(): String =
    generateSequence(this) { it.cause }.joinToString("\n") { "$it ${(it as? HallmarkException)?.violations}" }

/**
 * `suspendValidateAndMutate` has the state in hand, so for a
 * `StateTag.Secret` state it withholds the rejected value from the
 * `HallmarkException` — and so from the `TransactionResult.Error` and every
 * middleware line — as `:holdfast-hallmark`'s boxed paths do (issue #20, R3).
 */
class SuspendBoxedSecretRedactionTest {
    @Test fun aRejectedValueForASecretStateIsWithheld() =
        runTest {
            val store = PinStore()
            val log = mutableListOf<String>()
            store.middlewares(LoggingMiddleware("pins") { log += it })

            val result = store.suspendValidateAndMutate(store.secret, PinValidator.asSuspend(), REJECTED)

            val failure = assertIs<HallmarkException>(assertIs<TransactionResult.Error>(result).exception)
            assertFalse("$REJECTED" in failure.fullText(), failure.fullText())
            assertFalse(log.any { "$REJECTED" in it }, "$log")
            assertTrue(log.isNotEmpty(), "the middleware logged the failed action")
            assertContains(failure.message.orEmpty(), "number.gte rejected the value (withheld: a Secret state)")
            val violation = failure.violations.single()
            assertEquals("number.gte", violation.code)
            assertIs<GteRule<Long>>(violation.rule)
            assertTrue(violation.args.isEmpty(), "the arguments held the value: ${violation.args}")
            assertEquals(123_456L, store.secret.value.value, "the write rolled back")
        }

    @Test fun anAcceptedValueForASecretStateIsWritten() =
        runTest {
            val store = PinStore()
            val result = store.suspendValidateAndMutate(store.secret, PinValidator.asSuspend(), 222_222L)
            assertIs<TransactionResult.Success<Unit>>(result)
            assertEquals(222_222L, store.secret.value.value)
        }

    @Test fun aNonSecretStateKeepsHallmarksMessage() =
        runTest {
            val store = PinStore()
            val log = mutableListOf<String>()
            store.middlewares(LoggingMiddleware("pins") { log += it })
            val result = store.suspendValidateAndMutate(store.public, PinValidator.asSuspend(), REJECTED)
            val failure = assertIs<TransactionResult.Error>(result).exception
            assertContains(failure.message.orEmpty(), "got $REJECTED", message = "hallmark's own message, unchanged")
            assertTrue(log.any { "got $REJECTED" in it }, "the log shows the exception message: $log")
            assertEquals(111_111L, store.public.value.value)
        }
}
