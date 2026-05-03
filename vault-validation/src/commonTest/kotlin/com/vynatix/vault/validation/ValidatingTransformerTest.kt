package com.vynatix.vault.validation

import com.vynatix.validation.Boxed
import com.vynatix.validation.BoxedValidator
import com.vynatix.validation.Spec
import com.vynatix.validation.SpecMode
import com.vynatix.validation.ValidationException
import com.vynatix.validation.rules.MinLengthRule
import com.vynatix.validation.rules.NonBlankRule
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
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

private class UserVault : Vault<UserVault>() {
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
        assertTrue(result.exception is ValidationException)
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
