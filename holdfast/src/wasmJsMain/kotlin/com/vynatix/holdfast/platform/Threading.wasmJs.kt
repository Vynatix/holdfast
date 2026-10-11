package com.vynatix.holdfast.platform

actual fun currentThreadId(): Long = 0L

actual fun threadYield() {
    // wasmJs runs on a single browser thread; yielding is a no-op.
}

/** atomicfu's wasmJs SynchronousMutex is a counting no-op: tryLock never counts. */
internal actual val tryLockCounts: Boolean = false
