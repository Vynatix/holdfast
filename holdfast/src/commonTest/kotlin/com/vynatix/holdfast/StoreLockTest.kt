package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class VaultLockBasicsTest {

    @Test
    fun withLockReturnsBlockResult() {
        val lock = HoldfastLock()
        val result = lock.withLock { 42 }
        assertEquals(42, result)
    }

    @Test
    fun withLockReleasesLockOnExceptionAndPropagatesException() {
        val lock = HoldfastLock()
        val ex = assertFailsWith<RuntimeException> {
            lock.withLock { throw RuntimeException("boom") }
        }
        assertEquals("boom", ex.message)
        // Lock was released — re-acquire succeeds
        val after = lock.withLock { "ok" }
        assertEquals("ok", after)
    }

    @Test
    fun acquireFromSameThreadIsReentrantAndIncrementsLockCount() {
        val lock = HoldfastLock()
        val results = mutableListOf<Int>()
        lock.withLock {
            results.add(1)
            lock.withLock {
                results.add(2)
                lock.withLock {
                    results.add(3)
                }
            }
        }
        assertEquals(listOf(1, 2, 3), results)
        // After all releases, lock can be acquired by anyone (here, same thread again)
        lock.withLock { results.add(4) }
        assertEquals(listOf(1, 2, 3, 4), results)
    }

    @Test
    fun nestedReentrantWithLockBlocksUnwindLockCountInOrder() {
        val lock = HoldfastLock()
        val depth = mutableListOf<Int>()
        lock.withLock {
            depth.add(1)
            lock.withLock {
                depth.add(2)
                lock.withLock {
                    depth.add(3)
                }
                depth.add(4)
            }
            depth.add(5)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), depth)
    }
}

class VaultLockErrorPathTest {

    /**
     * Cross-thread lock test: `newSingleThreadContext` pins owner and intruder to
     * dedicated threads so coroutine suspension doesn't shuffle the lock owner thread.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun releaseFromNonOwnerThreadThrowsIllegalStateException() {
        val ownerCtx = newSingleThreadContext("vault-lock-owner")
        val intruderCtx = newSingleThreadContext("vault-lock-intruder")
        try {
            val captured = runBlocking {
                val lock = HoldfastLock()
                val acquired = CompletableDeferred<Unit>()
                val canRelease = CompletableDeferred<Unit>()
                val foreignReleaseError = atomic<Throwable?>(null)

                val owner = async(ownerCtx) {
                    lock.acquire()
                    acquired.complete(Unit)
                    canRelease.await()
                    lock.release()
                }
                acquired.await()

                val intruder = async(intruderCtx) {
                    try {
                        lock.release()
                    } catch (e: Throwable) {
                        foreignReleaseError.value = e
                    }
                }
                intruder.await()

                canRelease.complete(Unit)
                owner.await()

                foreignReleaseError.value
            }

            assertNotNull(captured, "release from non-owner thread must throw")
            assertIs<IllegalStateException>(captured)
        } finally {
            ownerCtx.close()
            intruderCtx.close()
        }
    }

    @Test
    fun releaseWithoutPriorAcquireThrowsIllegalStateException() {
        val lock = HoldfastLock()
        assertFailsWith<IllegalStateException> { lock.release() }
    }
}

class VaultLockContentionTest {

    /**
     * Owner pinned to a single dedicated thread; waiters use `Dispatchers.Default`
     * so they're scheduled on different threads. Without a dedicated owner context,
     * coroutine suspension would let the owner's thread be reused for a waiter,
     * making the lock falsely reentrant.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun concurrentAcquireBlocksUntilOwnerReleasesAndAllWaitersEventuallyAcquire() {
        val ownerCtx = newSingleThreadContext("vault-lock-contention-owner")
        try {
            runBlocking {
                val lock = HoldfastLock()
                val acquireCount = atomic(0)
                val firstAcquired = CompletableDeferred<Unit>()
                val canFirstRelease = CompletableDeferred<Unit>()

                val first = async(ownerCtx) {
                    lock.withLock {
                        firstAcquired.complete(Unit)
                        canFirstRelease.await()
                    }
                }
                firstAcquired.await()

                val waiterCount = 4
                val waiters = List(waiterCount) {
                    async(Dispatchers.Default) {
                        lock.withLock {
                            acquireCount.incrementAndGet()
                        }
                    }
                }

                // Give waiters a chance to start spinning on the lock.
                delay(150)

                assertEquals(
                    0,
                    acquireCount.value,
                    "no waiter should have acquired while owner holds the lock",
                )

                canFirstRelease.complete(Unit)
                first.await()
                waiters.awaitAll()

                assertEquals(waiterCount, acquireCount.value, "all waiters must eventually acquire")
            }
        } finally {
            ownerCtx.close()
        }
    }

    @Test
    fun manyWaitersUnderHighContentionAllEventuallyAcquireWithoutPerpetualStarvation() = runBlocking {
        val lock = HoldfastLock()
        val workers = 16
        val opsPerWorker = 50
        val totalOps = atomic(0)

        val finished = withTimeoutOrNull(30_000) {
            val jobs = List(workers) {
                async(Dispatchers.Default) {
                    repeat(opsPerWorker) {
                        lock.withLock {
                            totalOps.incrementAndGet()
                        }
                    }
                }
            }
            jobs.awaitAll()
            totalOps.value
        }

        assertNotNull(finished, "all $workers x $opsPerWorker acquisitions must complete within 30s")
        assertEquals(workers * opsPerWorker, finished)
    }

    @Test
    fun aGreedyReentrantHolderDoesNotBlockOtherWaitersAfterFinalRelease() = runBlocking {
        val lock = HoldfastLock()
        val acquired = atomic(0)

        val greedy = async(Dispatchers.Default) {
            lock.withLock {
                lock.withLock {
                    lock.withLock {
                        lock.withLock {
                            // four nested levels, all on one coroutine/thread
                        }
                    }
                }
            }
        }

        val waiters = List(4) {
            async(Dispatchers.Default) {
                lock.withLock {
                    acquired.incrementAndGet()
                }
            }
        }

        greedy.await()
        waiters.awaitAll()

        assertEquals(4, acquired.value, "all 4 waiters must acquire after greedy holder finished")
    }
}
