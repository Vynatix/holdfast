@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vynatix.vault.coroutines

import platform.Foundation.NSTemporaryDirectory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal actual fun suspendingTempRoot(suffix: String): String = "${NSTemporaryDirectory()}$suffix"

@OptIn(ExperimentalUuidApi::class)
internal actual fun suspendingRandomDirSuffix(): String = Uuid.random().toString()
