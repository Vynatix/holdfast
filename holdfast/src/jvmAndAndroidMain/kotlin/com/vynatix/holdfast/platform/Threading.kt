package com.vynatix.holdfast.platform

actual fun currentThreadId(): Long = Thread.currentThread().threadId()

actual fun threadYield() {
    Thread.yield()
}
