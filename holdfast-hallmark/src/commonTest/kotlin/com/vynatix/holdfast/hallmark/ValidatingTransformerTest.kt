package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.BoxedValidator
import com.vynatix.hallmark.Spec
import com.vynatix.hallmark.SpecMode
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.rules.MinLengthRule
import com.vynatix.hallmark.rules.NonBlankRule
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Holdfast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private data class UserEmail(override val value: String) : Boxed<String>

private object UserEmailValidator : BoxedValidator<String, UserEmail>() {
    override val specs = listOf(
        Spec(listOf(NonBlankRule(), MinLengthRule(3)), SpecMode.ALL) { UserEmail(it) },
    )
}

private class UserVault : Holdfast<UserVault>() {
    val email by boxed(UserEmailValidator) { "init@example.com" }
    val displayName by state { "init" }
}

class ValidatingTransformerTest {
    @Test
    fun acceptsValidPrimitiveAndStoresWrappedValue() {
        val v = UserVault()
        val result = v action {
            email mutate (UserEmailValidator of "alice@example.com")
        }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("alice@example.com", v.email.value.value)
    }

    @Test
    fun rejectsConstructorBypassAndRollsBackTransaction() {
        val v = UserVault()
        v action { displayName mutate "before" }

        val result = v action {
            displayName mutate "during"
            email mutate UserEmail(" ") // bypasses validator; transformer rejects
        }

        assertIs<TransactionResult.Error>(result)
        assertTrue(result.exception is HallmarkException)
        assertEquals("init@example.com", v.email.value.value)
        assertEquals("before", v.displayName.value)
    }

    @Test
    fun boxedFactoryWiresTransformerForBypassRejection() {
        val v = UserVault()
        // Using the validator directly produces a wrapped value.
        val civilized = UserEmailValidator of "alice@example.com"
        assertEquals(UserEmail("alice@example.com"), civilized)
        // Constructor-bypass writes still rejected via the transformer wired by boxed().
        val rejected = v action { email mutate UserEmail("ab") }
        assertIs<TransactionResult.Error>(rejected)
    }
}
