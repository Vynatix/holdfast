package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class LifecycleVault : Store<LifecycleVault>() {
    val a by state { 0 }
    val b by state { "init" }
}

private class LifecycleBridge : Bridge<Int> {
    val publishCount = atomic(0)

    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { /* noop */ }

    override fun publish(value: Int): Boolean {
        publishCount.incrementAndGet()
        return true
    }
}

/**
 * Two-way bridge with a manual `deliver` so the test can simulate an external
 * inbound update. Used to verify that swapping or detaching a bridge actually
 * disposes its inbound observer registration.
 */
private class TwoWayBridge : Bridge<Int> {
    private val callbacks = mutableListOf<(Int) -> Unit>()
    val publishCount = atomic(0)

    override fun observe(observer: (Int) -> Unit): Disposable {
        callbacks.add(observer)
        return Disposable { callbacks.remove(observer) }
    }

    override fun publish(value: Int): Boolean {
        publishCount.incrementAndGet()
        return true
    }

    fun deliver(value: Int) = callbacks.toList().forEach { it(value) }
}

class MemoryLifecycleTest {
    @Test
    fun disposingObserverRemovesItFromTheStatesObserverSet() {
        val v = LifecycleVault()
        val seen = mutableListOf<Int>()
        val d = v { a effect { seen.add(this) } }
        seen.clear()

        v action { a mutate 1 }
        assertEquals(listOf(1), seen, "observer fires before disposal")

        d.dispose()
        v action { a mutate 2 }
        assertEquals(
            listOf(1),
            seen,
            "after dispose, observer must not fire for further commits",
        )
    }

    @Test
    fun disposingObserverTwiceIsSafeAndIdempotent() {
        val v = LifecycleVault()
        val seen = mutableListOf<Int>()
        val d = v { a effect { seen.add(this) } }
        seen.clear()

        d.dispose()
        d.dispose() // second dispose must not throw

        v action { a mutate 5 }
        assertEquals(
            emptyList(),
            seen,
            "double-dispose is idempotent; observer remains removed",
        )
    }

    @Test
    fun replacingBridgeStopsOldBridgeFromReceivingFurtherPublishes() {
        val v = LifecycleVault()
        val first = LifecycleBridge()
        val second = LifecycleBridge()
        v { a bridge first }

        v action { a mutate 1 }
        assertEquals(1, first.publishCount.value)

        v { a bridge second }
        v action { a mutate 2 }

        assertEquals(1, first.publishCount.value, "old bridge no longer receives publishes")
        assertEquals(1, second.publishCount.value, "new bridge sees the post-swap commit")
    }

    @Test
    fun settingBridgeToNullStopsAllPublishing() {
        val v = LifecycleVault()
        val bridge = LifecycleBridge()
        v { a bridge bridge }

        v action { a mutate 1 }
        assertEquals(1, bridge.publishCount.value)

        @Suppress("UNCHECKED_CAST")
        v { (a as MutableState<Int>).bridge = null }

        v action { a mutate 2 }
        assertEquals(
            1,
            bridge.publishCount.value,
            "after bridge=null, no further publishes",
        )
    }

    @Test
    fun removeStateMakesPropertiesSnapshotNoLongerContainTheState() {
        val v = LifecycleVault()
        v.a // register
        v.b // register
        assertEquals(2, v.properties.size)

        v.removeState("a")

        assertEquals(1, v.properties.size, "removed state must drop out of properties snapshot")
        assertFalse("a" in v.properties.keys)
        assertTrue("b" in v.properties.keys)
    }

    @Test
    fun clearStatesEmptiesPropertiesSnapshotCompletely() {
        val v = LifecycleVault()
        v.a
        v.b
        assertEquals(2, v.properties.size)

        v.clearStates()
        assertEquals(0, v.properties.size, "clearStates drops every registered state from the snapshot")
    }

