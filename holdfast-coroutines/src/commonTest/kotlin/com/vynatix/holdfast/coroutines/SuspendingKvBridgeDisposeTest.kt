package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * F15 — `SuspendingKvBridge` is [com.vynatix.holdfast.Disposable]: [dispose]
 * shuts down the drainer (after the last conflated value drains), cancels
 * in-flight load-on-attach jobs, and turns `publish`/`observe` into no-ops.
 */
private class DisposeVault : Store<DisposeVault>() {
    val s by state { "init" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingKvBridgeDisposeTest {
    @Test
    fun publishAfterDisposeReturnsFalseAndDoesNotPersist() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val bridge = store.suspendingBridge("k", StringCodec, scope = TestScope(testScheduler))

            bridge.dispose()

            val accepted = bridge.publish("v")
            advanceUntilIdle()

            assertFalse(accepted, "publish after dispose reports shutdown")
            assertNull(store.get("k"), "nothing is persisted after dispose")
        }

    @Test
    fun disposeIsIdempotent() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val bridge = store.suspendingBridge("k", StringCodec, scope = TestScope(testScheduler))

            bridge.dispose()
            bridge.dispose() // second call must be a harmless no-op
            advanceUntilIdle()

            assertFalse(bridge.publish("v"))
        }

    @Test
    fun lastPreDisposeConflatedValueStillDrains() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val bridge = store.suspendingBridge("k", StringCodec, scope = TestScope(testScheduler))

            // Enqueue a value fire-and-forget, then dispose before the drainer runs.
            val accepted = bridge.publish("last")
            bridge.dispose()
            advanceUntilIdle()

            assertEquals(true, accepted, "publish before dispose is accepted")
            // close-then-cancel-backstop: the drainer drains the last conflated
            // value before it exits.
            assertEquals(StringCodec.encode("last"), store.get("k"))
        }

    @Test
    fun disposeCancelsInFlightLoadOnAttach() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store =
                object : SuspendingKvStore {
                    override suspend fun get(key: String): String? {
                        gate.await() // never resolves until we open the gate
                        return "loaded"
                    }

                    override suspend fun put(
                        key: String,
                        value: String,
                    ) = Unit

                    override suspend fun remove(key: String) = Unit

                    override suspend fun snapshot(): Map<String, String> = emptyMap()
                }
            val bridge = store.suspendingBridge("k", StringCodec, scope = TestScope(testScheduler))
            val v = DisposeVault()

            v.action { s bridge bridge } // observe launches the load job
            advanceUntilIdle() // load job runs get(), suspends on the gate

            bridge.dispose() // cancels the in-flight load

            gate.complete(Unit) // even if the gate opens now,
            advanceUntilIdle()

            // the cancelled load never delivers — state stays at its initializer.
            assertEquals("init", v.s.value)
        }
}
