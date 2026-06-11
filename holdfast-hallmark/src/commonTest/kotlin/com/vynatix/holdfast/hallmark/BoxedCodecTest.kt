package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.BoxedValidator
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.Spec
import com.vynatix.hallmark.SpecMode
import com.vynatix.hallmark.rules.MinLengthRule
import com.vynatix.holdfast.bridge.LongCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private data class Token(
    override val value: String,
) : Boxed<String>

private object TokenValidator : BoxedValidator<String, Token>() {
    override val specs =
        listOf(
            Spec(listOf(MinLengthRule(5)), SpecMode.ALL) { Token(it) },
        )
}

private data class CreditCents(
    override val value: Long,
) : Boxed<Long>

private object CreditCentsValidator : BoxedValidator<Long, CreditCents>() {
    override val specs =
        listOf(
            Spec(
                listOf(
                    object : com.vynatix.hallmark.Rule<Long>("positive", "must be positive") {
                        override fun validate(value: Long) = value > 0
                    },
                ),
                SpecMode.ALL,
            ) { CreditCents(it) },
        )
}

class BoxedCodecTest {
    @Test
    fun encodeAndDecodeStringBoxedRoundTrip() {
        val codec = BoxedCodec(StringCodec, TokenValidator)
        val token = TokenValidator of "abcdef"
        val encoded = codec.encode(token)
        assertEquals("abcdef", encoded)
        val decoded = codec.decode(encoded)
        assertEquals(token, decoded)
    }

    @Test
    fun encodeAndDecodeLongBoxedRoundTrip() {
        val codec = BoxedCodec(LongCodec, CreditCentsValidator)
        val cents = CreditCentsValidator of 12345L
        val encoded = codec.encode(cents)
        assertEquals("12345", encoded)
        val decoded = codec.decode(encoded)
        assertEquals(cents, decoded)
    }

    @Test
    fun decodeOfPersistedInvalidPrimitiveThrowsValidationException() {
        val codec = BoxedCodec(StringCodec, TokenValidator)
        // simulate persisted primitive that no longer satisfies MinLengthRule(5)
        assertFailsWith<HallmarkException> { codec.decode("ab") }
    }
}
