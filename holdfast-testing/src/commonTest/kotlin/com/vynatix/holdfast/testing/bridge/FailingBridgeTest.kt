package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.storeTest
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
    fun integrationPublishFailureIsReportedWithoutTearingTheCommit() =
        storeTest {
            val cause = IllegalStateException("kv unreachable")
            val bridge = FailingBridge<Int>(initial = 0, failOn = FailingBridge.FailureMode.Publish, cause = cause)
            val reported = mutableListOf<Throwable>()

            val vault =
                CounterVault().also { v ->
                    v.uncaughtObserverHandler = { reported += it }
                    v { count bridge bridge }
                }
            val ctr = track(vault)

            // A bridge is external sync, not a transaction participant. Its
            // publish runs in post-commit fanout, after every state in the
            // transaction is already applied, so a throw there is reported
            // through `uncaughtObserverHandler` and cannot fail the commit or
            // tear it part-way. See CommitAtomicityTest in :holdfast.
            val result = ctr.action { count mutate 1 }
            result.shouldBeSuccess()
            assertEquals(1, vault.count.value, "the committed value stands even though the publish failed")
            assertSame(cause, reported.single(), "the publish failure is surfaced, not swallowed")
        }
}
