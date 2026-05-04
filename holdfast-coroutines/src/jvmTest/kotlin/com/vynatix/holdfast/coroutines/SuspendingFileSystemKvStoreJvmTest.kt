package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM/Android-specific persistence guarantees for [SuspendingFileSystemKvStore].
 * Runs on both `:holdfast-coroutines:jvmTest` and `:holdfast-coroutines:testAndroidHostTest`
 * via the `jvmAndAndroidHostTest` shared source set hierarchy.
 */
class SuspendingFileSystemKvStoreJvmTest {

    private fun freshDirectory(): String {
        val tmp = System.getProperty("java.io.tmpdir") ?: "/tmp"
        val dir = File(tmp, "vault-coro-fs-jvm-${UUID.randomUUID()}")
        dir.mkdirs()
        return dir.absolutePath
    }

    @Test
    fun persistsAcrossRestartFor100Entries() = runTest {
        val dir = freshDirectory()
        val first = SuspendingFileSystemKvStore(dir)
        repeat(ENTRY_COUNT) { i -> first.put("key-$i", "value-$i") }

        // Simulate restart by constructing a fresh instance over the same directory.
        val reborn = SuspendingFileSystemKvStore(dir)
        repeat(ENTRY_COUNT) { i ->
            assertEquals("value-$i", reborn.get("key-$i"))
        }
    }

    /**
     * Atomic-move semantics: if a write is interrupted before the rename completes,
     * the previous committed value must remain readable. We simulate this by
     * leaving an orphaned tmp file in the directory alongside a previously committed
     * value file, then opening a fresh store and asserting the committed value wins.
     */
    @Test
    fun atomicMoveLeavesPreviousValueOnInterruptedWrite() = runTest {
        val dir = freshDirectory()
        val store = SuspendingFileSystemKvStore(dir)

        // Commit the "previous" value cleanly.
        store.put("config", "v1")
        assertEquals("v1", store.get("config"))

        // Inject an orphaned tmp file mid-rename — simulates a kill between
        // Files.write(tmp) and Files.move(tmp, target, ATOMIC_MOVE).
        val root = File(dir)
        val orphanTmp = Files.createTempFile(root.toPath(), ".tmp-", "")
        Files.write(orphanTmp, "v2-partial".toByteArray(StandardCharsets.UTF_8))

        // Reopen and verify the previous committed value is intact.
        val reborn = SuspendingFileSystemKvStore(dir)
        assertEquals("v1", reborn.get("config"))

        // Snapshot also excludes the orphan tmp file.
        val snap = reborn.snapshot()
        assertEquals("v1", snap["config"])
        assertEquals(1, snap.size)
    }

    private companion object {
        const val ENTRY_COUNT = 100
    }
}
