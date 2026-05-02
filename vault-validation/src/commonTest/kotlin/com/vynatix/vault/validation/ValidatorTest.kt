package com.vynatix.vault.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Email(override val value: String) : Boxed<String>

private object EmailValidator : Validator<String, Rule<String>, Email> {
    private val nonEmpty = Rule<String> { it.isNotBlank() }
    private val containsAt = Rule<String> { it.contains('@') }
    private val sensibleLength = Rule<String> { it.length in 3..254 }

    override val specs = listOf(
        createSpec({ Email(it) }, allConditions(), nonEmpty, containsAt, sensibleLength),
    )
}

private data class IntNum(override val value: Int) : Boxed<Int>

private object PositiveIntValidator : Validator<Int, Rule<Int>, IntNum> {
    override val specs = listOf(
        createSpec({ IntNum(it) }, allConditions(), Rule { it > 0 }),
    )
}

private data class Num(override val value: String) : Boxed<String>

private object MultiSpecValidator : Validator<String, Rule<String>, Num> {
    override val specs = listOf(
        createSpec({ Num("int:$it") }, allConditions(), Rule { it.toIntOrNull() != null }),
        createSpec({ Num("double:$it") }, allConditions(), Rule { it.toDoubleOrNull() != null }),
    )
}

private data class Token(override val value: String) : Boxed<String>

private object EitherTokenValidator : Validator<String, Rule<String>, Token> {
    override val specs = listOf(
        createSpec(
            { Token(it) },
            anyConditions(),
            Rule { it.startsWith("Bearer ") },
            Rule { it.startsWith("Basic ") },
        ),
    )
}

class ValidatorTest {
    @Test
    fun ofReturnsWrappedObjectWhenAllRulesPass() {
        val email = EmailValidator of "alice@example.com"
        assertEquals("alice@example.com", email.value)
    }

    @Test
    fun ofThrowsWhenNoSpecMatches() {
        val ex = assertFailsWith<IllegalArgumentException> {
            EmailValidator of "not-an-email"
        }
        assertTrue(ex.message?.startsWith("No spec matched value not-an-email") == true)
    }

    @Test
    fun ofOrNullReturnsNullOnRejection() {
        assertNull(EmailValidator.ofOrNull("not-an-email"))
    }

    @Test
    fun validateReturnsRuleResult() {
        assertTrue(PositiveIntValidator.validate(5))
        assertTrue(!PositiveIntValidator.validate(0))
        assertTrue(!PositiveIntValidator.validate(-1))
    }

    @Test
    fun firstMatchingSpecWins() {
        val intResult = MultiSpecValidator of "42"
        assertEquals("int:42", intResult.value)

        val doubleResult = MultiSpecValidator of "3.14"
        assertEquals("double:3.14", doubleResult.value)
    }

    @Test
    fun anyConditionsAcceptsIfOneRulePasses() {
        val bearer = EitherTokenValidator of "Bearer abc123"
        assertEquals("Bearer abc123", bearer.value)

        val basic = EitherTokenValidator of "Basic dXNlcjpwYXNz"
        assertEquals("Basic dXNlcjpwYXNz", basic.value)

        assertFailsWith<IllegalArgumentException> {
            EitherTokenValidator of "Digest xyz"
        }
    }

    @Test
    fun allConditionsRequiresEveryRule() {
        // EmailValidator chains nonEmpty + containsAt + sensibleLength.
        assertFailsWith<IllegalArgumentException> { EmailValidator of " " } // blank
        assertFailsWith<IllegalArgumentException> { EmailValidator of "ab" } // no @, too short
        assertFailsWith<IllegalArgumentException> { EmailValidator of "@" } // too short
    }
}
