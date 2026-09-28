package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class HandoffSource : Store<HandoffSource>() {
    val x by state { 1 }
}

private class HandoffHost : Store<HandoffHost>() {
    val y by state { 0 }
}

/**
 * A serializer that can park its next blocking acquirer right after it takes
 * the permit: inside `action`, holding the store's serializer, before that
 * action has installed a transaction. That is the state a store is in while
 * a coroutine that was just handed its mutex has not resumed yet, made
 * deterministic.
 */
@OptIn(StoreInternalApi::class)
private class ParkingSerializer : Store.AsyncSerializer {
    private val permit = Semaphore(1)
    private val parkNext = AtomicBoolean(false)
    val parked = CountDownLatch(1)
    val release = CountDownLatch(1)

    fun parkNextAcquirer() = parkNext.set(true)

    override fun blockingAcquire() {
        permit.acquire()
        if (parkNext.compareAndSet(true, false)) {
            parked.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    override fun blockingRelease() = permit.release()

    override fun tryBlockingAcquire(): Boolean = permit.tryAcquire()
}

/**
 * A serializer whose FIRST failed non-blocking acquire lets the parked holder
 * finish its whole action — commit, release, and its post-commit drain of a
 * still-empty queue — before reporting busy. The caller therefore hands its
 * task off only AFTER that holder's drain: exactly the window the recompute's
 * hand-off retry exists to close, made deterministic.
 */
@OptIn(StoreInternalApi::class)
private class ReleasingOnFirstTrySerializer : Store.AsyncSerializer {
    private val permit = Semaphore(1)
    private val parkNext = AtomicBoolean(false)
    private val handOffOnce = AtomicBoolean(true)
    val parked = CountDownLatch(1)
    val release = CountDownLatch(1)

    /** Counted down by the test once the parked holder's `action` (drain included) has returned. */
    val holderDone = CountDownLatch(1)

    /** Whether the busy report really came after the holder's drain; guards against a vacuous pass. */
    val raced = AtomicBoolean(false)

    fun parkNextAcquirer() = parkNext.set(true)

    override fun blockingAcquire() {
        permit.acquire()
        if (parkNext.compareAndSet(true, false)) {
            parked.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    override fun blockingRelease() = permit.release()

    override fun tryBlockingAcquire(): Boolean {
        if (permit.tryAcquire()) return true
        if (handOffOnce.compareAndSet(true, false)) {
            // Let the holder run, release and drain an EMPTY queue…
            release.countDown()
            check(holderDone.await(10, TimeUnit.SECONDS)) { "the released holder did not finish" }
            raced.set(true)
        }
        // …then report busy, so the caller hands off after that drain.
        return false
    }
}

/**
 * A serializer whose [stallOnRelease]-th release parks, still holding the
 * permit, until [resume] opens: a non-blocking attempt that took the
 * serializer, found the store's lock busy, and is about to give the
 * serializer back.
 */
@OptIn(StoreInternalApi::class)
private class StallingReleaseSerializer(
    private val stallOnRelease: Int,
) : Store.AsyncSerializer {
    private val permit = Semaphore(1)
    private val releases = AtomicInteger()

    /** Non-blocking acquires that found the permit taken. */
    val failedTries = AtomicInteger()
    val stalled = CountDownLatch(1)
    val resume = CountDownLatch(1)

    override fun blockingAcquire() = permit.acquire()

    override fun tryBlockingAcquire(): Boolean = permit.tryAcquire().also { if (!it) failedTries.incrementAndGet() }

    override fun blockingRelease() {
        if (releases.incrementAndGet() == stallOnRelease) {
            stalled.countDown()
            resume.await(30, TimeUnit.SECONDS)
        }
        permit.release()
    }
}

/**
 * A `derived` hosted on one store with a source on another recomputes from
 * inside the SOURCE's commit fanout, while the source's `transactionLock` is
 * held. It used to recompute with a blocking `action` on the host, so a host
 * held with no transaction visible yet stalled the source's commit — a
 * deadlock if that holder then needed the source store. The recompute now
 * commits through a non-blocking top-level attempt and, when the host is
 * busy, hands itself to the host's current holder, which runs it after
 * releasing.
 */
@OptIn(StoreInternalApi::class)
class DerivedHandoffTest {
    /** Fails without the fix: the source's `action` waits on the host's serializer until the watchdog fires. */
    @Test
    fun `a source commit never waits for the busy host of its derived`() {
        val source = HandoffSource()
        val host = HandoffHost()
        val serializer = ParkingSerializer()
        host.asyncSerializer = serializer
        val (doubled, d) = host.derived(source.x) { source.x.value * 2 }
        try {
            serializer.parkNextAcquirer()
            val hostAction = daemon("busy-host-action") { host action { y mutate 1 } }
            assertTrue(serializer.parked.await(10, TimeUnit.SECONDS), "the host action should hold the serializer")

            completesWithin(5, "a source commit while its derived's host is busy") {
                source action { x mutate 5 }
            }
            assertEquals(5, source.x.value)
            assertEquals(2, doubled.value, "the host is still busy, so the recompute is waiting for it")

            serializer.release.countDown()
            hostAction.join(10_000)
            assertFalse(hostAction.isAlive, "the host action should finish once released")
            assertEquals(10, doubled.value, "the host's action ran the handed-off recompute after it released")
            assertEquals(1, host.y.value)
        } finally {
            serializer.release.countDown()
            d.dispose()
        }
    }

    /**
     * The same hand-off when the host's business is a visible transaction
     * (an action parked in its body). The old path queued here too, so this
     * guards convergence rather than reproducing the defect.
     */
    @Test
    fun `a host whose action is parked recomputes the derived when that action ends`() {
        val source = HandoffSource()
        val host = HandoffHost()
        val (doubled, d) = host.derived(source.x) { source.x.value * 2 }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val hostAction =
                daemon("parked-host-action") {
                    host action {
                        entered.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        y mutate 1
                    }
                }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the host action should be running")

            completesWithin(5, "a source commit while its derived's host is in an action") {
                source action { x mutate 7 }
            }
            assertEquals(2, doubled.value, "the host is still in its action, so the recompute is waiting for it")

            release.countDown()
            hostAction.join(10_000)
            assertFalse(hostAction.isAlive, "the host action should finish once released")
            assertEquals(14, doubled.value)
        } finally {
            release.countDown()
            d.dispose()
        }
    }

    /**
     * The recompute finds the host busy, but the host's holder then releases —
     * and drains its still-empty queue — before the recompute hands itself
     * off. Nobody is left to drain the hand-off, so only the recompute's own
     * retry can run it. Fails without that retry: the derived stays at its
     * initial value until some unrelated later host action.
     */
    @Test
    fun `a recompute whose holder releases between the busy attempt and the hand-off still runs`() {
        val source = HandoffSource()
        val host = HandoffHost()
        val serializer = ReleasingOnFirstTrySerializer()
        host.asyncSerializer = serializer
        val (doubled, d) = host.derived(source.x) { source.x.value * 2 }
        try {
            serializer.parkNextAcquirer()
            val hostAction =
                daemon("releasing-host-action") {
                    host action { y mutate 1 }
                    serializer.holderDone.countDown()
                }
            assertTrue(serializer.parked.await(10, TimeUnit.SECONDS), "the host action should hold the serializer")

            completesWithin(10, "a source commit whose derived's host releases mid-attempt") {
                source action { x mutate 5 }
            }
            assertTrue(serializer.raced.get(), "the busy report must come after the holder's drain")
            assertEquals(1, host.y.value, "the holder's action committed before the hand-off")
            assertEquals(10, doubled.value, "the hand-off retry must run the recompute nobody else will drain")
            hostAction.join(10_000)
        } finally {
            serializer.release.countDown()
            serializer.holderDone.countDown()
            d.dispose()
        }
    }

    /**
     * A lock-only holder (the harness's open-transaction commit, or an
     * `action` that read the serializer before a coroutine installed it)
     * drains after it releases, but its drain can meet the serializer held by
     * a recompute retry that already found the lock busy and is about to back
     * out. That drain leaves the recompute queued, and the retry is then the
     * last holder left: it must drain once it lets go. Fails without that
     * drain: the derived stays at its initial value until some unrelated later
     * host action.
     */
    @Test
    fun `a recompute retry that backs out of the serializer drains what a lock-only holder left queued`() {
        val source = HandoffSource()
        val host = HandoffHost()
        // Release 1 is the recompute's first attempt; release 2 is its retry.
        val serializer = StallingReleaseSerializer(stallOnRelease = 2)
        host.asyncSerializer = serializer
        val (doubled, d) = host.derived(source.x) { source.x.value * 2 }
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        try {
            // Takes the lock without the serializer and drains after releasing
            // it, exactly like the harness's commitOpenTransaction.
            val lockHolder =
                daemon("lock-only-holder") {
                    host.runUnderLock {
                        lockHeld.countDown()
                        releaseLock.await(30, TimeUnit.SECONDS)
                    }
                    host.internalDrainPostCommitTasks()
                }
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "the holder should take the lock")

            val sourceCommit = daemon("source-commit") { source action { x mutate 5 } }
            assertTrue(
                serializer.stalled.await(10, TimeUnit.SECONDS),
                "the recompute's retry should find the lock busy and start backing out",
            )

            releaseLock.countDown()
            lockHolder.join(10_000)
            assertFalse(lockHolder.isAlive, "the lock-only holder should finish")
            assertTrue(serializer.failedTries.get() > 0, "the holder's drain must meet the serializer the retry holds")
            assertEquals(2, doubled.value, "the holder's drain could not run the recompute")

            serializer.resume.countDown()
            sourceCommit.join(10_000)
            assertFalse(sourceCommit.isAlive, "the source commit should finish once the retry lets go")
            assertEquals(5, source.x.value)
            assertEquals(10, doubled.value, "the retry that backed out last must drain the recompute left queued")
        } finally {
            releaseLock.countDown()
            serializer.resume.countDown()
            d.dispose()
        }
    }

    /**
     * When the hand-off retry does take the store, a middleware that rejects
     * the recompute must not leave the handed-off copy queued: the drain that
     * follows would run the recompute — and report its failure — a second
     * time.
     */
    @Test
    fun `a recompute rejected by middleware after a hand-off runs and is reported once`() {
        val source = HandoffSource()
        val host = HandoffHost()
        val serializer = ReleasingOnFirstTrySerializer()
        host.asyncSerializer = serializer
        val attempts = AtomicInteger()
        host.middlewares(
            object : Middleware<HandoffHost>() {
                override fun onTransactionStarted(context: MiddlewareContext<HandoffHost>) {
                    if (context.transaction.id.startsWith("__derived_")) {
                        attempts.incrementAndGet()
                        error("recompute rejected")
                    }
                }
            },
        )
        val reported = ConcurrentLinkedQueue<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        val (doubled, d) = host.derived(source.x) { source.x.value * 2 }
        try {
            serializer.parkNextAcquirer()
            val hostAction =
                daemon("releasing-host-action") {
                    host action { y mutate 1 }
                    serializer.holderDone.countDown()
                }
            assertTrue(serializer.parked.await(10, TimeUnit.SECONDS), "the host action should hold the serializer")

            completesWithin(10, "a source commit whose derived's recompute is rejected") {
                source action { x mutate 5 }
            }
            assertTrue(serializer.raced.get(), "the recompute must have been handed off first")
            assertEquals(1, attempts.get(), "the rejected recompute must run once")
            assertEquals(listOf("recompute rejected"), reported.map { it.message }, "and be reported once")
            assertEquals(2, doubled.value, "a rejected recompute keeps the previous value")
            hostAction.join(10_000)
        } finally {
            serializer.release.countDown()
            serializer.holderDone.countDown()
            d.dispose()
        }
    }

    /**
     * Two stores each hosting a derived of the other, committed concurrently
     * from two threads. Nothing may block across the pair, and once both
     * threads are done every hand-off must have been drained by some holder:
     * a lost hand-off shows up as a derived that is stale for good.
     */
    @Test
    fun `stores deriving from each other converge under concurrent commits`() {
        val a = HandoffSource()
        val b = HandoffHost()
        val (aFromB, dA) = a.derived(b.y) { b.y.value + 1_000 }
        val (bFromA, dB) = b.derived(a.x) { a.x.value + 2_000 }
        val rounds = 5_000
        try {
            completesWithin(60, "concurrent commits on two stores deriving from each other") {
                val start = CyclicBarrier(2)
                val onA =
                    daemon("commits-on-a") {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(rounds) { i -> a action { x mutate i } }
                    }
                val onB =
                    daemon("commits-on-b") {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(rounds) { i -> b action { y mutate i } }
                    }
                onA.join()
                onB.join()
            }
            assertEquals(rounds - 1, a.x.value)
            assertEquals(rounds - 1, b.y.value)
            assertEquals(b.y.value + 1_000, aFromB.value, "a's derived of b must converge")
            assertEquals(a.x.value + 2_000, bFromA.value, "b's derived of a must converge")
        } finally {
            dA.dispose()
            dB.dispose()
        }
    }
}
