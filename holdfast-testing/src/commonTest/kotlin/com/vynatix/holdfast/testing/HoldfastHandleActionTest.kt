package com.vynatix.holdfast.testing

import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Holdfast
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

private class CountVault : Holdfast<CountVault>() {
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
        // Strict scope-exit guard requires the error to be acknowledged.
        ctr.consumeAllPendingErrors()
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
        // Strict scope-exit guard requires the error to be acknowledged.
        ctr.consumeAllPendingErrors()
    }
}
