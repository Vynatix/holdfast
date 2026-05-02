package com.vynatix.vault.platform

actual fun currentThreadId(): Long = Thread.currentThread().threadId()

actual fun threadYield() {
    Thread.yield()
}
