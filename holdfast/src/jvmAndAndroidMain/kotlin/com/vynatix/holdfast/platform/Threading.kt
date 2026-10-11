package com.vynatix.holdfast.platform

actual fun currentThreadId(): Long = Thread.currentThread().threadId()

actual fun threadYield() {
    Thread.yield()
}

/** The JVM's SynchronousMutex is a real lock: tryLock counts. */
internal actual val tryLockCounts: Boolean = true
