package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.threadYield
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class HoldfastLock : SynchronizedObject() {
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

    private fun isReentrant(threadId: Long): Boolean = synchronized(this) {
        isLocked() && ownerThreadId == threadId
    }

    private fun isLocked(): Boolean = locked
}
