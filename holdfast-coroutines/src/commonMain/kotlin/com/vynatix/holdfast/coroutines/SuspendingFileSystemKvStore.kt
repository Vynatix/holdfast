package com.vynatix.holdfast.coroutines

/**
 * File-system-backed [SuspendingKvStore]: each key maps to a single file under
 * [directory]. Writes are atomic (tempfile + rename) on every supported platform.
 *
 * All operations suspend on the platform's I/O dispatcher (`Dispatchers.IO` on
 * JVM/Android, an equivalent background dispatcher on iOS). The calling thread is
 * never blocked.
 *
 * Key encoding: keys are URL-encoded so they're safe filename characters. The
 * actual on-disk filename is `<directory>/<urlencoded(key)>`.
 *
 * Failure modes are platform-specific: JVM throws `java.io.IOException` family;
 * Native throws `IllegalStateException` with the underlying errno description.
 *
 * **Multi-process safety is the caller's responsibility** — this store does not
 * coordinate cross-process file locks. Concurrent writes from a single process
 * are safe (each write goes through tmp + atomic rename).
 *
 * Use as the backing for `SuspendingBridge` to get save-on-commit + load-on-attach
 * persistence with await-completion semantics:
 * ```
 * val kv = SuspendingFileSystemKvStore("/path/to/state")
 * store { balance suspendBridge SuspendingBridge(kv, "balance", LongCodec) }
 * ```
 */
expect class SuspendingFileSystemKvStore(
    directory: String,
) : SuspendingKvStore {
    override suspend fun get(key: String): String?

    override suspend fun put(
        key: String,
        value: String,
    )

    override suspend fun remove(key: String)

    override suspend fun snapshot(): Map<String, String>
}
