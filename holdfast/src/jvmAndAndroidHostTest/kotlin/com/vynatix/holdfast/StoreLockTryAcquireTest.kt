package com.vynatix.holdfast

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `StoreLock.tryAcquire` backs the non-blocking top-level attempt a `derived` recompute makes. */
class StoreLockTryAcquireTest {
    @Test
    fun `tryAcquire takes a free lock and deepens it on the owning thread`() {
        val lock = StoreLock()
        assertTrue(lock.tryAcquire(), "a free lock is taken")
        assertTrue(lock.tryAcquire(), "the owner re-enters")
        lock.withLock { }
        lock.release()
        lock.release()
        // Fully unwound: another thread can take it without waiting.
        val other = AtomicBoolean(false)
        completesWithin(5, "tryAcquire on a released lock") {
            other.set(lock.tryAcquire())
            if (other.get()) lock.release()
        }
        assertTrue(other.get(), "the lock must be free once every depth is released")
    }

    @Test
    fun `tryAcquire returns false at once while another thread holds the lock`() {
        val lock = StoreLock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            daemon("lock-holder") {
                lock.withLock {
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val acquired = AtomicBoolean(true)
            completesWithin(5, "tryAcquire against a held lock") { acquired.set(lock.tryAcquire()) }
            assertFalse(acquired.get(), "a lock held by another thread is not taken")
        } finally {
            release.countDown()
            holder.join(10_000)
        }
        assertTrue(lock.tryAcquire(), "the lock is free again once the holder releases")
        lock.release()
    }
}
