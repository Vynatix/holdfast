@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.vynatix.holdfast.bridge

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL

actual class FileSystemKvStore actual constructor(
    rootPath: String,
) : KvStore {
    private val root: NSURL =
        NSURL.fileURLWithPath(rootPath, isDirectory = true).also {
            NSFileManager.defaultManager.createDirectoryAtURL(it, withIntermediateDirectories = true, attributes = null, error = null)
        }

    @Suppress("ReturnCount")
    actual override fun get(key: String): String? {
        val url = root.URLByAppendingPathComponent(encodeKey(key)) ?: return null
        val path = url.path ?: return null
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    actual override fun put(
        key: String,
        value: String,
    ) {
        val url = root.URLByAppendingPathComponent(encodeKey(key)) ?: error("invalid key: $key")
        val nsString = NSString.create(string = value)
        val data: NSData =
            nsString.dataUsingEncoding(NSUTF8StringEncoding)
                ?: error("encode failed for key=$key")
        // writeToURL:atomically:  — atomically=true writes to a temp file then renames.
        val ok = data.writeToURL(url, atomically = true)
        if (!ok) error("FileSystemKvStore.put failed for key=$key")
    }

    actual override fun remove(key: String) {
        val url = root.URLByAppendingPathComponent(encodeKey(key)) ?: return
        val path = url.path ?: return
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
        }
    }

    actual override fun snapshot(): Map<String, String> {
        val contents =
            NSFileManager.defaultManager.contentsOfDirectoryAtURL(
                root,
                includingPropertiesForKeys = null,
                options = 0u,
                error = null,
            ) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        @Suppress("UNCHECKED_CAST")
        (contents as List<NSURL>).forEach { item ->
            val name = item.lastPathComponent
            if (name != null && !name.startsWith(TMP_PREFIX)) {
                val key = decodeKey(name)
                val value = get(key)
                if (value != null) out[key] = value
            }
        }
        return out
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
