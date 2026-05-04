package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.EventfulHoldfast
import com.vynatix.holdfast.TransactionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private sealed class OrderEvent {
    data object Saved : OrderEvent()
    data object Updated : OrderEvent()
}

private class OrderingVault : EventfulHoldfast<OrderingVault, OrderEvent>() {
    val s by state { "init" }
    val n by state { 0 }
}

/**
 * The master verticality test for issue 14: a collector subscribed to BOTH
 * `state.asFlow()` and `vault.events` MUST observe the state value before the
 * event in commit-phase ordering.
 *
 * In `suspendAction`, the commit fans out:
 *  1. observers fire (post `applyCommittedRaw`),
 *  2. bridges publish (sync `Bridge.publish` and/or awaited `SuspendingBridge.publishAwaited`),
 *  3. events drain to the `events` SharedFlow.
 */
class EventfulVaultCommitOrderingTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test fun observersFireBeforeEventsInCommitPhaseOrdering() = runBlocking {
        val v = OrderingVault()
        val seen = mutableListOf<String>()
        val seenLock = object : kotlinx.atomicfu.locks.SynchronizedObject() {}
        fun record(item: String) {
            // Single-thread test scope, but be paranoid.
            kotlinx.atomicfu.locks.synchronized(seenLock) { seen += item }
        }

        val stateJob = testScope.launch {
            v.s.asFlow().collect { value ->
                if (value == "new") record("state=new")
            }
        }
        val eventJob = testScope.launch {
            v.events.collect { event ->
                record("event=${event::class.simpleName}")
            }
        }
        // Allow both launched collectors to register before commit emits.
        delay(100)

        val r = v.suspendAction {
            s mutate "new"
            emit(OrderEvent.Saved)
        }
        assertIs<TransactionResult.Success<*>>(r)

        // Wait for both signals to land.
        withTimeoutOrNull(2_000) {
            while (kotlinx.atomicfu.locks.synchronized(seenLock) {
                    !(seen.any { it == "state=new" } && seen.any { it == "event=Saved" })
                }
            ) {
                delay(10)
            }
        }
        stateJob.cancel()
        eventJob.cancel()

        val snapshot = kotlinx.atomicfu.locks.synchronized(seenLock) { seen.toList() }
        val stateIndex = snapshot.indexOfFirst { it == "state=new" }
        val eventIndex = snapshot.indexOfFirst { it == "event=Saved" }
        assertTrue(stateIndex >= 0, "did not observe state=new in $snapshot")
        assertTrue(eventIndex >= 0, "did not observe event=Saved in $snapshot")
        assertTrue(
            stateIndex < eventIndex,
            "commit-phase ordering: state must be observed before event; saw $snapshot",
        )
    }

    @Test fun emitInsideSuspendActionDelivers() = runBlocking {
        val v = OrderingVault()
        val collectorJob = testScope.launch {
            // First-collected event terminates this collect.
            v.events.first()
        }
        // Ensure subscriber is registered before emitting.
        delay(50)

        val r = v.suspendAction {
            n mutate 7
            emit(OrderEvent.Updated)
        }
        assertIs<TransactionResult.Success<*>>(r)
        withTimeoutOrNull(2_000) { collectorJob.join() }
        assertTrue(collectorJob.isCompleted, "collector should have received event")
        assertEquals(7, v.n.value)
    }

    @Test fun suspendActionRollbackDiscardsEvents() = runBlocking {
        val v = OrderingVault()
        val received = mutableListOf<OrderEvent>()
        val collectJob = testScope.launch {
            v.events.collect { received += it }
        }
        delay(50)

        val r = v.suspendAction {
            n mutate 99
            emit(OrderEvent.Saved)
            error("rolled back")
        }
        assertIs<TransactionResult.Error>(r)

        // Wait briefly to make sure no event arrives.
        delay(100)
        collectJob.cancel()
        assertEquals(0, v.n.value)
        assertTrue(received.isEmpty(), "rollback must discard events; got $received")
    }

    @Test fun losslessSuspendActionUnderSlowCollector() = runBlocking {
        // SUSPEND-policy buffer (capacity 2 here) means a slow collector
        // back-pressures the suspending commit thread. Emit more events than
        // buffer fits and verify the collector eventually receives all of them.
        val v = SmallBufferVault()
        val received = mutableListOf<Int>()
        val collectJob = testScope.launch {
            v.events.collect { event ->
                received += event.n
                // Slow collector to force back-pressure.
                delay(20)
            }
        }
        delay(50)

        val r = v.suspendAction {
            // Five events; buffer is 2, so suspend at least once.
            for (i in 1..5) emit(SmallEvent(i))
        }
        assertIs<TransactionResult.Success<*>>(r)

        // Wait for all 5 to land (they CAN'T be dropped; SUSPEND policy).
        withTimeoutOrNull(5_000) {
            while (received.size < 5) delay(20)
        }
        collectJob.cancel()
        assertEquals(listOf(1, 2, 3, 4, 5), received)
    }

    private data class SmallEvent(val n: Int)

    private class SmallBufferVault :
        EventfulHoldfast<SmallBufferVault, SmallEvent>(
            extraBufferCapacity = 2,
            onBufferOverflow = BufferOverflow.SUSPEND,
        ) {
        val tick by state { 0 }
    }
}
