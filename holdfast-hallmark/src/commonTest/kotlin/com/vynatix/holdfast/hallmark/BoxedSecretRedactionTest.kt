@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.BoxedValidator
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.Spec
import com.vynatix.hallmark.SpecMode
import com.vynatix.hallmark.rules.GteRule
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.LongCodec
import com.vynatix.holdfast.middleware.LoggingMiddleware
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.tags
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

private class PinStore(
    initialSecret: Long = 123_456L,
) : Store<PinStore>() {
    val secret by boxed(PinValidator, codec = BoxedCodec(LongCodec, PinValidator), tags = setOf(StateTag.Secret)) {
        initialSecret
    }
    val handle by boxedHandle(PinValidator, tags = setOf(StateTag.Secret)) { 654_321L }
    val public by boxed(PinValidator, codec = BoxedCodec(LongCodec, PinValidator)) { 111_111L }
    val codedHandle by boxedHandle(PinValidator, codec = BoxedCodec(LongCodec, PinValidator)) { 333_333L }

    /** Declared by hand: the transformer cannot know the tags (the documented limit). */
    val byHand by state(transformer = ValidatingTransformer(PinValidator), tags = setOf(StateTag.Secret)) { Pin(222_222L) }
}

private fun Throwable.fullText(): String =
    generateSequence(this) { it.cause }.joinToString("\n") { "$it ${(it as? HallmarkException)?.violations}" }

/**
 * HallmarkException messages may quote a rejected value (issue #20, R3; plan
 * risk "Hallmark's messages may leak Secret values"). Decided here: the boxed
 * path withholds a Secret state's value — initializer, writes, `civilize` and
 * `assign` — keeping each violation's code, path and rule; a non-Secret state
 * keeps hallmark's message; and a `ValidatingTransformer` declared by hand,
 * which cannot know its state's tags, is pinned as the documented gap.
 */
class BoxedSecretRedactionTest {
    @Test fun aRejectedWriteOfASecretBoxedStateWithholdsTheValue() {
        val store = PinStore()
        val log = mutableListOf<String>()
        store.middlewares(LoggingMiddleware("pins") { log += it })

        val result = store action { secret mutate Pin(REJECTED) }

        val failure = assertIs<HallmarkException>(assertIs<TransactionResult.Error>(result).exception)
        assertFalse("$REJECTED" in failure.fullText(), failure.fullText())
        assertFalse(log.any { "$REJECTED" in it }, "$log")
        assertContains(failure.message.orEmpty(), "number.gte rejected the value (withheld: a Secret state)")
        val violation = failure.violations.single()
        assertEquals("number.gte", violation.code)
        assertIs<GteRule<Long>>(violation.rule)
        assertTrue(violation.args.isEmpty(), "the arguments held the value: ${violation.args}")
        assertEquals(123_456L, store.secret.value.value, "the write rolled back")
    }

    @Test fun civilizeAndAssignOnASecretHandleWithholdTheValue() {
        val store = PinStore()
        val civilized = assertFailsWith<HallmarkException> { store.handle.civilize(REJECTED) }
        assertFalse("$REJECTED" in civilized.fullText(), civilized.fullText())

        val assigned = store action { handle assign REJECTED }
        val failure = assertIs<TransactionResult.Error>(assigned).exception
        assertFalse("$REJECTED" in failure.fullText(), failure.fullText())
        assertEquals(setOf(StateTag.Secret), store.handle.state.tags)
    }

    @Test fun aRejectedInitializerOfASecretBoxedStateWithholdsTheValue() {
        val store = PinStore(initialSecret = REJECTED)
        val failure = assertFailsWith<HallmarkException> { store.secret }
        assertFalse("$REJECTED" in failure.fullText(), failure.fullText())
    }

    @Test fun aNonSecretBoxedStateKeepsHallmarksMessage() {
        val store = PinStore()
        val result = store action { public mutate Pin(REJECTED) }
        val failure = assertIs<TransactionResult.Error>(result).exception
        assertContains(failure.message.orEmpty(), "got $REJECTED", message = "hallmark's own message, unchanged")
    }

    @Test fun aValidatingTransformerDeclaredByHandCannotWithholdTheValue() {
        // Pinned gap: the transformer is built before the state exists and
        // never learns its tags. Declare Secret boxed states with boxed(…, tags).
        val store = PinStore()
        val result = store action { byHand mutate Pin(REJECTED) }
        val failure = assertIs<TransactionResult.Error>(result).exception
        assertContains(failure.message.orEmpty(), "got $REJECTED")
    }

    @Test fun shouldBeBoxedAsOnASecretStateWithholdsBothValues() {
        val store = PinStore()
        val failure = assertFailsWith<AssertionError> { store.secret shouldBeBoxedAs 999_999L }
        val message = failure.message.orEmpty()
        assertFalse("123456" in message, message)
        assertFalse("999999" in message, message)
        assertContains(message, "Pin")
        store.public shouldBeBoxedAs 111_111L // non-Secret behaviour unchanged
        val plain = assertFailsWith<AssertionError> { store.public shouldBeBoxedAs 999_999L }
        assertContains(plain.message.orEmpty(), "expected=999999 actual=111111")
    }

    @Test fun theCodecOverloadEncodesBoxedValuesAndSecretsStayOut() {
        val source = PinStore()
        source action {
            public mutate Pin(200_000L)
            codedHandle assign 300_000L
        }
        val text = source.snapshot().encode()
        assertContains(text, "\"public\":\"200000\"")
        assertContains(text, "\"codedHandle\":\"300000\"", message = "boxedHandle's codec overload")
        assertContains(text, "\"secret\":null")
        assertFalse("123456" in text, text)

        val target = PinStore()
        target.restore(StoreSnapshot.decode(text)).getOrThrow()
        assertEquals(200_000L, target.public.value.value)
        assertEquals(300_000L, target.codedHandle.state.value.value)
        assertEquals(123_456L, target.secret.value.value)
    }
}