    @Test
    fun vaultRemainsFunctionalAfterAllObserversAndBridgesAreClearedAndStatesRemoved() {
        // Stand-in for "GC-cleanup-friendly" — without a multiplatform WeakReference we
        // test the observable side: after wiping observers, bridges, and states, the
        // store is still usable for fresh operations without crashes or stale wiring.
        val v = LifecycleVault()
        val seen = mutableListOf<Int>()
        val d = v { a effect { seen.add(this) } }
        seen.clear() // ignore initial-subscribe callback
        val bridge = LifecycleBridge()
        v { a bridge bridge }
        v action { a mutate 1 }
        assertEquals(1, bridge.publishCount.value)

        d.dispose()
        @Suppress("UNCHECKED_CAST")
        v { (a as MutableState<Int>).bridge = null }
        v.clearStates()

        // Re-use the store: fresh state on next access, action commits, no leftover wiring.
        v action { a mutate 99 }
        assertEquals(99, v.a.value)
        assertEquals(1, bridge.publishCount.value, "old bridge cleared; no new publishes")
        assertEquals(listOf(1), seen, "old observer disposed; no new notifications")
    }

    @Test
    fun replacingBridgeDisposesPreviousInboundObserverRegistration() {
        // Regression: prior to A1, the bridge setter discarded the Disposable returned
        // by `value?.observe { … }`. After swap, an external `deliver` on the OLD
        // bridge would still drive applyFromBridge on the store's state. Verify
        // the inbound observer is detached on swap.
        val v = LifecycleVault()
        val first = TwoWayBridge()
        val second = TwoWayBridge()
        v { a bridge first }
        v.a // ensure registered

        // Swap: the previous bridge's inbound observer must be disposed.
        v { a bridge second }

        // External "admin push" on the OLD bridge — should not affect the state.
        first.deliver(999)
        assertEquals(0, v.a.value, "old bridge's inbound observer was leaked; state was driven by detached bridge")

        // Push on the NEW bridge does drive the state.
        second.deliver(42)
        assertEquals(42, v.a.value)
    }

    @Test
    fun settingBridgeToNullDisposesPreviousInboundObserverRegistration() {
        val v = LifecycleVault()
        val bridge = TwoWayBridge()
        v { a bridge bridge }

        // Detach via null.
        v { a bridge null }

        // External "admin push" should not affect the now-detached state.
        bridge.deliver(123)
        assertEquals(0, v.a.value, "bridge=null didn't dispose inbound observer; detached bridge still drives state")
    }

    @Test
    fun removeStateInsideActiveTransactionWithPendingWriteRefuses() {
        val v = LifecycleVault()
        var caught: Throwable? = null
        v action {
            a mutate 7
            // a now has a pending write in this transaction; removeState must refuse.
            try {
                removeState("a")
            } catch (e: IllegalStateException) {
                caught = e
            }
        }
        assertIs<IllegalStateException>(caught, "removeState with pending writes must throw IllegalStateException")
        // After the action commits (a's write applied), removeState succeeds normally.
        v.removeState("a")
        assertFalse("a" in v.properties.keys)
    }

    @Test
    fun uncaughtObserverHandlerReceivesObserverExceptionsOnCommitFire() {
        // A10 contract: observer exceptions on commit-fire are routed to the loud
        // built-in logger by default; a non-null uncaughtObserverHandler captures them
        // instead. Initial-subscribe fires propagate to the caller (observe is
        // synchronous from their POV).
        val v = LifecycleVault()
        val captured = mutableListOf<Throwable>()
        v.uncaughtObserverHandler = { captured.add(it) }
        // Throw only on commit-fire, not on the initial-subscribe call.
        val first = atomic(true)
        v {
            a effect {
                if (first.value) {
                    first.value = false
                } else {
                    error("boom on commit with value=$this")
                }
            }
        }
        v action { a mutate 1 }
        assertEquals(1, captured.size, "handler captured the commit-fire throw")
        assertTrue(captured.first().message?.contains("boom on commit") == true)
    }

    @Test
    fun uncaughtObserverHandlerNullRoutesToLoudFallbackWithoutDisruptingFanout() {
        // P1-observer-swallow: a null handler no longer swallows silently — it routes
        // to a loud built-in logger. Either way the throw is contained: other
        // observers continue to fire on the same commit. (See EffectTest.
        // effectThatThrowsExceptionDoesNotPreventOtherSubscribersFromBeingNotified.)
        val v = LifecycleVault()
        val seen = mutableListOf<Int>()
        val first = atomic(true)
        v {
            a effect {
                if (first.value) {
                    first.value = false
                } else {
                    error("commit-fire throws")
                }
            }
        }
        v { a effect { seen.add(this) } }
        seen.clear()
        v action { a mutate 1 }
        assertEquals(listOf(1), seen, "second observer fires on commit even when first throws and handler is null")
    }
}
