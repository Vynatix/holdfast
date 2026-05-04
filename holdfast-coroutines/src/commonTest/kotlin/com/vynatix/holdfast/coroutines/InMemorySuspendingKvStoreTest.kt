package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue 10 — SuspendingKvStore + InMemorySuspendingKvStore.
 */
class InMemorySuspendingKvStoreTest {
    @Test
    fun put_then_get_round_trips_value() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore()
        store.put("k", "v")
        assertEquals("v", store.get("k"))
    }

    @Test
    fun get_returns_null_for_missing_key() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore()
        assertNull(store.get("missing"))
    }

    @Test
    fun remove_removes_value() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore()
        store.put("k", "v")
        store.remove("k")
        assertNull(store.get("k"))
    }

    @Test
    fun remove_on_missing_key_is_no_op() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore()
        store.remove("never-existed")
        assertNull(store.get("never-existed"))
    }

    @Test
    fun snapshot_returns_a_defensive_copy() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore()
        store.put("a", "1")
        store.put("b", "2")
        val snap = store.snapshot()
        assertEquals(mapOf("a" to "1", "b" to "2"), snap)

        // Mutating the snapshot must not affect the store.
        (snap as? MutableMap<String, String>)?.put("c", "3")
        assertNull(store.get("c"))
    }

    @Test
    fun initial_seed_is_visible_via_get_and_snapshot() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore(initial = mapOf("seeded" to "yes"))
        assertEquals("yes", store.get("seeded"))
        assertEquals(mapOf("seeded" to "yes"), store.snapshot())
    }

    @Test
    fun concurrent_puts_serialize_no_lost_writes() = runTest {
        val store: SuspendingKvStore = InMemorySuspendingKvStore()
        val keys = (1..200).map { "key-$it" }
        // Launch all puts concurrently. Mutex inside the impl serializes them.
        keys.map { key ->
            async { store.put(key, "value-of-$key") }
        }.awaitAll()
        val snap = store.snapshot()
        assertEquals(200, snap.size)
        keys.forEach { key ->
            assertEquals("value-of-$key", snap[key], "missing/wrong value for $key")
        }
    }
}
