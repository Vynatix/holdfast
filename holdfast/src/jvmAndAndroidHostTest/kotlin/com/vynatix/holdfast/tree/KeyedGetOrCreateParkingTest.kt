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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The branch's factory is declared once and counts its runs, naming the
 * thread of each ([runThreads]); it parks on [gate] when one is set, and
 * fails after constructing its store while [failNext] is set (once).
 */
private class PkParent(
    codec: PkCountingKeyCodec,
) : Store<PkParent>() {
    val runThreads = ConcurrentLinkedQueue<String>()

    @Volatile
    var gate: CountDownLatch? = null
    val failNext = AtomicBoolean(false)

    val slow by stores<Int, PkStore>(keyCodec = codec) { id ->
        runThreads += Thread.currentThread().name
        gate?.await()
        val built = PkStore(id)
        check(!failNext.getAndSet(false)) { "refused after construction" }
        built
    }
}

private const val SAMPLE_MS = 500L
private const val WARM_UP_MS = 100L
private const val SAMPLE_EVERY_MS = 5L
private const val MAX_RUNNABLE_SHARE = 0.2
private const val WAIT_SECONDS = 10L

/**
 * `KeyedBranch.getOrCreate` parks on another thread's construction of the
 * key and never spins: not while the factory runs, and not in the creator's
 * window between reserving the key and starting the factory.
 */
class KeyedGetOrCreateParkingTest {
    @Test
    fun aGetOrCreateParksWhileTheKeyIsReservedButItsFactoryHasNotStarted() =
        completesWithin(30, "getOrCreate against a reserved key") {
            val codec = PkCountingKeyCodec()
            val parent = PkParent(codec)
            val branch = parent.slow
            // The creator's window, held open: the key is reserved on this
            // thread but its factory has not started (`createKeyed` between
            // `reserveOrExisting` and `constructReserved`).
            val (entry, reserved) = branch.registry.reserveOrExisting(branch, 1, branch.leafNameFor(1))
            assertTrue(reserved)
            val encodesBefore = codec.encodes.get()
            val result = AtomicReference<PkStore?>(null)
            val waiter = daemon("waiter") { result.set(branch.getOrCreate(1)) }
            val share = runnableShare(waiter)
            assertNull(result.get(), "the waiter returned before the key was constructed")
            assertNull(branch[1], "a reserved key has no live store")
            assertTrue(
                share < MAX_RUNNABLE_SHARE,
                "the waiting getOrCreate was runnable in ${(share * 100).toInt()}% of samples: it spins on the " +
                    "registry instead of parking on the construction lock",
            )
            val created = branch.constructReserved(entry, 1)
            waiter.join()
            assertSame(created, result.get(), "the waiter gets the creator's instance")
            assertEquals(
                listOf(Thread.currentThread().name),
                parent.runThreads.toList(),
                "the declared factory ran once, for the creator; never for the waiter",
            )
            assertEquals(
                1,
                codec.encodes.get() - encodesBefore,
                "the waiter's getOrCreate names the key once, not once per wake-up",
            )
        }

    @Test
    fun aGetOrCreateParksOnTheCreatorsFactoryAndSharesItsInstance() =
        completesWithin(30, "getOrCreate against a running factory") {
            val codec = PkCountingKeyCodec()
            val parent = PkParent(codec)
            val gate = CountDownLatch(1)
            parent.gate = gate
            val created = AtomicReference<PkStore?>(null)
            val creator = daemon("creator") { created.set(parent.slow.create(2)) }
            awaitUntil("the creator entering its factory") { parent.runThreads.size == 1 }
            val encodesBefore = codec.encodes.get()
            val result = AtomicReference<PkStore?>(null)
            val waiter = daemon("waiter") { result.set(parent.slow.getOrCreate(2)) }
            val share = runnableShare(waiter)
            assertNull(result.get(), "the waiter returned while the factory was still parked")
            assertTrue(share < MAX_RUNNABLE_SHARE, "runnable in ${(share * 100).toInt()}% of samples")
            gate.countDown()
            creator.join()
            waiter.join()
            assertSame(created.get(), result.get())
            assertEquals(listOf("creator"), parent.runThreads.toList(), "the factory ran once, for the creator")
            assertEquals(1, codec.encodes.get() - encodesBefore, "named once for the whole wait")
        }

    @Test
    fun aGetOrCreateConstructsTheKeyItselfWhenTheCreatorsFactoryFails() =
        completesWithin(30, "getOrCreate after a failed create") {
            val codec = PkCountingKeyCodec()
            val parent = PkParent(codec)
            val gate = CountDownLatch(1)
            parent.gate = gate
            parent.failNext.set(true)
            val creatorFailed = AtomicReference<Throwable?>(null)
            val creator = daemon("creator") { creatorFailed.set(runCatching { parent.slow.create(3) }.exceptionOrNull()) }
            awaitUntil("the creator entering its factory") { parent.runThreads.size == 1 }
            val result = AtomicReference<PkStore?>(null)
            val waiter = daemon("waiter") { result.set(parent.slow.getOrCreate(3)) }
            val share = runnableShare(waiter)
            assertNull(result.get())
            assertTrue(share < MAX_RUNNABLE_SHARE, "runnable in ${(share * 100).toInt()}% of samples")
            gate.countDown()
            creator.join()
            waiter.join()
            assertTrue(creatorFailed.get()?.message?.contains("refused") == true, "${creatorFailed.get()}")
            assertEquals(
                listOf("creator", "waiter"),
                parent.runThreads.toList(),
                "the abandoned reservation releases its lock; the waiter runs the factory itself",
            )
            assertSame(result.get(), parent.slow[3])
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
