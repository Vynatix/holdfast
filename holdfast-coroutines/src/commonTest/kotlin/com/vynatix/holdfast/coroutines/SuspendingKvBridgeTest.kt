package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for `SuspendingKvStore.suspendingBridge(...)` — the factory that adapts
 * a `SuspendingKvStore` to the `SuspendingBridge<T>` contract: conflated
 * fire-and-forget under sync `store.action { }`, awaited persistence under
 * `store.suspendAction { }`. See issue 11 and finding F14 (the former
 * `bridge(...)`/`suspendingBridge(...)` split is merged into one class).
 */
private class BridgedVault : Store<BridgedVault>() {
    val s by state { "init" }
    val n by state { 0 }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingKvBridgeTest {
    @Test
    fun publishConflatesAndPersists() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val key = "user:name"
            val bridge = store.suspendingBridge(key, StringCodec, scope = TestScope(testScheduler))
            val v = BridgedVault()

            v.action { s bridge bridge }

            v.action { s mutate "alice" }
            advanceUntilIdle()

            assertEquals(StringCodec.encode("alice"), store.get(key))
        }

    @Test
    fun rapidPublishesCoalesceToLatest() =
        runTest {
            val store = CountingSuspendingKvStore()
            val key = "counter"
            val bridge = store.suspendingBridge(key, IntCodec, scope = TestScope(testScheduler))
            val v = BridgedVault()

            v.action { n bridge bridge }

            // 100 rapid publishes — the conflated channel must keep only the latest.
            for (i in 1..100) {
                v.action { n mutate i }
            }
            advanceUntilIdle()

            // Final value reaches the store.
            assertEquals(IntCodec.encode(100), store.get(key))
            // The conflation contract: far fewer than 100 puts hit the backend.
            // We can't pin an exact count (depends on dispatcher interleaving) but it
            // MUST be strictly less than the publish count, otherwise CONFLATED isn't
            // doing its job.
            assertTrue(
                store.putCount < 100,
                "expected conflation to drop intermediate values, but saw ${store.putCount} puts",
            )
        }

    @Test
    fun loadOnAttachHydratesStateFromStore() =
        runTest {
            val store = InMemorySuspendingKvStore(mapOf("greet" to "hello"))
            val bridge = store.suspendingBridge("greet", StringCodec, scope = TestScope(testScheduler))
            val v = BridgedVault()

            v.action { s bridge bridge }
            advanceUntilIdle()

            assertEquals("hello", v.s.value)
        }

    @Test
    fun storeFailureSurfacesOnErrorsFlow() =
        runTest {
            val boom = RuntimeException("disk full")
            val store = ThrowingSuspendingKvStore(boom)
            val bridge = store.suspendingBridge("x", StringCodec, scope = TestScope(testScheduler))
            val v = BridgedVault()

            v.action { s bridge bridge }
            v.action { s mutate "trigger" }

            val first = bridge.errors.take(1).toList()
            assertEquals(1, first.size)
            assertSame(boom, first.single())
        }

    @Test
    fun publishReturnsTrueWithoutBlocking() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val bridge = store.suspendingBridge("k", StringCodec, scope = TestScope(testScheduler))

            // publish must NOT suspend / block — fire-and-forget by contract.
            val ok = bridge.publish("v")
            assertTrue(ok)
        }

    /**
     * F14: the factories are merged — both produce a [SuspendingKvBridge] that
     * IS a [SuspendingBridge], so `suspendAction`'s `is`-check awaits its
     * writes. The deprecated `bridge(...)` alias returns the identical type.
     */
    @Suppress("DEPRECATION")
    @Test
    fun bothFactoriesProduceASuspendingBridge() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val viaSuspending: SuspendingKvBridge<String> =
                store.suspendingBridge("a", StringCodec, scope = TestScope(testScheduler))
            val viaDeprecatedAlias: SuspendingKvBridge<String> =
                store.bridge("b", StringCodec, scope = TestScope(testScheduler))

            assertIs<SuspendingBridge<String>>(viaSuspending)
            assertIs<SuspendingBridge<String>>(viaDeprecatedAlias)
        }

    /**
     * F14: under `suspendAction` the bridge's `publishAwaited` is awaited —
     * the value is persisted before the action returns, with no scheduler
     * advancement required.
     */
    @Test
    fun suspendActionAwaitsPersistence() =
        runTest {
            val store = InMemorySuspendingKvStore()
            val key = "awaited"
            val bridge = store.suspendingBridge(key, StringCodec, scope = TestScope(testScheduler))
            val v = BridgedVault()

            v.action { s bridge bridge }
            v.suspendAction { s mutate "bob" }

            // No advanceUntilIdle: the write completed inside the suspendAction.
            assertEquals(StringCodec.encode("bob"), store.get(key))
        }
}

/**
 * Counts how many times `put` is observed by the backend. Used to verify
 * conflation drops intermediate values.
 */
private class CountingSuspendingKvStore : SuspendingKvStore {
    private val mutex = Mutex()
    private val map = mutableMapOf<String, String>()
    var putCount: Int = 0
        private set

    override suspend fun get(key: String): String? = mutex.withLock { map[key] }

    override suspend fun put(
        key: String,
        value: String,
    ): Unit =
        mutex.withLock {
            putCount += 1
            map[key] = value
        }

    override suspend fun remove(key: String): Unit =
        mutex.withLock {
            map.remove(key)
        }

    override suspend fun snapshot(): Map<String, String> = mutex.withLock { map.toMap() }
}

/** Always throws on `put` — feeds the errors-flow test. */
private class ThrowingSuspendingKvStore(
    private val boom: Throwable,
) : SuspendingKvStore {
    override suspend fun get(key: String): String? = null

    override suspend fun put(
        key: String,
        value: String,
    ): Unit = throw boom

    override suspend fun remove(key: String) = Unit

    override suspend fun snapshot(): Map<String, String> = emptyMap()
}
