@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** Counts how often `getOrCreate`/`create` name a key through the branch's codec. */
private class PkCountingKeyCodec : StateCodec<Int> {
    val encodes = AtomicInteger()

    override fun encode(value: Int): String {
        encodes.incrementAndGet()
        return value.toString()
    }

    override fun decode(string: String): Int = string.toInt()
}

private class PkStore(
    val id: Int,
) : Store<PkStore>() {
    val n by state { id }
}

/**
 * The branch's factory is declared once and records every run — the
 * thread ([runThreads]) and the store it built ([built]). A run on the
 * thread named [gatedThread] parks on [gate] after building its store; a
 * run on the thread named [failingThread] throws after building it.
 */
private class PkParent(
    codec: PkCountingKeyCodec,
) : Store<PkParent>() {
    val runThreads = ConcurrentLinkedQueue<String>()
    val built = ConcurrentLinkedQueue<PkStore>()

    @Volatile
    var gate: CountDownLatch? = null

    @Volatile
    var gatedThread: String? = null

    @Volatile
    var failingThread: String? = null

    val slow by keyed<Int, PkStore>(keyCodec = codec) { id ->
        val thread = Thread.currentThread().name
        runThreads += thread
        val store = PkStore(id).also { built += it }
        if (thread == gatedThread) gate?.await()
        check(thread != failingThread) { "refused after construction" }
        store
    }
}

private const val SAMPLE_MS = 500L
private const val WARM_UP_MS = 100L
private const val SAMPLE_EVERY_MS = 5L
private const val MAX_RUNNABLE_SHARE = 0.2
private const val WAIT_SECONDS = 10L

/**
 * `KeyedBranch.getOrCreate` under races. The factory runs holding no lock,
 * so a racing `getOrCreate` never waits on another thread's factory: it
 * runs the factory itself, and the first store to claim the key wins —
 * every caller gets it and the losers' stores are disposed, never attached.
 * It parks (never spins) only while another thread finishes ATTACHING the
 * key's store, which runs no user code.
 */
class KeyedGetOrCreateParkingTest {
    @Test
    fun aGetOrCreateParksWhileAnotherThreadIsStillAttachingTheKey() =
        completesWithin(30, "getOrCreate against an attach still being announced") {
            val codec = PkCountingKeyCodec()
            val parent = PkParent(codec)
            val announcing = CountDownLatch(1)
            val release = CountDownLatch(1)
            parent.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onAttached(leaf: LeafNode) {
                        // Test-only stall of attach phase 6 (no user code runs there otherwise).
                        announcing.countDown()
                        release.await()
                    }
                },
            )
            val created = AtomicReference<PkStore?>(null)
            val creator = daemon("creator") { created.set(parent.slow.create(1)) }
            assertTrue(announcing.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val encodesBefore = codec.encodes.get()
            val result = AtomicReference<PkStore?>(null)
            val waiter = daemon("waiter") { result.set(parent.slow.getOrCreate(1)) }
            val share = runnableShare(waiter)
            assertNull(result.get(), "the waiter returned before the attach was announced")
            assertTrue(
                share < MAX_RUNNABLE_SHARE,
                "the waiting getOrCreate was runnable in ${(share * 100).toInt()}% of samples: it spins on the " +
                    "registry instead of parking on the construction lock",
            )
            release.countDown()
            creator.join()
            waiter.join()
            assertSame(created.get(), result.get(), "the waiter gets the creator's instance")
            assertEquals(listOf("creator"), parent.runThreads.toList(), "a live key is never built again")
            assertEquals(1, codec.encodes.get() - encodesBefore, "named once for the whole wait")
        }

    @Test
    fun aGetOrCreateNeverWaitsOnAnotherThreadsFactoryAndTheFirstToAttachWins() =
        completesWithin(30, "getOrCreate against a running factory") {
            val codec = PkCountingKeyCodec()
            val parent = PkParent(codec)
            val gate = CountDownLatch(1)
            parent.gate = gate
            parent.gatedThread = "creator"
            val creatorOutcome = AtomicReference<Result<PkStore>?>(null)
            val creator = daemon("creator") { creatorOutcome.set(runCatching { parent.slow.getOrCreate(2) }) }
            awaitUntil("the creator parked inside its factory") { parent.runThreads.size == 1 }
            val encodesBefore = codec.encodes.get()
            // The factory is not a lock: the waiter runs it itself and wins while the creator is parked.
            val waiterStore = parent.slow.getOrCreate(2)
            assertSame(waiterStore, parent.slow[2])
            assertEquals(2, parent.runThreads.size, "the waiter ran the factory itself: ${parent.runThreads}")
            assertEquals(1, codec.encodes.get() - encodesBefore, "named once")
            gate.countDown()
            creator.join()
            val creatorStore = creatorOutcome.get()!!.getOrThrow()
            assertSame(waiterStore, creatorStore, "the losing getOrCreate returns the winner's store")
            val losers = parent.built.filter { it !== waiterStore }
            assertEquals(1, losers.size)
            assertTrue(losers.single().isDisposed, "the loser's freshly built store is disposed")
            assertNull(parent.tree.nodeOf(losers.single()), "a loser's store is never attached")
            assertFalse(waiterStore.isDisposed)
            assertEquals(listOf<Store<*>>(parent, waiterStore), parent.tree.stores())
        }

    @Test
    fun aFailingFactoryRunDisposesWhatItBuiltAndTheNextCallerBuildsItsOwn() =
        completesWithin(30, "getOrCreate after a failed create") {
            val codec = PkCountingKeyCodec()
            val parent = PkParent(codec)
            parent.failingThread = "creator"
            val creatorFailed = AtomicReference<Throwable?>(null)
            daemon("creator") { creatorFailed.set(runCatching { parent.slow.create(3) }.exceptionOrNull()) }.join()
            assertTrue(creatorFailed.get()?.message?.contains("refused") == true, "${creatorFailed.get()}")
            assertNull(parent.slow[3], "a throwing factory leaves no entry")
            val result = AtomicReference<PkStore?>(null)
            daemon("waiter") { result.set(parent.slow.getOrCreate(3)) }.join()
            assertEquals(listOf("creator", "waiter"), parent.runThreads.toList())
            assertSame(result.get(), parent.slow[3])
            assertEquals(2, parent.built.size)
        }

    private companion object {
        /**
         * Sample [thread]'s state after a warm-up: the share of samples in
         * which it was `RUNNABLE` (spinning, or runnable and waiting for a
         * core) rather than parked in `WAITING`/`BLOCKED`. A thread parked on
         * a `ReentrantLock` reads `WAITING` however loaded the machine is.
         */
        fun runnableShare(thread: Thread): Double {
            Thread.sleep(WARM_UP_MS)
            var runnable = 0
            var samples = 0
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAMPLE_MS)
            while (System.nanoTime() < deadline) {
                if (thread.state == Thread.State.RUNNABLE) runnable++
                samples++
                Thread.sleep(SAMPLE_EVERY_MS)
            }
            return runnable.toDouble() / samples
        }

        fun awaitUntil(
            what: String,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!condition()) {
                if (System.nanoTime() > deadline) fail("timed out waiting for $what")
                Thread.sleep(1)
            }
        }
    }
}
