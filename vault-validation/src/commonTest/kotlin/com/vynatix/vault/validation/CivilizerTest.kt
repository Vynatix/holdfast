package com.vynatix.vault.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Email(override val value: String) : Civilizable<String>

private object EmailCivilizer : SimpleCivilizer<String, Rule<String>, Email>() {
    override val variations = listOf(
        Variation.of<String, Rule<String>, Email>(
            rule = Rule { it.contains('@') && it.length in 3..254 },
        ) { Email(it) },
    )
}

private data class IntNum(override val value: Int) : Civilizable<Int>

private object PositiveIntCivilizer : SimpleCivilizer<Int, Rule<Int>, IntNum>() {
    override val variations = listOf(
        Variation.of<Int, Rule<Int>, IntNum>(rule = Rule { it > 0 }) { IntNum(it) },
    )
}

private data class Number(override val value: String) : Civilizable<String>

private object MultiVariationCivilizer : SimpleCivilizer<String, Rule<String>, Number>() {
    override val variations = listOf(
        Variation.of<String, Rule<String>, Number>(
            rule = Rule { it.toIntOrNull() != null },
        ) { Number("int:$it") },
        Variation.of<String, Rule<String>, Number>(
            rule = Rule { it.toDoubleOrNull() != null },
        ) { Number("double:$it") },
    )
}

class CivilizerTest {
    @Test
    fun ofReturnsCivilizedObjectWhenRuleMatches() {
        val email = EmailCivilizer of "alice@example.com"
        assertEquals("alice@example.com", email.value)
    }

    @Test
    fun ofThrowsWhenNoVariationMatches() {
        val ex = assertFailsWith<CivilizationException> {
            EmailCivilizer of "not-an-email"
        }
        assertEquals("not-an-email", ex.primitive)
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
    fun allOfConditionRequiresAllPredicates() {
        val condition = allOf<String, Rule<String>>(
            { !it.contains(' ') },
            { it.length > 1 },
        )
        val rule: Rule<String> = Rule { it.contains('@') }

        assertTrue(condition.check("a@b", rule))
        assertTrue(!condition.check("@", rule))
        assertTrue(!condition.check("a @b", rule))
        assertTrue(!condition.check("ab", rule))
    }

    @Test
    fun alwaysValidAlwaysPassesNeverValidNeverPasses() {
        assertTrue(alwaysValid<String>().validate("anything"))
        assertTrue(!neverValid<String>().validate("anything"))
    }
}
