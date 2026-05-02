package com.vynatix.vault.coroutines

import com.vynatix.vault.Vault
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue 04 — asFlow() lossless-conflated contract.
 *
 * Behavior change shipped as a fix. 1.x backed asFlow on `callbackFlow` with default
 * BUFFERED capacity (64); fast emitter / slow collector silently dropped values past
 * the backlog. 2.0 backs on `MutableSharedFlow(replay=1, extraBufferCapacity=0,
 * onBufferOverflow=DROP_OLDEST)`: producer never blocks, latest value always
 * recoverable from the replay slot, intermediate values may be conflated.
 */
private class FlowConflatedVault : Vault<FlowConflatedVault>() {
    val n by state { 0 }
}

class AsFlowLosslessConflatedTest {

    @Test
    fun fast_emitter_slow_collector_eventually_delivers_latest() = runBlocking {
        val v = FlowConflatedVault()
        val seen = mutableListOf<Int>()
        val job = launch {
            v.n.asFlow().collect { value ->
                seen.add(value)
                delay(50) // deliberately slow consumer
            }
        }
        // Let the collector subscribe and consume the initial replay slot.
        delay(20)
        // Burst-emit 100 values with no inter-emit delay.
        repeat(100) { i ->
            v action { n mutate (i + 1) }
        }
        // Wait long enough for the slow collector to drain to the latest value.
        withTimeout(5_000) {
            while (seen.lastOrNull() != 100) delay(20)
        }
        job.cancel()

        // Contract: latest value always delivered.
        assertEquals(100, seen.last(), "expected latest value 100; saw ${seen.last()}")
        // Contract: conflation is observable — slow consumer cannot have seen all 100.
        assertTrue(
            seen.size < 100,
            "expected conflation under slow consumer; seen.size=${seen.size}",
        )
    }

    @Test
    fun late_subscriber_sees_value_at_subscribe_time_via_replay_slot() = runBlocking {
        val v = FlowConflatedVault()
        // Commit five times BEFORE any subscriber.
        repeat(5) { i -> v action { n mutate (i + 1) } }
        assertEquals(5, v.n.value)
        // First emission to a brand-new subscriber is the value at subscribe time.
        val first = v.n.asFlow().first()
        assertEquals(5, first)
    }

    @Test
    fun collector_cancellation_disposes_underlying_observer_subscription() = runBlocking {
        val v = FlowConflatedVault()
        val job = launch {
            // Forever collector — only stops on cancel.
            v.n.asFlow().collect { /* ignore */ }
        }
        delay(50) // ensure subscribed
        job.cancel()
        delay(50) // let cleanup run

        // After cancel, further commits must not crash and must not be observed by
        // the cancelled flow. We can't directly inspect observer count from outside,
        // but we can verify by collecting a fresh flow that the value flows are
        // independent and disposing one doesn't disrupt subsequent subscribers.
        v action { n mutate 42 }
        val first = v.n.asFlow().first()
        assertEquals(42, first)
    }
}
