package com.vynatix.vault.platform

actual fun currentThreadId(): Long = 0L

actual fun threadYield() {
    // wasmJs runs on a single browser thread; yielding is a no-op.
}
