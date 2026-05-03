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

private data class HandleEmail(override val value: String) : Boxed<String>

private object HandleEmailValidator : BoxedValidator<String, HandleEmail>() {
    override val specs = listOf(
        Spec(listOf(NonBlankRule(), MinLengthRule(3)), SpecMode.ALL) { HandleEmail(it) },
    )
}

private class HandleVault : Vault<HandleVault>() {
    val email by boxedHandle(HandleEmailValidator) { "init@example.com" }
}

class BoxedHandleTest {
    @Test
    fun handleExposesStateAndValidator() {
        val v = HandleVault()
        // Static type of email is BoxedHandle<String, HandleEmail>; validator and state are accessible.
        val handle = v.email
        assertEquals(HandleEmailValidator, handle.validator)
        assertEquals("init@example.com", handle.state.value.value)
    }

    @Test
    fun civilizeAndMutateRoundTripsValue() {
        val v = HandleVault()
        val r = v action {
            email.state mutate email.civilize("alice@example.com")
        }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals("alice@example.com", v.email.state.value.value)
    }

    @Test
    fun civilizeRejectsInvalidPrimitive() {
        val v = HandleVault()
        // civilize itself throws — caller can choose to do their own try/catch
        val r = v action {
            // Wrap in try since civilize throws even before mutate is called.
            email.state mutate email.civilize("ab") // too short
        }
        assertIs<TransactionResult.Error>(r)
        assertTrue(r.exception is ValidationException)
        // Initial value preserved
        assertEquals("init@example.com", v.email.state.value.value)
    }

    @Test
    fun assignInfixCivilizesAndMutatesInsideAction() {
        val v = HandleVault()
        val r = v action {
            email assign "alice@example.com"
        }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals("alice@example.com", v.email.state.value.value)
    }

    @Test
    fun assignInfixRollsBackOnInvalidPrimitive() {
        val v = HandleVault()
        val r = v action {
            email assign "ab" // too short — civilize throws ValidationException
        }
        assertIs<TransactionResult.Error>(r)
        assertTrue(r.exception is ValidationException)
        assertEquals("init@example.com", v.email.state.value.value)
    }
}
