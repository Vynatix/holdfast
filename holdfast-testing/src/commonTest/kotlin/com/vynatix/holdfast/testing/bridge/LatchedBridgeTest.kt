package com.vynatix.vault.testing.bridge

import com.vynatix.vault.Vault
import com.vynatix.vault.testing.matcher.shouldBeSuccess
import com.vynatix.vault.testing.vaultTest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class LatchVault : Vault<LatchVault>() {
    val payload by state { "init" }
}

class LatchedBridgeTest {

    @Test
    fun publishedListInitiallyEmpty() {
        val bridge = LatchedBridge<String>(initial = "init")
        assertTrue(bridge.published.isEmpty())
        assertNull(bridge.lastPublished)
    }

    @Test
    fun publishRecordsValueAndReturnsTrue() {
        val bridge = LatchedBridge<String>(initial = "init")
        assertTrue(bridge.publish("v1"))
        assertTrue(bridge.publish("v2"))
        assertEquals(listOf("v1", "v2"), bridge.published)
        assertEquals("v2", bridge.lastPublished)
    }

    @Test
    fun observeDoesNotReplayInitialOnAttach() {
        val bridge = LatchedBridge<String>(initial = "init")
        val received = mutableListOf<String>()
        bridge.observe { received.add(it) }
        // Unlike RecordingBridge, LatchedBridge does NOT replay initial.
        assertTrue(received.isEmpty())
    }

    @Test
    fun simulateInboundFeedsAttachedObserver() {
        val bridge = LatchedBridge<String>(initial = "init")
        val received = mutableListOf<String>()
        bridge.observe { received.add(it) }
        bridge.simulateInbound("incoming")
        assertEquals(listOf("incoming"), received)
    }

    @Test
    fun releasePublishIsNoOp() {
        val bridge = LatchedBridge<String>(initial = "init")
        // Should not throw, should not affect state.
        bridge.releasePublish()
        bridge.releasePublish()
        assertTrue(bridge.published.isEmpty())
    }

    @Test
    fun awaitPublishAttemptResumesOnNextPublish() = vaultTest {
        val bridge = LatchedBridge<String>(initial = "init")

        // Schedule a publish a few virtual ticks from now.
        val publishJob = backgroundScope.launch {
            delay(10.milliseconds)
            bridge.publish("scheduled")
        }

        val seen = bridge.awaitPublishAttempt()
        assertEquals("scheduled", seen)
        publishJob.join()
    }

    @Test
    fun awaitPublishAttemptIgnoresPastPublishes() = vaultTest {
        val bridge = LatchedBridge<String>(initial = "init")
        // Past publishes do NOT satisfy a subsequent awaitPublishAttempt.
        bridge.publish("past1")
        bridge.publish("past2")

        val publishJob = backgroundScope.launch {
            delay(10.milliseconds)
            bridge.publish("future")
        }

        val seen = bridge.awaitPublishAttempt()
        assertEquals("future", seen)
        publishJob.join()
    }

    @Test
    fun multipleConcurrentAwaitersAllResumeOnSinglePublish() = vaultTest {
        val bridge = LatchedBridge<Int>(initial = 0)

        // Use backgroundScope so the awaiters participate in the test
        // scheduler's virtual time; that lets us deterministically advance
        // through the publish without a Dispatchers.Default real-time race.
        val a1 = backgroundScope.async { bridge.awaitPublishAttempt() }
        val a2 = backgroundScope.async { bridge.awaitPublishAttempt() }
        val a3 = backgroundScope.async { bridge.awaitPublishAttempt() }

        // Tiny virtual delay so the awaiters subscribe before publish.
        delay(10.milliseconds)
        bridge.publish(42)

        // No need for withTimeout — virtual time is deterministic.
        assertEquals(42, a1.await())
        assertEquals(42, a2.await())
        assertEquals(42, a3.await())
    }

    @Test
    fun integrationWithVaultRecordsPublish() = vaultTest {
        val bridge = LatchedBridge<String>(initial = "init")
        val ctr = track(
            LatchVault().also { v ->
                v { payload bridge bridge }
            },
        )
        ctr.action { payload mutate "first" }.shouldBeSuccess()
        ctr.action { payload mutate "second" }.shouldBeSuccess()

        assertEquals(listOf("first", "second"), bridge.published)
    }
}
