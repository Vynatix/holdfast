package com.vynatix.holdfast

import com.vynatix.holdfast.platform.tryLockCounts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wasmJs claim route of [StoreLock.tryAcquire] (`tryLockCounts = false`):
 * atomicfu's wasmJs `SynchronousMutex` is a counting no-op whose `tryLock`
 * NEVER counts, so a tryLock-claim paired with `release()`'s `unlock`
 * underflowed the counter and threw "Mutex already unlocked" — the wasm
 * break that surfaced from Astrid's hydration gate
 * (`internalTransactionLockFree`), `tryTopLevelAction` and the post-commit
 * drain (13/15 post-sign-in web captures; PR #40 fixed InitializerGraph's
 * twin of it). On the modelled route the real JVM mutex plays the counter:
 * any claim/release imbalance the route leaves would strand the mutex and
 * hang the next bracket, which these tests bound with [completesWithin].
 */
class StoreLockWasmClaimTest {
    @Test
    fun `wasm route claims a free lock and releases it, repeatedly`() {
        val lock = StoreLock(tryLockCounts = false)
        repeat(10_000) {
            assertTrue(lock.tryAcquire(), "a free lock is taken (iteration $it)")
            lock.release()
        }
        // The counter is balanced: the blocking pair still runs at once.
        completesWithin(5, "withLock after wasm-route claims") { lock.withLock { } }
    }

    @Test
    fun `wasm route deepens reentrantly and unwinds at the outermost depth`() {
        val lock = StoreLock(tryLockCounts = false)
        assertTrue(lock.tryAcquire(), "a free lock is taken")
        lock.acquire() // the owner deepens through acquire
        assertTrue(lock.tryAcquire(), "the owner deepens through tryAcquire")
        lock.release()
        lock.release()
        lock.release()
        // Fully unwound: a fresh claim must not wait on a leaked count.
        completesWithin(5, "claim after wasm-route brackets") { lock.withLock { } }
    }

    @Test
    fun `wasm route answers false at once while another thread holds the lock`() {
        val lock = StoreLock(tryLockCounts = false)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            daemon("wasm-route-holder") {
                lock.withLock {
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val acquired = AtomicBoolean(true)
            // The route must answer from the owner-state — a bare lock() claim
            // would park here until the holder releases.
            completesWithin(5, "wasm route against a held lock") { acquired.set(lock.tryAcquire()) }
            assertFalse(acquired.get(), "a lock held by another thread is not taken")
        } finally {
            release.countDown()
            holder.join(10_000)
        }
        assertTrue(lock.tryAcquire(), "the lock is free again once the holder releases")
        lock.release()
    }

    @Test
    fun `wasm route probe sees a held lock as held and a free one as free`() {
        val lock = StoreLock(tryLockCounts = false)
        lock.acquire()
        assertFalse(lock.tryAcquireIfUnheld(), "a held lock is not unheld, this thread's included")
        assertTrue(lock.isHeldByCurrentThread(), "the holder is the current thread")
        lock.release()
        assertTrue(lock.tryAcquireIfUnheld(), "a free lock is taken")
        lock.release()
        completesWithin(5, "withLock after probes") { lock.withLock { } }
    }

    @Test
    fun `the platform seam counts tryLock on the JVM`() {
        assertTrue(tryLockCounts, "the JVM's SynchronousMutex.tryLock counts; only wasmJs models false")
    }
}
