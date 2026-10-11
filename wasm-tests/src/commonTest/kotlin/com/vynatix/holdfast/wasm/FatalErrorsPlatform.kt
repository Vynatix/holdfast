package com.vynatix.holdfast.wasm

/**
 * Throwables this platform treats as fatal (issue #61): every
 * `VirtualMachineError` on the JVM, `kotlin.OutOfMemoryError` on wasmJs (a
 * stack overflow is uncatchable by wasm code there).
 */
internal expect fun platformFatalErrors(): List<Throwable>
