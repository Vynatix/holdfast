package com.vynatix.vault.validation

import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private data class UserEmail(override val value: String) : Validated<String>

private object UserEmailValidator : Validator<String, Rule<String>, UserEmail> {
    override val specs = listOf(
        createSpec(
            { UserEmail(it) },
            allConditions(),
            Rule { it.contains('@') },
            Rule { it.length in 3..254 },
        ),
    )
}

private class UserVault : Vault<UserVault>() {
    val email by state(transformer = ValidatingTransformer(UserEmailValidator)) {
        UserEmailValidator of "init@example.com"
    }
    val displayName by state { "init" }
}

class ValidatingTransformerTest {
    @Test
    fun acceptsValidPrimitiveAndStoresWrappedValue() {
        val vault = UserVault()
        val result = vault action {
            email mutate (UserEmailValidator of "alice@example.com")
        }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("alice@example.com", vault.email.value.value)
    }

    @Test
    fun rejectsConstructorBypassAndRollsBackTransaction() {
        val vault = UserVault()
        vault action { displayName mutate "before" }

        val result = vault action {
            displayName mutate "during"
            email mutate UserEmail("not-an-email")
        }

        assertIs<TransactionResult.Error>(result)
        assertTrue(result.exception is IllegalArgumentException)
        assertEquals("init@example.com", vault.email.value.value)
        assertEquals("before", vault.displayName.value)
    }

    @Test
    fun crossStateAtomicityHoldsAcrossValidationFailure() {
        val vault = UserVault()
        val before = vault.email.value.value

        repeat(3) {
            val r = vault action {
                displayName mutate "user-$it"
                email mutate UserEmail("bad-$it")
            }
            assertIs<TransactionResult.Error>(r)
        }

        assertEquals(before, vault.email.value.value)
        assertEquals("init", vault.displayName.value)
    }
}
