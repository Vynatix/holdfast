package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.tryLockCounted
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

    /**
     * Non-blocking [acquire]: deepen the lock if this thread already holds it,
     * take it if it is free, and otherwise return `false` at once instead of
     * parking. A `true` return must be paired with [release], exactly like
     * [acquire].
     *
     * Reentrancy is read the same way [acquire] reads it. On wasmJs every
     * caller reports thread id `0`, so a held lock always reads as this
     * thread's and the call deepens it; that is only sound because that
     * target is single-threaded, which [acquire] already assumes. The mutex
     * is only tried when [locked] is clear, which [tryLockCounted]'s
     * unconditional take on wasmJs relies on.
     */
    internal fun tryAcquire(): Boolean {
        val currentThreadId = currentThreadId()
        val acquired =
            when {
                locked && ownerThreadId == currentThreadId -> true
                mutex.tryLockCounted() -> {
                    locked = true
                    ownerThreadId = currentThreadId
                    lockCount = 0
                    true
                }
                else -> false
            }
        if (acquired) lockCount++
        return acquired
    }

    /**
     * Non-blocking, non-reentrant [acquire]: take the lock only if NO thread
     * holds it, this one included, and otherwise return `false` at once. A
     * `true` return must be paired with [release].
     *
     * For a caller that needs every other holder gone, not a deeper hold of
     * its own: a coroutine running inline inside a holder's critical section
     * on this thread must not count that holder as released. On wasmJs, where
     * every caller reports thread id `0`, any held lock reads as this
     * thread's, so the call returns `false` while anyone holds it — which is
     * what this caller needs there too.
     */
    internal fun tryAcquireIfUnheld(): Boolean {
        if (locked && ownerThreadId == currentThreadId()) return false
        return tryAcquire()
    }

    /**
     * Whether the calling thread holds this lock right now (at any depth).
     * On wasmJs, where every caller reports thread id `0`, this reads `true`
     * whenever the lock is held — correct there, as that target is
     * single-threaded.
     */
    internal fun isHeldByCurrentThread(): Boolean = locked && ownerThreadId == currentThreadId()

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
