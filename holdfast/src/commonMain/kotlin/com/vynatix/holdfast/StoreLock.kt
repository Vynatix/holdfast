package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.locks.SynchronousMutex

/**
 * Reentrant mutual exclusion for one store-internal structure.
 *
 * Blocking is delegated to [SynchronousMutex], which parks a waiting thread.
 * This lock previously spun on `tryAcquire` + `threadYield` until it won, which
 * made every contended critical section — and `action` holds one across the
 * whole user body plus commit fanout — burn a core per waiter, and left waiting
 * threads in `RUNNABLE`, where no thread dump, deadlock detector or profiler
 * reports them as blocked.
 *
 * Reentrancy is tracked here rather than delegated: [SynchronousMutex] is not
 * reentrant, so the underlying mutex is taken only on the outermost acquire and
 * released only when the matching depth unwinds.
 */
class StoreLock {
    private val mutex = SynchronousMutex()

    /**
     * Whether the mutex is currently held. Kept alongside [ownerThreadId] rather
     * than folded into it because `currentThreadId()` is `0` for every caller on
     * wasmJs, which collides with the "unowned" sentinel.
     */
    @kotlin.concurrent.Volatile
    private var locked = false

    @kotlin.concurrent.Volatile
    private var ownerThreadId = 0L

    /** Reentrancy depth. Only ever read or written by the owning thread. */
    private var lockCount = 0

    fun acquire() {
        val currentThreadId = currentThreadId()
        // Safe unsynchronized: both fields are volatile, and the only thread that
        // can see them naming itself as owner is the owner, which alone mutates
        // lockCount while holding the mutex.
        if (locked && ownerThreadId == currentThreadId) {
            lockCount++
            return
        }
        mutex.lock()
        locked = true
        ownerThreadId = currentThreadId
        lockCount = 1
    }

    fun release() {
        val currentThreadId = currentThreadId()
        check(locked && ownerThreadId == currentThreadId) {
            "Cannot release: lock not held by current thread"
        }
        lockCount--
        if (lockCount == 0) {
            locked = false
            ownerThreadId = 0L
            mutex.unlock()
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
}
