package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.BoxedValidator
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.Spec
import com.vynatix.hallmark.SpecMode
import com.vynatix.hallmark.rules.MinLengthRule
import com.vynatix.hallmark.rules.NonBlankRule
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private data class HandleEmail(
    override val value: String,
) : Boxed<String>

private object HandleEmailValidator : BoxedValidator<String, HandleEmail>() {
    override val specs =
        listOf(
            Spec(listOf(NonBlankRule(), MinLengthRule(3)), SpecMode.ALL) { HandleEmail(it) },
        )
}

private class HandleVault : Store<HandleVault>() {
    val email by boxedHandle(HandleEmailValidator) { "init@example.com" }
}

class BoxedHandleTest {
    @Test
    fun handleExposesStateAndValidator() {
        val v = HandleVault()
        // Static type of email is BoxedHandle<HandleVault, String, HandleEmail>; validator and state are accessible.
        val handle = v.email
        assertEquals(HandleEmailValidator, handle.validator)
        assertEquals("init@example.com", handle.state.value.value)
    }

    @Test
    fun civilizeAndMutateRoundTripsValue() {
        val v = HandleVault()
        val r =
            v action {
                email.state mutate email.civilize("alice@example.com")
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals("alice@example.com", v.email.state.value.value)
    }

    @Test
    fun civilizeRejectsInvalidPrimitive() {
        val v = HandleVault()
        // civilize itself throws — caller can choose to do their own try/catch
        val r =
            v action {
                // Wrap in try since civilize throws even before mutate is called.
                email.state mutate email.civilize("ab") // too short
            }
        assertIs<TransactionResult.Error>(r)
        assertTrue(r.exception is HallmarkException)
        // Initial value preserved
        assertEquals("init@example.com", v.email.state.value.value)
    }

    @Test
    fun assignInfixCivilizesAndMutatesInsideAction() {
        val v = HandleVault()
        val r =
            v action {
                email assign "alice@example.com"
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals("alice@example.com", v.email.state.value.value)
    }

    @Test
    fun assignInfixRollsBackOnInvalidPrimitive() {
        val v = HandleVault()
        val r =
            v action {
                email assign "ab" // too short — civilize throws HallmarkException
            }
        assertIs<TransactionResult.Error>(r)
        assertTrue(r.exception is HallmarkException)
        assertEquals("init@example.com", v.email.state.value.value)
    }

    @Test
    fun assignOutsideAnActionThrowsTeachingError() {
        val v = HandleVault()
        // Providing the Store context WITHOUT opening an action: the runtime gate
        // must refuse rather than let `mutate` synthesize a silent one-shot.
        val ex =
            assertFailsWith<IllegalStateException> {
                with(v) { v.email assign "alice@example.com" }
            }
        assertTrue(ex.message!!.contains("action"), "message should teach the action requirement: ${ex.message}")
        // No silent commit leaked through.
        assertEquals("init@example.com", v.email.state.value.value)
    }
}
