package com.vynatix.holdfast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private sealed class FixtureEvent {
    data object A : FixtureEvent()
    data object B : FixtureEvent()
    data class Custom(val n: Int) : FixtureEvent()
}

private class FixtureEventfulVault : EventfulStore<FixtureEventfulVault, FixtureEvent>() {
    val s by state { "init" }
    val n by state { 0 }
}

class EventfulVaultTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test fun emitOutsideActionThrows() {
        val v = FixtureEventfulVault()
        val ex = assertFailsWith<IllegalStateException> {
            v.emit(FixtureEvent.A)
        }
        assertTrue(
            ex.message!!.contains("outside of an action"),
            "expected diagnostic mentioning 'outside of an action', got: ${ex.message}",
        )
    }

    @Test fun syncActionEmitDeliversAfterCommit() = runBlocking {
        val v = FixtureEventfulVault()
        val received = mutableListOf<FixtureEvent>()
        val collectJob = testScope.launch {
            v.events.collect { received += it }
        }
        // Allow the launched collector to register before emit. SharedFlow with
        // replay=0 only delivers events emitted after subscription.
        delay(50)

        v action {
            n mutate 5
            emit(FixtureEvent.A)
        }
        withTimeoutOrNull(2_000) {
            while (received.isEmpty()) delay(10)
        }
        collectJob.cancel()
        assertEquals(listOf<FixtureEvent>(FixtureEvent.A), received)
        assertEquals(5, v.n.value)
    }

    @Test fun rollbackDiscardsEvents() = runBlocking {
        val v = FixtureEventfulVault()
        val received = mutableListOf<FixtureEvent>()
        val collectJob = testScope.launch {
            v.events.collect { received += it }
        }
        delay(50)

        val r = v action {
            n mutate 1
            emit(FixtureEvent.A)
            error("boom")
        }
        assertIs<TransactionResult.Error>(r)

        // State rolled back — no commit fired, no event drained.
        assertEquals(0, v.n.value)
        delay(100) // let any spurious emission land
        collectJob.cancel()
        assertTrue(received.isEmpty(), "rollback must discard staged events; got $received")
    }

    @Test fun nestedActionEmitsFireInOrderOnOuterCommit() = runBlocking {
        val v = FixtureEventfulVault()
        val received = mutableListOf<FixtureEvent>()
        val collectJob = testScope.launch {
            v.events.take(2).toList(received)
        }
        delay(50)

        v action {
            emit(FixtureEvent.A)
            this@action action {
                emit(FixtureEvent.B)
            }
        }
        withTimeoutOrNull(2_000) { collectJob.join() }
        assertEquals(listOf<FixtureEvent>(FixtureEvent.A, FixtureEvent.B), received)
    }

    @Test fun nestedActionInnerRollbackKeepsOuterEvents() = runBlocking {
        // Inner rollback discards the inner's pendingEvents but keeps the
        // outer's. Outer commit fires the outer's events.
        val v = FixtureEventfulVault()
        val received = mutableListOf<FixtureEvent>()
        val collectJob = testScope.launch {
            v.events.collect { received += it }
        }
        delay(50)

        v action {
            emit(FixtureEvent.A)
            val innerResult = this@action action {
                emit(FixtureEvent.B)
                error("inner failure")
            }
            assertIs<TransactionResult.Error>(innerResult)
        }
        withTimeoutOrNull(2_000) {
            while (received.isEmpty()) delay(10)
        }
        delay(100) // let any spurious extra events land
        collectJob.cancel()
        assertEquals(listOf<FixtureEvent>(FixtureEvent.A), received)
    }

    @Test fun customDataEventsRoundTrip() = runBlocking {
        val v = FixtureEventfulVault()
        val received = mutableListOf<FixtureEvent>()
        val collectJob = testScope.launch {
            v.events.take(3).toList(received)
        }
        delay(50)

        v action {
            emit(FixtureEvent.Custom(1))
            emit(FixtureEvent.Custom(2))
            emit(FixtureEvent.Custom(3))
        }
        withTimeoutOrNull(2_000) { collectJob.join() }
        val expected: List<FixtureEvent> = listOf(
            FixtureEvent.Custom(1),
            FixtureEvent.Custom(2),
            FixtureEvent.Custom(3),
        )
        assertEquals(expected, received)
    }
}
