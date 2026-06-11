package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class SuspendingFileSystemKvStore actual constructor(
    directory: String,
) : SuspendingKvStore {
    private val root: File = File(directory).also { it.mkdirs() }

    init {
        require(root.isDirectory) { "directory does not exist or is not a directory: $directory" }
    }

    actual override suspend fun get(key: String): String? =
        withContext(Dispatchers.IO) {
            val file = root.resolve(encodeKey(key))
            if (!file.exists()) {
                null
            } else {
                runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
            }
        }

    actual override suspend fun put(
        key: String,
        value: String,
    ) {
        withContext(Dispatchers.IO) {
            val target = root.resolve(encodeKey(key)).toPath()
            val tmp = Files.createTempFile(root.toPath(), TMP_PREFIX, "")
            Files.write(tmp, value.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    actual override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            val file = root.resolve(encodeKey(key))
            if (file.exists()) file.delete()
        }
    }

    actual override suspend fun snapshot(): Map<String, String> =
        withContext(Dispatchers.IO) {
            val files =
                root.listFiles { f -> f.isFile && !f.name.startsWith(TMP_PREFIX) }
                    ?: return@withContext emptyMap()
            files.associate { f ->
                decodeKey(f.name) to f.readText(StandardCharsets.UTF_8)
            }
        }

    private fun encodeKey(key: String): String =
        buildString {
            for (ch in key) {
                if (isSafeKeyChar(ch)) {
                    append(ch)
                } else {
                    append('%')
                    append(ch.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0'))
                }
            }
        }

    private fun decodeKey(name: String): String =
        buildString {
            var i = 0
            while (i < name.length) {
                val ch = name[i]
                if (ch == '%' && i + HEX_DIGITS < name.length) {
                    append(name.substring(i + 1, i + 1 + HEX_DIGITS).toInt(HEX_RADIX).toChar())
                    i += 1 + HEX_DIGITS
                } else {
                    append(ch)
                    i++
                }
            }
        }

    private fun isSafeKeyChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.'

    private companion object {
        const val TMP_PREFIX = ".tmp-"
        const val HEX_RADIX = 16
        const val HEX_DIGITS = 2
    }
}
