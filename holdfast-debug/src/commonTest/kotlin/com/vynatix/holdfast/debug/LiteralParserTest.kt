package com.vynatix.holdfast.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiteralParserTest {
    private fun ok(
        current: Any,
        literal: String,
    ): Any = assertIs<LiteralParser.Outcome.Ok>(LiteralParser.parseLike(current, literal)).value

    private fun fail(
        current: Any,
        literal: String,
    ): String = assertIs<LiteralParser.Outcome.Fail>(LiteralParser.parseLike(current, literal)).message

    @Test
    fun producesTheRuntimeClassOfTheCurrentValue() {
        assertEquals(3, ok(1, "3"))
        assertEquals(3L, ok(1L, "3"))
        assertEquals(3L, ok(1L, "3L"))
        assertEquals(3.toShort(), ok(1.toShort(), "3"))
        assertEquals(3.toByte(), ok(1.toByte(), "3"))
        assertEquals(2.5, ok(1.0, "2.5"))
        assertEquals(2.5f, ok(1.0f, "2.5f"))
        assertEquals(true, ok(false, "TRUE"))
        assertEquals('x', ok('a', "x"))
        assertEquals("two words", ok("s", "two words"))
        assertEquals("", ok("s", "\"\""))
    }

    @Test
    fun rejectsMismatchesWithoutThrowing() {
        assertTrue(fail(1, "abc").contains("Int"))
        assertTrue(fail(false, "yes").contains("true or false"))
        assertTrue(fail('a', "ab").contains("one character"))
        assertTrue(fail(1, "99999999999").contains("Int"))
    }

    @Test
    fun refusesUnsupportedTypes() {
        assertTrue(fail(listOf(1), "[1]").contains("unsupported type"))
        assertTrue(fail(listOf(1), "[1]").contains("verb"))
    }

    @Test
    fun enumsResolveByNameWherePlatformAllows() {
        val outcome = LiteralParser.parseLike(Mode.FAST, "slow")
        if (enumConstantsOf(Mode.FAST) != null) {
            assertEquals(Mode.SLOW, assertIs<LiteralParser.Outcome.Ok>(outcome).value)
            assertTrue(fail(Mode.FAST, "medium").contains("FAST, SLOW"))
        } else {
            assertTrue(assertIs<LiteralParser.Outcome.Fail>(outcome).message.contains("platform"))
        }
    }
}
