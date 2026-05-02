package com.vynatix.vault.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Email(override val value: String) : Civilizable<String>

private object EmailCivilizer : Civilizer<String, Rule<String>, Email> {
    private val nonEmpty = Rule<String> { it.isNotBlank() }
    private val containsAt = Rule<String> { it.contains('@') }
    private val sensibleLength = Rule<String> { it.length in 3..254 }

    override val variations = listOf(
        createVariation({ Email(it) }, allConditions(), nonEmpty, containsAt, sensibleLength),
    )
}

private data class IntNum(override val value: Int) : Civilizable<Int>

private object PositiveIntCivilizer : Civilizer<Int, Rule<Int>, IntNum> {
    override val variations = listOf(
        createVariation({ IntNum(it) }, allConditions(), Rule { it > 0 }),
    )
}

private data class Num(override val value: String) : Civilizable<String>

private object MultiVariationCivilizer : Civilizer<String, Rule<String>, Num> {
    override val variations = listOf(
        createVariation({ Num("int:$it") }, allConditions(), Rule { it.toIntOrNull() != null }),
        createVariation({ Num("double:$it") }, allConditions(), Rule { it.toDoubleOrNull() != null }),
    )
}

private data class Token(override val value: String) : Civilizable<String>

private object EitherTokenCivilizer : Civilizer<String, Rule<String>, Token> {
    override val variations = listOf(
        createVariation(
            { Token(it) },
            anyConditions(),
            Rule { it.startsWith("Bearer ") },
            Rule { it.startsWith("Basic ") },
        ),
    )
}

class CivilizerTest {
    @Test
    fun ofReturnsCivilizedObjectWhenAllRulesPass() {
        val email = EmailCivilizer of "alice@example.com"
        assertEquals("alice@example.com", email.value)
    }

    @Test
    fun ofThrowsWhenNoVariationMatches() {
        val ex = assertFailsWith<IllegalArgumentException> {
            EmailCivilizer of "not-an-email"
        }
        assertTrue(ex.message?.startsWith("No variation found for value not-an-email") == true)
    }

    @Test
    fun ofOrNullReturnsNullOnRejection() {
        assertNull(EmailCivilizer.ofOrNull("not-an-email"))
    }

    @Test
    fun validateReturnsRuleResult() {
        assertTrue(PositiveIntCivilizer.validate(5))
        assertTrue(!PositiveIntCivilizer.validate(0))
        assertTrue(!PositiveIntCivilizer.validate(-1))
    }

    @Test
    fun firstMatchingVariationWins() {
        val intResult = MultiVariationCivilizer of "42"
        assertEquals("int:42", intResult.value)

        val doubleResult = MultiVariationCivilizer of "3.14"
        assertEquals("double:3.14", doubleResult.value)
    }

    @Test
    fun anyConditionsAcceptsIfOneRulePasses() {
        val bearer = EitherTokenCivilizer of "Bearer abc123"
        assertEquals("Bearer abc123", bearer.value)

        val basic = EitherTokenCivilizer of "Basic dXNlcjpwYXNz"
        assertEquals("Basic dXNlcjpwYXNz", basic.value)

        assertFailsWith<IllegalArgumentException> {
            EitherTokenCivilizer of "Digest xyz"
        }
    }

    @Test
    fun allConditionsRequiresEveryRule() {
        // EmailCivilizer chains nonEmpty + containsAt + sensibleLength.
        assertFailsWith<IllegalArgumentException> { EmailCivilizer of " " } // blank
        assertFailsWith<IllegalArgumentException> { EmailCivilizer of "ab" } // no @, too short
        assertFailsWith<IllegalArgumentException> { EmailCivilizer of "@" } // too short
    }
}
