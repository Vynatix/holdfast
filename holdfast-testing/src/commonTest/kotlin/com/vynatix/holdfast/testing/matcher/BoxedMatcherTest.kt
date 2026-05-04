package com.vynatix.vault.testing.matcher

import com.vynatix.validation.Boxed
import com.vynatix.vault.Vault
import com.vynatix.vault.testing.vaultTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

private data class Email(override val value: String) : Boxed<String>
private data class PositiveInt(override val value: Int) : Boxed<Int>

private class UserVault : Vault<UserVault>() {
    val email by state { Email("seed@example.com") }
    val ageBox by state { PositiveInt(1) }
}

class BoxedMatcherTest {

    @Test
    fun shouldBeBoxedAsPassesOnExactPrimitive() = vaultTest {
        val ctr = track(UserVault())
        ctr.action { email mutate Email("alice@example.com") }.shouldBeSuccess()
        ctr.read { email } shouldBeBoxedAs "alice@example.com"
    }

    @Test
    fun shouldBeBoxedAsPassesForNonStringPrimitive() = vaultTest {
        val ctr = track(UserVault())
        ctr.action { ageBox mutate PositiveInt(42) }.shouldBeSuccess()
        ctr.read { ageBox } shouldBeBoxedAs 42
    }

    @Test
    fun shouldBeBoxedAsFailsOnPrimitiveMismatch() = vaultTest {
        val ctr = track(UserVault())
        ctr.action { email mutate Email("alice@example.com") }.shouldBeSuccess()
        val err = assertFailsWith<AssertionError> {
            ctr.read { email } shouldBeBoxedAs "bob@example.com"
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "expected=bob@example.com")
        assertContains(msg, "actual=alice@example.com")
    }

    @Test
    fun shouldBeBoxedAsFailureMessageIncludesWrapperClass() = vaultTest {
        val ctr = track(UserVault())
        val err = assertFailsWith<AssertionError> {
            ctr.read { email } shouldBeBoxedAs "different@example.com"
        }
        // Wrapper class simpleName surfaces so users can tell which Boxed
        // implementation produced the value.
        assertContains(err.message.orEmpty(), "Email")
    }
}
