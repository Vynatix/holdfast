package com.vynatix.holdfast.platform

// A stack overflow is uncatchable by wasm code; kotlin.OutOfMemoryError is
// what the stdlib throws on a capacity overflow.
internal actual fun Throwable.isFatal(): Boolean = this is OutOfMemoryError
