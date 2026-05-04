package com.vynatix.holdfast.coroutines.platform

// Runs [body] on the calling thread, blocking until completion. Used by
// `suspendDerived` to seed its backing state synchronously at construction.
//
// Available on jvm/android/ios via `kotlinx.coroutines.runBlocking`. Not
// available on wasmJs: the JS event loop is single-threaded and cannot
// block, so the wasmJs actual throws [UnsupportedOperationException].
// Web callers must seed asynchronously via `suspendAction` instead of
// constructing `suspendDerived` directly.
internal expect fun <T> runBlockingForInitialSeed(body: suspend () -> T): T
