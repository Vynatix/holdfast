package com.vynatix.vault.testing

import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

private class CountVault : Vault<CountVault>() {
    val count by state { 0 }
}

class VaultHandleActionTest {

    @Test
    fun actionCommitsAndReturnsSuccess() = vaultTest {
        val ctr = track(CountVault())
        val result = ctr.action { count mutate 5 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(5, ctr.read { count.value })
    }

    @Test
    fun actionPropagatesThrowAsError() = vaultTest {
        val ctr = track(CountVault())
        val ise = IllegalStateException("boom")
        val result = ctr.action<Unit> { throw ise }
        assertIs<TransactionResult.Error>(result)
        assertSame(ise, result.exception)
    }

    @Test
    fun suspendActionCommitsAndReturnsSuccess() = vaultTest {
        val ctr = track(CountVault())
        val result = ctr.suspendAction {
            delay(0)
            count mutate 7
            count.value
        }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(7, ctr.read { count.value })
    }

    @Test
    fun suspendActionPropagatesThrowAsError() = vaultTest {
        val ctr = track(CountVault())
        val iae = IllegalArgumentException("nope")
        val result = ctr.suspendAction<Unit> { throw iae }
        assertIs<TransactionResult.Error>(result)
        assertSame(iae, result.exception)
    }
}
