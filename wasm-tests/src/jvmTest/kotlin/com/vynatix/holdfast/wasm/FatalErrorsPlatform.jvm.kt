package com.vynatix.holdfast.wasm

internal actual fun platformFatalErrors(): List<Throwable> = listOf(StackOverflowError(), OutOfMemoryError())
