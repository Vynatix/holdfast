package com.vynatix.vault.validation.coroutines

import com.vynatix.validation.Boxed
import com.vynatix.validation.SpecMode
import com.vynatix.validation.ValidationException
import com.vynatix.validation.coroutines.SuspendBoxedValidator
import com.vynatix.validation.coroutines.SuspendRule
import com.vynatix.validation.coroutines.SuspendSpec
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.validation.boxed
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private data class Username(override val value: String) : Boxed<String>

private class UniqueUsernameRule(private val taken: Set<String>) :
    SuspendRule<String>(
        code = "username.unique",
        messageTemplate = "username already taken",
    ) {
    override suspend fun validate(value: String): Boolean {
        delay(1)
        return value !in taken
    }
}

private object UsernameLeafValidator : com.vynatix.validation.BoxedValidator<String, Username>() {
    override val specs = listOf(
        com.vynatix.validation.Spec(
            listOf(
                object : com.vynatix.validation.Rule<String>("username.format", "alphanumeric") {
                    override fun validate(value: String) = value.all { it.isLetterOrDigit() }
                },
            ),
            SpecMode.ALL,
        ) { Username(it) },
    )
}

private class UniqueUsernameValidator(taken: Set<String>) : SuspendBoxedValidator<String, Username>() {
    override val specs = listOf(
        SuspendSpec(listOf(UniqueUsernameRule(taken)), SpecMode.ALL) { Username(it) },
    )
}

private class UserVault : Vault<UserVault>() {
    val username by boxed(UsernameLeafValidator) { "init" }
}

class SuspendBoxedTest {
    @Test
    fun suspendValidateAndMutateAcceptsValidPrimitive() = runTest {
        val v = UserVault()
        val taken = setOf("alice", "bob")
        val r = v.suspendValidateAndMutate(v.username, UniqueUsernameValidator(taken), "carol")
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals("carol", v.username.value.value)
    }

    @Test
    fun suspendValidateAndMutateRejectsAndRollsBack() = runTest {
        val v = UserVault()
        val taken = setOf("alice")
        val r = v.suspendValidateAndMutate(v.username, UniqueUsernameValidator(taken), "alice")
        assertIs<TransactionResult.Error>(r)
        // Initial value preserved
        assertEquals("init", v.username.value.value)
        assertEquals(true, r.exception is ValidationException)
    }
}
