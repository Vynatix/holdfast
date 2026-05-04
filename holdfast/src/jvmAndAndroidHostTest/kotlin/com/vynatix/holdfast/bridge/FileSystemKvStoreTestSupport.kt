package com.vynatix.vault.bridge

import java.util.UUID

internal actual fun tempRoot(suffix: String): String {
    val tmp = System.getProperty("java.io.tmpdir") ?: "/tmp"
    return "$tmp/$suffix"
}

internal actual fun randomDirSuffix(): String = UUID.randomUUID().toString()
