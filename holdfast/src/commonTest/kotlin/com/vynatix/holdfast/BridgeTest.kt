package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BridgeTestVault : Store<BridgeTestVault>() {
    val count by state { 0 }
    val text by state { "init" }
}

private class TransformingBridgeVault : Store<TransformingBridgeVault>() {
    val n by state(IntDoublerTransformer()) { 0 }
}

private class IntDoublerTransformer : Transformer<Int> {
    override fun set(value: Int): Int = value * 2

    override fun get(value: Int): Int = value
}

private class RecordingBridge<T : Any> : Bridge<T> {
    private val observers = mutableListOf<(T) -> Unit>()
    val published = mutableListOf<T>()
    val observerInstalls = atomic(0)

    override fun observe(observer: (T) -> Unit): Disposable {
        observerInstalls.incrementAndGet()
        observers.add(observer)
        return Disposable { observers.remove(observer) }
    }

    override fun publish(value: T): Boolean {
        published.add(value)
        return true
    }

    fun deliver(value: T) {
        observers.toList().forEach { it(value) }
    }
}

private class ThrowingPublishBridge<T : Any>(
    private val message: String = "publish refused",
) : Bridge<T> {
    val callCount = atomic(0)

    override fun observe(observer: (T) -> Unit): Disposable = Disposable { /* noop */ }

    override fun publish(value: T): Boolean {
        callCount.incrementAndGet()
        throw RuntimeException(message)
    }
}

private class ReplayingBridge<T : Any>(
    private val initial: T,
) : Bridge<T> {
    val initialDelivered = atomic(0)

    override fun observe(observer: (T) -> Unit): Disposable {
        // Many bridges replay the current value to new subscribers.
        observer(initial)
        initialDelivered.incrementAndGet()
        return Disposable { /* noop */ }
    }

    override fun publish(value: T): Boolean = true
}

private class AsyncBridge<T : Any> : Bridge<T> {
    private val observers = mutableListOf<(T) -> Unit>()
    val published = mutableListOf<T>()

    override fun observe(observer: (T) -> Unit): Disposable {
        observers.add(observer)
        return Disposable { observers.remove(observer) }
    }

    override fun publish(value: T): Boolean {
        published.add(value)
        return true
    }

    suspend fun deliverFromCoroutine(value: T) {
        withContext(Dispatchers.Default) {
            observers.toList().forEach { it(value) }
        }
    }
}

class BridgeRollbackIsolationTest {
    @Test
    fun rolledBackTransactionDoesNotPublishToAttachedBridge() {
        val v = BridgeTestVault()
        v action { count mutate 1 }

        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }
        bridge.published.clear()

        v action {
            count mutate 99
            error("rollback")
        }

        assertTrue(
            99 !in bridge.published && 1 !in bridge.published,
            "bridge must not receive publish events from a rolled-back transaction; " +
                "published=${bridge.published}",
        )
    }
}

class BridgeLifecycleTest {
    @Test
    fun attachingBridgeRunsItsObserveImmediatelyAndConnectsListener() {
        val v = BridgeTestVault()
        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }
        assertEquals(
            1,
            bridge.observerInstalls.value,
            "attaching a bridge must install its observe-listener exactly once",
        )
    }

    @Test
    fun bridgePublishIsCalledOncePerSuccessfulCommit() {
        val v = BridgeTestVault()
        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }
        bridge.published.clear()

        v action { count mutate 1 }
        v action { count mutate 2 }
        v action { count mutate 3 }

        assertEquals(listOf(1, 2, 3), bridge.published)
    }

    @Test
    fun bridgeReplacedWithNullClearsTheRepositoryReference() {
        val v = BridgeTestVault()
        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }
        bridge.published.clear()

        // The public `State<T>.bridge(...)` infix takes a non-null Bridge.
        // Clearing requires the MutableState setter directly.
        @Suppress("UNCHECKED_CAST")
        v { (count as MutableState<Int>).bridge = null }

        // Verify by triggering a mutation; the now-null bridge must not record it.
        v action { count mutate 7 }
        assertEquals(emptyList(), bridge.published, "no publish after bridge cleared to null")
        @Suppress("UNCHECKED_CAST")
        val readBack = v { (count as MutableState<Int>).bridge }
        assertNull(readBack, "bridge property reads back as null")
    }

    @Test
    fun attachingNewBridgeReplacesPreviousAndAttachesItsObserver() {
        val v = BridgeTestVault()
        val first = RecordingBridge<Int>()
        val second = RecordingBridge<Int>()

        v { count bridge first }
        v action { count mutate 1 }

        v { count bridge second }
        v action { count mutate 2 }

        assertEquals(listOf(1), first.published, "first bridge sees only the first commit")
        assertEquals(listOf(2), second.published, "second bridge sees only the second commit")
        assertEquals(1, second.observerInstalls.value, "second bridge's observe was attached")
    }

    @Test
    fun bridgeAttachmentIsThreadSafeUnderConcurrentSetCalls() =
        runBlocking {
            val v = BridgeTestVault()
            val workers = 8
            val opsPerWorker = 50

            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            val bridge = RecordingBridge<Int>()
                            v { count bridge bridge }
                        }
                    }
                }
            jobs.awaitAll()

            // Final state: store is consistent; one final attach + mutate works.
            val final = RecordingBridge<Int>()
            v { count bridge final }
            v action { count mutate 999 }
            assertEquals(listOf(999), final.published)
        }
}

