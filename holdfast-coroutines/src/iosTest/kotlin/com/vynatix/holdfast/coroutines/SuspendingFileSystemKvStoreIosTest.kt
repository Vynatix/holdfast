@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * iOS-specific persistence guarantee for [SuspendingFileSystemKvStore]: state put
 * via one instance must be readable by a fresh instance pointing at the same
 * directory.
 */
class SuspendingFileSystemKvStoreIosTest {

    @OptIn(ExperimentalUuidApi::class)
    private fun freshDirectory(): String {
        val dir = "${NSTemporaryDirectory()}store-coro-fs-ios-${Uuid.random()}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    @Test
    fun persistsAcrossRestartOnIosSimulator() = runTest {
        val dir = freshDirectory()
        val first = SuspendingFileSystemKvStore(dir)
        repeat(ENTRY_COUNT) { i -> first.put("key-$i", "value-$i") }

        val reborn = SuspendingFileSystemKvStore(dir)
        repeat(ENTRY_COUNT) { i ->
            assertEquals("value-$i", reborn.get("key-$i"))
        }
    }

    private companion object {
        const val ENTRY_COUNT = 100
    }
}
