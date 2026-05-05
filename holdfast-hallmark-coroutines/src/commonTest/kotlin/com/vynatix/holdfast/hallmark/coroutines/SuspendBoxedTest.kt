package com.vynatix.holdfast.hallmark.coroutines

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.SpecMode
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.coroutines.SuspendBoxedValidator
import com.vynatix.hallmark.coroutines.SuspendRule
import com.vynatix.hallmark.coroutines.SuspendSpec
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.hallmark.boxed
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

private object UsernameLeafValidator : com.vynatix.hallmark.BoxedValidator<String, Username>() {
    override val specs = listOf(
        com.vynatix.hallmark.Spec(
            listOf(
                object : com.vynatix.hallmark.Rule<String>("username.format", "alphanumeric") {
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

private class UserVault : Store<UserVault>() {
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
        assertEquals(true, r.exception is HallmarkException)
    }
}
