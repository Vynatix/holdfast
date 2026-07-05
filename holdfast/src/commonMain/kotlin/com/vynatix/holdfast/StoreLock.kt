package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.threadYield
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Reentrant spin-yield lock used internally to serialize a store's transactional
 * work. `@StoreInternalApi` — it is an implementation detail of the store kernel
 * (and companion modules that build on it), never a user-facing type.
 */
@StoreInternalApi
class StoreLock : SynchronizedObject() {
    @kotlin.concurrent.Volatile
    private var locked = false
    private var lockCount = 0
    private var ownerThreadId = 0L

    fun acquire() {
        val currentThreadId = currentThreadId()
        if (isReentrant(currentThreadId)) {
            lockCount++
            return
        }

        while (!tryAcquire(currentThreadId)) {
            threadYield()
        }
    }

    fun release() {
        val currentThreadId = currentThreadId()
        synchronized(this) {
            check(isLocked() && ownerThreadId == currentThreadId) {
                "Cannot release: lock not held by current thread"
            }
            lockCount--
            if (lockCount == 0) {
                locked = false
                ownerThreadId = 0
            }
        }
    }

    inline fun <T> withLock(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private fun tryAcquire(threadId: Long): Boolean {
        synchronized(this) {
            if (!locked) {
                locked = true
                lockCount = 1
                ownerThreadId = threadId
                return true
            }
            return false
        }
    }

    private fun isReentrant(threadId: Long): Boolean =
        synchronized(this) {
            isLocked() && ownerThreadId == threadId
        }

    private fun isLocked(): Boolean = locked
}
