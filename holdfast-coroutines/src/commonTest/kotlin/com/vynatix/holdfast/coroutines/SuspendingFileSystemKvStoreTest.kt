package com.vynatix.vault.coroutines

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-platform tests for [SuspendingFileSystemKvStore]. Each test uses a unique
 * subdirectory under a platform-provided tmp root to avoid collisions when tests
 * run in parallel.
 */
class SuspendingFileSystemKvStoreTest {

    private fun newKvStore(suffix: String): SuspendingFileSystemKvStore =
        SuspendingFileSystemKvStore(suspendingTempRoot("vault-coro-fs-$suffix-${suspendingRandomDirSuffix()}"))

    @Test
    fun putThenGetReturnsTheValue() = runTest {
        val kv = newKvStore("put-get")
        kv.put("alpha", "one")
        assertEquals("one", kv.get("alpha"))
    }

    @Test
    fun getOnMissingKeyReturnsNull() = runTest {
        val kv = newKvStore("missing")
        assertNull(kv.get("nope"))
    }

    @Test
    fun removeDropsTheKey() = runTest {
        val kv = newKvStore("remove")
        kv.put("k", "v")
        kv.remove("k")
        assertNull(kv.get("k"))
    }

    @Test
    fun roundTripUnicodeAndSpecialKeyCharacters() = runTest {
        val kv = newKvStore("unicode")
        kv.put("user/with slash", "héllo 🔒")
        kv.put("a.b:c=d", "value")
        assertEquals("héllo 🔒", kv.get("user/with slash"))
        assertEquals("value", kv.get("a.b:c=d"))
    }

    @Test
    fun snapshotListsAllStoredKeys() = runTest {
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
}

/** Platform-specific temp directory root. */
internal expect fun suspendingTempRoot(suffix: String): String

/** Best-effort unique subdirectory suffix to keep parallel tests isolated. */
internal expect fun suspendingRandomDirSuffix(): String
