package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.TransactionException
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.matcher.shouldBeError
import com.vynatix.holdfast.testing.vaultTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CounterVault : Store<CounterVault>() {
    val count by state { 0 }
}

class FailingBridgeTest {

    @Test
    fun publishModeAttachSucceedsButPublishThrows() {
        val cause = IllegalStateException("publish boom")
        val bridge = FailingBridge<Int>(initial = 0, failOn = FailingBridge.FailureMode.Publish, cause = cause)

        // observe should succeed and replay initial.
        val received = mutableListOf<Int>()
        bridge.observe { received.add(it) }
        assertEquals(listOf(0), received)

        // publish should throw the configured cause.
        val ex = assertFailsWith<IllegalStateException> { bridge.publish(1) }
        assertSame(cause, ex)
    }

    @Test
    fun observeModeAttachThrowsButPublishSucceeds() {
        val cause = RuntimeException("observe boom")
        val bridge = FailingBridge<Int>(initial = 0, failOn = FailingBridge.FailureMode.Observe, cause = cause)

        val ex = assertFailsWith<RuntimeException> { bridge.observe { /* never invoked */ } }
        assertSame(cause, ex)

        // publish still works.
        assertTrue(bridge.publish(1))
    }

    @Test
    fun bothModeAllPathsThrow() {
        val cause = IllegalArgumentException("both boom")
        val bridge = FailingBridge<Int>(initial = 0, failOn = FailingBridge.FailureMode.Both, cause = cause)

        assertFailsWith<IllegalArgumentException> { bridge.observe { } }
        assertFailsWith<IllegalArgumentException> { bridge.publish(1) }
    }

    @Test
    fun defaultCauseIsRuntimeExceptionWithMessage() {
        val bridge = FailingBridge<Int>(initial = 0, failOn = FailingBridge.FailureMode.Publish)
        val ex = assertFailsWith<RuntimeException> { bridge.publish(1) }
        assertEquals("FailingBridge", ex.message)
    }

    @Test
    fun integrationPublishFailureSurfacesAsTransactionError() = vaultTest {
        val cause = IllegalStateException("kv unreachable")
        val bridge = FailingBridge<Int>(initial = 0, failOn = FailingBridge.FailureMode.Publish, cause = cause)

        val ctr = track(
            CounterVault().also { v ->
                v { count bridge bridge }
            },
        )

        // The bridge throws during commit-phase publish; vault wraps the
        // throw in TransactionException ("Commit failed") and surfaces it as
        // a TransactionResult.Error. The transaction status is Failed (not
        // RolledBack — it's a commit-time error, past the body-throw rollback
        // path), so we use shouldBeError rather than shouldRollbackWith.
        val result = ctr.action { count mutate 1 }
        val err = result.shouldBeError<TransactionException>()
        // The original publish failure is the cause of the wrapping exception.
        assertSame(cause, err.exception.cause)
    }
}
