@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vynatix.vault.bridge

import platform.Foundation.NSTemporaryDirectory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal actual fun tempRoot(suffix: String): String = "${NSTemporaryDirectory()}$suffix"

@OptIn(ExperimentalUuidApi::class)
internal actual fun randomDirSuffix(): String = Uuid.random().toString()
