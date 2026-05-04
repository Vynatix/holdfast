package com.vynatix.holdfast.coroutines

// Browser/wasmJs has no filesystem API. The expect class exists for parity
// with jvm/android/ios; instantiating on web throws. Web targets should use
// [InMemorySuspendingKvStore] or a browser-storage-backed SuspendingKvStore
// (IndexedDB/localStorage).
actual class SuspendingFileSystemKvStore actual constructor(directory: String) : SuspendingKvStore {
    init {
        throw UnsupportedOperationException(
            "SuspendingFileSystemKvStore is not available on wasmJs (directory=$directory). " +
                "Use InMemorySuspendingKvStore or a browser-storage-backed SuspendingKvStore.",
        )
    }

    actual override suspend fun get(key: String): String? = error("unreachable")
    actual override suspend fun put(key: String, value: String): Unit = error("unreachable")
    actual override suspend fun remove(key: String): Unit = error("unreachable")
    actual override suspend fun snapshot(): Map<String, String> = error("unreachable")
}
