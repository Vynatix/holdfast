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

private sealed class SupportEvent {
    data object A : SupportEvent()

    data object B : SupportEvent()

    data class Custom(
        val n: Int,
    ) : SupportEvent()
}

/**
 * Store that mixes in [Eventful] via [EventfulSupport] delegation. The class
 * extends [Store] (not [EventfulStore]) and binds the support helper in init —
 * the contract proven by the test is that this composition delivers the same
 * commit-phase ordering as [EventfulStore].
 *
 * The constructor takes the [EventfulSupport] as a private parameter so the
 * same instance is referenced by both the supertype delegation and the
 * `init`-block binding. Default-argument syntax keeps the call site clean:
 * `SupportVault()`.
 */
private class SupportVault private constructor(
    private val support: EventfulSupport<SupportEvent>,
) : Store<SupportVault>(),
    Eventful<SupportEvent> by support {
    constructor() : this(EventfulSupport())

    val n by state { 0 }

    init {
        @Suppress("LeakingThis")
        support.bindStore(this)
    }
}

class EventfulSupportTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test fun emitOutsideActionThrows() {
        val v = SupportVault()
        val ex =
            assertFailsWith<IllegalStateException> {
                v.emit(SupportEvent.A)
            }
        assertTrue(
            ex.message!!.contains("outside of an action"),
            "expected diagnostic mentioning 'outside of an action', got: ${ex.message}",
        )
    }

    @Test fun syncActionEmitDeliversAfterCommit() =
        runBlocking {
            val v = SupportVault()
            val received = mutableListOf<SupportEvent>()
            val collectJob =
                testScope.launch {
                    v.events.collect { received += it }
                }
            delay(50)

            v action {
                n mutate 5
                emit(SupportEvent.A)
            }
            withTimeoutOrNull(2_000) {
                while (received.isEmpty()) delay(10)
            }
            collectJob.cancel()
            assertEquals(listOf<SupportEvent>(SupportEvent.A), received)
            assertEquals(5, v.n.value)
        }

    @Test fun rollbackDiscardsEvents() =
        runBlocking {
            val v = SupportVault()
            val received = mutableListOf<SupportEvent>()
            val collectJob =
                testScope.launch {
                    v.events.collect { received += it }
                }
            delay(50)

            val r =
                v action {
                    n mutate 1
                    emit(SupportEvent.A)
                    error("boom")
                }
            assertIs<TransactionResult.Error>(r)

            // State rolled back — no commit fired, no event drained.
            assertEquals(0, v.n.value)
            delay(100) // let any spurious emission land
            collectJob.cancel()
            assertTrue(received.isEmpty(), "rollback must discard staged events; got $received")
        }

    @Test fun stateEffectFiresBeforeEventCollector() =
        runBlocking {
            // Master verticality: a collector subscribed to BOTH the state and the
            // events sees the state value before the event — same as EventfulStore.
            // We use `effect` (sync state observer) rather than asFlow so the order
            // of observation is deterministic relative to commit-phase fanout.
            val v = SupportVault()
            // Drop the initial firing of effect (fires once with current value on
            // subscribe — see Effect.kt KDoc). Only commit-time entries are scored.
            val trace = mutableListOf<String>()
            var skippedInitial = false
            val disposable =
                v.n effect {
                    if (skippedInitial) trace += "state=$this"
                    skippedInitial = true
                }
            val collectJob =
                testScope.launch {
                    v.events.collect { trace += "event=$it" }
                }
            delay(50)

            v action {
                n mutate 7
                emit(SupportEvent.A)
            }
            withTimeoutOrNull(2_000) {
                while (trace.size < 2) delay(10)
            }
            delay(100)
            disposable.dispose()
            collectJob.cancel()

            // effect fires SYNCHRONOUSLY during commit's observer fanout, so it lands
            // before the event hits the SharedFlow collector. Both must be present
            // and in this order.
            assertEquals(2, trace.size, "expected exactly state + event in trace; got $trace")
            assertTrue(
                trace[0] == "state=7" && trace[1] == "event=${SupportEvent.A}",
                "state effect must precede events collector; got $trace",
            )
        }

    @Test fun multipleEventsDrainInOrder() =
        runBlocking {
            val v = SupportVault()
            val received = mutableListOf<SupportEvent>()
            val collectJob =
                testScope.launch {
                    v.events.take(3).toList(received)
                }
            delay(50)

            v action {
                emit(SupportEvent.Custom(1))
                emit(SupportEvent.Custom(2))
                emit(SupportEvent.Custom(3))
            }
            withTimeoutOrNull(2_000) { collectJob.join() }
            val expected: List<SupportEvent> =
                listOf(
                    SupportEvent.Custom(1),
                    SupportEvent.Custom(2),
                    SupportEvent.Custom(3),
                )
            assertEquals(expected, received)
        }

    @Test fun nestedActionEmitsFireOnOuterCommit() =
        runBlocking {
            val v = SupportVault()
            val received = mutableListOf<SupportEvent>()
            val collectJob =
                testScope.launch {
                    v.events.take(2).toList(received)
                }
            delay(50)

            v action {
                emit(SupportEvent.A)
                this@action action {
                    emit(SupportEvent.B)
                }
            }
            withTimeoutOrNull(2_000) { collectJob.join() }
            assertEquals(listOf<SupportEvent>(SupportEvent.A, SupportEvent.B), received)
        }
}
