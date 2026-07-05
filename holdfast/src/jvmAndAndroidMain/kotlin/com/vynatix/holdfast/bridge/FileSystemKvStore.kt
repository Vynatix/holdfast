package com.vynatix.holdfast.bridge

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class FileSystemKvStore actual constructor(
    rootPath: String,
) : KvStore {
    private val root: File = File(rootPath).also { it.mkdirs() }

    actual override fun get(key: String): String? {
        val file = root.resolve(encodeKey(key))
        if (!file.exists()) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
    }

    actual override fun put(
        key: String,
        value: String,
    ) {
        val target = root.resolve(encodeKey(key)).toPath()
        val tmp = Files.createTempFile(root.toPath(), ".tmp-", "")
        Files.write(tmp, value.toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    actual override fun remove(key: String) {
        val file = root.resolve(encodeKey(key))
        if (file.exists()) file.delete()
    }

    actual override fun snapshot(): Map<String, String> {
        val files = root.listFiles { f -> f.isFile && !f.name.startsWith(TMP_PREFIX) } ?: return emptyMap()
        return files.associate { f ->
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
        private const val TMP_PREFIX = ".tmp-"
        private const val HEX_RADIX = 16
        private const val HEX_DIGITS = 2
    }
}
