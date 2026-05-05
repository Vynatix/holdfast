@file:Suppress("InjectDispatcher")

package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.KvBridge
import com.vynatix.holdfast.bridge.LongCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.testing.vaultTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SettingsVault : Store<SettingsVault>() {
    val theme by state { "light" }
    val balance by state { 0L }
}

class FakeKvStoreTest {

    @Test
    fun roundTripsValuesViaPutAndGet() {
        val kv = FakeKvStore()
        kv.put("k", "v")
        assertEquals("v", kv.get("k"))
    }

    @Test
    fun getReturnsNullForMissingKeys() {
        val kv = FakeKvStore()
        assertNull(kv.get("missing"))
    }

    @Test
    fun removeDropsTheKey() {
        val kv = FakeKvStore()
        kv.put("k", "v")
        kv.remove("k")
        assertNull(kv.get("k"))
    }

    @Test
    fun snapshotReflectsAllStoredEntries() {
        val kv = FakeKvStore()
        kv.put("a", "1")
        kv.put("b", "2")
        val snap = kv.snapshot()
        assertEquals("1", snap["a"])
        assertEquals("2", snap["b"])
        assertEquals(2, snap.size)
    }

    @Test
    fun clearEmptiesStore() {
        val kv = FakeKvStore()
        kv.put("k", "v")
        kv.put("k2", "v2")
        kv.clear()
        assertNull(kv.get("k"))
        assertTrue(kv.contents.isEmpty())
        assertTrue(kv.snapshot().isEmpty())
    }

    @Test
    fun contentsReflectsCurrentStateAndIsDefensivelyCopied() {
        val kv = FakeKvStore()
        kv.put("k", "v")
        val snapshot = kv.contents
        kv.put("other", "x")
        // The original snapshot must not see the new key (defensive copy).
        assertEquals(1, snapshot.size)
        assertEquals("v", snapshot["k"])
        assertNull(snapshot["other"])
        // The live view reflects both writes.
        assertEquals(2, kv.contents.size)
    }

    @Test
    fun snapshotIsDefensivelyCopied() {
        val kv = FakeKvStore()
        kv.put("k", "v")
        val first = kv.snapshot()
        kv.put("other", "x")
        assertEquals(1, first.size)
        assertEquals(2, kv.snapshot().size)
    }

    @Test
    fun integrationWithKvBridgePersistsCommittedMutations() = vaultTest {
        val kv = FakeKvStore()
        val ctr = track(
            SettingsVault().also { v ->
                v { theme bridge KvBridge(kv, "theme", StringCodec) }
            },
        )

        ctr.action { theme mutate "dark" }

        // KvBridge.publish writes synchronously inside the commit phase, so the
        // value is observable in `contents` immediately after `action` returns.
        assertEquals("dark", kv.contents["theme"])
        assertEquals("dark", kv.get("theme"))
    }

    @Test
    fun integrationWithKvBridgeHydratesSeededValueOnAttach() = vaultTest {
        val kv = FakeKvStore()
        // Seed the store as if a previous run had persisted the value.
        kv.put("balance:1", "42")

        val v = SettingsVault()
        v { balance bridge KvBridge(kv, "balance:1", LongCodec) }

        val ctr = track(v)
        // KvBridge.observe runs on attach and hydrates the state from kv.
        assertEquals(42L, ctr.read { balance.value })
    }

    @Test
    fun concurrentWritesFromMultipleCoroutinesDoNotCorruptState() = vaultTest {
        val kv = FakeKvStore()
        val totalKeys = 100

        coroutineScope {
            (0 until totalKeys).map { i ->
                async(Dispatchers.Default) { kv.put("k$i", "v$i") }
            }.awaitAll()
        }

        assertEquals(totalKeys, kv.contents.size)
        repeat(totalKeys) { i ->
            assertEquals("v$i", kv.get("k$i"))
        }
    }

    @Test
    fun concurrentMixedReadsWritesAndRemovesRemainConsistent() = vaultTest {
        val kv = FakeKvStore()
        // Pre-seed half the keys.
        repeat(50) { i -> kv.put("k$i", "seed-$i") }

        coroutineScope {
            val writes = (50 until 100).map { i ->
                async(Dispatchers.Default) { kv.put("k$i", "v$i") }
            }
            val reads = (0 until 50).map { i ->
                async(Dispatchers.Default) { kv.get("k$i") }
            }
            val removes = (0 until 25).map { i ->
                async(Dispatchers.Default) { kv.remove("k$i") }
            }
            (writes + reads + removes).awaitAll()
        }

        // The first 25 keys were removed; the next 25 retained their seed; the
        // last 50 were written concurrently.
        repeat(25) { i -> assertNull(kv.get("k$i")) }
        for (i in 25 until 50) {
            assertEquals("seed-$i", kv.get("k$i"))
        }
        for (i in 50 until 100) {
            assertEquals("v$i", kv.get("k$i"))
        }
    }
}