class BridgeFlowTest {
    @Test
    fun incomingBridgeValueAppliesToStateAndFiresObserversWithoutRePublishing() {
        val v = BridgeTestVault()
        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }

        val seen = mutableListOf<Int>()
        val d = v { count effect { seen.add(this) } }
        seen.clear()
        bridge.published.clear()

        bridge.deliver(42)

        assertEquals(42, v.count.value, "incoming bridge value applied to state")
        assertEquals(listOf(42), seen, "observers fire on incoming bridge value")
        assertEquals(emptyList(), bridge.published, "incoming value must not be re-published to its source")
        d.dispose()
    }

    @Test
    fun incomingBridgeValueGoesThroughTransformerSet() {
        val v = TransformingBridgeVault()
        val bridge = RecordingBridge<Int>()
        v { n bridge bridge }

        bridge.deliver(5)

        // IntDoubler.set doubles the input. Stored _value=10. Reader returns 10 (get is identity).
        assertEquals(
            10,
            v.n.value,
            "transformer.set must apply to incoming bridge values",
        )
    }

    @Test
    fun incomingBridgeValueIsObservableViaStateValueGetter() {
        val v = BridgeTestVault()
        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }

        bridge.deliver(7)
        bridge.deliver(13)
        bridge.deliver(21)

        assertEquals(21, v.count.value, "value getter reflects the latest bridge-delivered value")
    }

    @Test
    fun bridgeReceivingItsOwnPublishedValueDoesNotInfiniteLoop() {
        val v = BridgeTestVault()
        val bridge = RecordingBridge<Int>()
        v { count bridge bridge }
        bridge.published.clear()

        // Trigger a state mutation -> bridge.publish(value).
        // A naive bridge might re-deliver via observer -> applyFromBridge -> notify observers.
        // applyFromBridge intentionally skips re-publishing; verify no loop.
        v action { count mutate 5 }
        bridge.deliver(bridge.published.last()) // simulate the bridge echoing back

        assertEquals(5, v.count.value)
        assertEquals(listOf(5), bridge.published, "echo must not be re-published; published=${bridge.published}")
    }
}

class BridgeErrorPathTest {
    @Test
    fun bridgePublishThrowingDoesNotCorruptStateOrLeakActiveTransaction() {
        val v = BridgeTestVault()
        val bridge = ThrowingPublishBridge<Int>("publish failed")
        v { count bridge bridge }

        // The mutation commits; applyCommitted is what calls bridge.publish.
        // A publish throw is fire-and-forget (P1-partial-commit): it is routed to
        // the uncaught-error handler and must not corrupt state or leak the txn.
        v.uncaughtObserverHandler = { /* swallow the routed publish failure */ }
        v action { count mutate 5 }

        // _value was set before publish was invoked, so the state's mutation is durable.
        assertEquals(5, v.count.value, "state mutation completes before publish throws")
        assertNull(v.activeTransaction, "active transaction must clear even when bridge.publish throws")
        assertTrue(bridge.callCount.value >= 1, "bridge.publish was invoked")
    }

    @Test
    fun bridgeReplayingInitialValueOnObserveDoesNotCrashState() {
        val v = BridgeTestVault()
        val replaying = ReplayingBridge(99)
        v { count bridge replaying }

        // Replaying bridge delivered 99 to MutableState.applyFromBridge during attach.
        assertEquals(1, replaying.initialDelivered.value)
        assertEquals(99, v.count.value, "replayed initial value is applied to state")
    }
}

class AsyncBridgeTest {
    @Test
    fun bridgeDeliveringIncomingValuesViaCoroutineUpdatesStateAndFiresObservers() =
        runBlocking {
            val v = BridgeTestVault()
            val bridge = AsyncBridge<Int>()
            v { count bridge bridge }

            val seen = mutableListOf<Int>()
            val d = v { count effect { seen.add(this) } }
            seen.clear()

            bridge.deliverFromCoroutine(99)
            assertEquals(99, v.count.value)
            assertEquals(listOf(99), seen, "effect must fire for the async-delivered value")
            d.dispose()
        }

    @Test
    fun bridgePublishingFromAsyncContextIntoStatePropagatesValue() =
        runBlocking {
            val v = BridgeTestVault()
            val bridge = AsyncBridge<Int>()
            v { count bridge bridge }
            bridge.published.clear()

            // Mutate from an off-main coroutine; commit fires bridge.publish.
            async(Dispatchers.Default) {
                v action { count mutate 33 }
            }.await()

            assertEquals(33, v.count.value)
            assertEquals(listOf(33), bridge.published)
        }

    @Test
    fun bridgeRoundTrippingViaAsyncEventLoopPreservesValueIdentityAndOrder() =
        runBlocking {
            val v = BridgeTestVault()
            val bridge = AsyncBridge<Int>()
            v { count bridge bridge }
            bridge.published.clear()

            // Outbound: store → bridge. Each commit publishes once.
            v action { count mutate 1 }
            v action { count mutate 2 }
            v action { count mutate 3 }

            // Inbound: bridge → store, in a different order, via async delivery.
            bridge.deliverFromCoroutine(10)
            bridge.deliverFromCoroutine(20)

            assertEquals(listOf(1, 2, 3), bridge.published, "outbound publish order preserved")
            assertEquals(20, v.count.value, "latest async-delivered value is current")
        }
}
