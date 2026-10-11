package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.tryLockCounted
import com.vynatix.holdfast.platform.tryLockCounts as platformTryLockCounts
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
 *
 * wasmJs counter rule (holdfast PR #40's doctrine, applied here): atomicfu's
 * wasmJs `SynchronousMutex` is a counting no-op — `lock` counts, `unlock`
 * counts down (throwing "Mutex already unlocked" at zero) and `tryLock` NEVER
 * counts, succeeding without claiming. So every acquire this class makes is
 * `lock`/`tryLock`-where-it-counts, never a bare wasmJs `tryLock`, or the
 * matching `release()` would underflow the counter. See [tryAcquire].
 */
class StoreLock
internal constructor(
    private val tryLockCounts: Boolean,
) {
    /** Production locks: the platform's claim rule ([platformTryLockCounts]). */
    constructor() : this(platformTryLockCounts)

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
     * unconditional take on wasmJs relies on: wasmJs's [SynchronousMutex.tryLock]
     * never counts, so a tryLock-claim paired with [release]'s unlock would
     * decrement a zero counter and throw "Mutex already unlocked" — the wasm
     * break PR #40's InitializerGraph fix first found; this was its surviving
     * twin, filed as PR #48 and independently fixed upstream by #47's
     * [tryLockCounted], which this branch now adopts.
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
     * Claim a lock the owner-state read as free, for [tryAcquire]:
     * [SynchronousMutex.tryLock] where it counts, [SynchronousMutex.lock]
     * where it does not (wasmJs — see [tryAcquire]). Writes the owner fields
     * only when the claim succeeds; a failed JVM `tryLock` (the flags lagged a
     * take or release on another thread) claims nothing.
     */
    private fun claimFree(currentThreadId: Long): Boolean =
        if (tryLockCounts) {
            if (mutex.tryLock()) {
                locked = true
                ownerThreadId = currentThreadId
                lockCount = 0
                true
            } else {
                false
            }
        } else {
            // wasmJs: counts, and cannot park — the flags are exact on its
            // single thread (no suspension happens inside a StoreLock bracket).
            mutex.lock()
            locked = true
            ownerThreadId = currentThreadId
            lockCount = 0
            true
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
