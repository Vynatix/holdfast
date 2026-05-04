package com.vynatix.vault.coroutines

import java.util.UUID

internal actual fun suspendingTempRoot(suffix: String): String {
    val tmp = System.getProperty("java.io.tmpdir") ?: "/tmp"
    return "$tmp/$suffix"
}

internal actual fun suspendingRandomDirSuffix(): String = UUID.randomUUID().toString()
