package com.vynatix.holdfast.bridge

// Browser/wasmJs has no synchronous filesystem API. The expect class exists
// for parity with jvm/android/ios; instantiating on web throws. Web targets
// should use [InMemoryKvStore] or a browser-specific store (IndexedDB/
// localStorage) wired through [SuspendingKvStore] in :holdfast-coroutines.
actual class FileSystemKvStore actual constructor(rootPath: String) : KvStore {
    init {
        throw UnsupportedOperationException(
            "FileSystemKvStore is not available on wasmJs (rootPath=$rootPath). " +
                "Use InMemoryKvStore or a browser-storage-backed KvStore.",
        )
    }

    actual override fun get(key: String): String? = error("unreachable")
    actual override fun put(key: String, value: String): Unit = error("unreachable")
    actual override fun remove(key: String): Unit = error("unreachable")
    actual override fun snapshot(): Map<String, String> = error("unreachable")
}
