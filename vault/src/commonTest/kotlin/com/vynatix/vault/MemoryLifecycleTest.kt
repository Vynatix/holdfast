package com.vynatix.vault

import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class LifecycleVault : Vault<LifecycleVault>() {
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
        // vault is still usable for fresh operations without crashes or stale wiring.
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

        // Re-use the vault: fresh state on next access, action commits, no leftover wiring.
        v action { a mutate 99 }
        assertEquals(99, v.a.value)
        assertEquals(1, bridge.publishCount.value, "old bridge cleared; no new publishes")
        assertEquals(listOf(1), seen, "old observer disposed; no new notifications")
    }
}
