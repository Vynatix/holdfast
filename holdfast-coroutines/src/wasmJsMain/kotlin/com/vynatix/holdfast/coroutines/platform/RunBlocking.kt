package com.vynatix.holdfast.coroutines.platform

internal actual fun <T> runBlockingForInitialSeed(body: suspend () -> T): T =
    throw UnsupportedOperationException(
        "suspendDerived's eager initial seed requires runBlocking, which is not " +
            "available on wasmJs (the JS event loop cannot block). Seed asynchronously " +
            "via `store.suspendAction { ... }` instead.",
    )
