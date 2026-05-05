package com.vynatix.holdfast.bridge

import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FileVault : Store<FileVault>() {
    val balance by state { 0L }
    val name by state { "" }
}

/**
 * Cross-platform tests for FileSystemKvStore. Each test uses a unique
 * subdirectory under a platform-provided tmp root to avoid collisions when
 * tests run in parallel.
 */
class FileSystemKvStoreTest {

    private fun newKvStore(suffix: String): FileSystemKvStore = FileSystemKvStore(tempRoot("vault-fs-$suffix-${randomDirSuffix()}"))

    @Test fun putThenGetReturnsTheValue() {
        val kv = newKvStore("put-get")
        kv.put("alpha", "one")
        assertEquals("one", kv.get("alpha"))
    }

    @Test fun getOnMissingKeyReturnsNull() {
        val kv = newKvStore("missing")
        assertNull(kv.get("nope"))
    }

    @Test fun removeDropsTheKey() {
        val kv = newKvStore("remove")
        kv.put("k", "v")
        kv.remove("k")
        assertNull(kv.get("k"))
    }

    @Test fun roundTripUnicodeAndSpecialKeyCharacters() {
        val kv = newKvStore("unicode")
        // Keys with characters that need URL-encoding on disk.
        kv.put("user/with slash", "héllo 🔒")
        kv.put("a.b:c=d", "value")
        assertEquals("héllo 🔒", kv.get("user/with slash"))
        assertEquals("value", kv.get("a.b:c=d"))
    }

    @Test fun snapshotListsAllStoredKeys() {
        val kv = newKvStore("snapshot")
        kv.put("a", "1")
        kv.put("b", "2")
        kv.put("c", "3")
        val s = kv.snapshot()
        assertEquals("1", s["a"])
        assertEquals("2", s["b"])
        assertEquals("3", s["c"])
        assertTrue(s.size >= 3)
    }

    @Test fun composesWithKvBridgeForCommitPersistence() {
        val v = FileVault()
        val kv = newKvStore("compose")
        v { balance bridge KvBridge(kv, "balance:1", LongCodec) }
        v action { balance mutate 42 }
        assertEquals("42", kv.get("balance:1"))

        // Simulated restart: fresh vault, same store, hydrate via attach.
        val reborn = FileVault()
        reborn { balance bridge KvBridge(kv, "balance:1", LongCodec) }
        assertEquals(42L, reborn.balance.value)
    }
}

/** Platform-specific temp directory root. */
internal expect fun tempRoot(suffix: String): String

/** Best-effort unique subdirectory suffix to keep parallel tests isolated. */
internal expect fun randomDirSuffix(): String
