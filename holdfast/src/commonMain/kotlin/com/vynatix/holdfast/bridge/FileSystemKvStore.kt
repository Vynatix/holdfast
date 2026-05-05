package com.vynatix.holdfast.bridge

/**
 * File-system-backed [KvStore]: each key maps to a single file under [rootPath].
 * Writes are atomic (tempfile + rename) on every supported platform.
 *
 * Key encoding: keys are URL-encoded so they're safe filename characters. The
 * actual on-disk filename is `<root>/<urlencoded(key)>`.
 *
 * Failure modes are platform-specific. JVM throws `java.io.IOException` family;
 * Native throws `IllegalStateException` with the underlying errno description.
 *
 * Use as the backing for [KvBridge] to get save-on-commit + load-on-attach
 * persistence with zero extra code:
 * ```
 * val kv = FileSystemKvStore(System.getProperty("user.home") + "/.myapp")
 * store { balance bridge KvBridge(kv, "balance", LongCodec) }
 * ```
 */
expect class FileSystemKvStore(rootPath: String) : KvStore {
    override fun get(key: String): String?
    override fun put(key: String, value: String)
    override fun remove(key: String)
    override fun snapshot(): Map<String, String>
}
