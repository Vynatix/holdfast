@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class HopFirst : Store<HopFirst>() {
    val x by state { 0 }
}

private class HopSecond : Store<HopSecond>() {
    val y by state { 0 }
}

/**
 * The post-hop tear of `suspendAtomic` is gone (issue #20, R9). Participants
 * used to commit one after the other, each fanning out — observers, awaited
 * bridge publishes, events — before the next one applied. On the frame's owner
 * thread an observer of the first participant still read the second one's
 * pending value, but a frame whose body resumed on another thread commits
 * there, where no pending value is visible: the observer read the second
 * participant's OLD value next to the first one's new one, and every other
 * thread saw that torn pair for as long as the first participant's
 * `publishAwaited` suspended. Now every participant applies, inside one write
 * bracket, before any fans out.
 */
class SuspendAtomicHopDerivationTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    /** Commit [x] and [y] in one frame whose body hops threads first, so it commits off its owner thread. */
    private fun commitAfterAHop(
        first: HopFirst,
        second: HopSecond,
        value: Int,
    ): Thread {
        val opener = Thread.currentThread()
        var committedOn: Thread? = null
        runBlocking {
            withContext(Dispatchers.Unconfined) {
                suspendAtomic(first, second) {
                    first { x mutate value }
                    second { y mutate value }
                    resumeOnAnotherThread()
                    committedOn = Thread.currentThread()
                }.getOrThrow()
            }
        }
        val on = assertNotNull(committedOn)
        assertTrue(on !== opener, "the frame committed on another thread than its owner")
        return on
    }

    @Test fun anObserverOfTheFirstParticipantReadsTheSecondOneAppliedAfterAHop() {
        val first = HopFirst()
        val second = HopSecond()
        val seen = mutableListOf<Pair<Int, Int>>()
        var initial = true
        disposables += first.x effect { if (initial) initial = false else seen += this to second.y.value }

        commitAfterAHop(first, second, 1)

        assertEquals(listOf(1 to 1), seen, "the second participant had applied before the first one fanned out")
    }

    /**
     * A `derivedState` over both participants settles once, after the frame.
     * A legacy `derived` over both, on an idle third store, recomputes inline
     * in the first participant's fanout, on the hop thread, where no pending
     * value is visible: it used to read the second participant's old value
     * there, and now finds it applied — it never holds a torn pair.
     */
    @Test fun derivedStatesOverBothParticipantsNeverHoldATornPairAfterAHop() {
        val first = HopFirst()
        val second = HopSecond()
        val host = HopSecond()
        var computes = 0
        val pair =
            host.derivedState(first.x, second.y) {
                computes++
                first.x.value to second.y.value
            }
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(pair effect { seen += this }, pair)
        val (legacy, subscription) = HopSecond().derived(first.x, second.y) { first.x.value to second.y.value }
        val legacySeen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(legacy effect { synchronized(legacySeen) { legacySeen += this } }, subscription)

        commitAfterAHop(first, second, 2)

        assertEquals(listOf(0 to 0, 2 to 2), seen)
        assertEquals(2, computes, "the initial compute, then one recompute after the frame")
        val legacyValues = synchronized(legacySeen) { legacySeen.toList() }
        assertTrue(2 to 0 !in legacyValues, "the legacy derived held a torn pair: $legacyValues")
        assertEquals(2 to 2, legacyValues.last())
    }

    /**
     * While the first participant's `publishAwaited` suspends, another thread
     * reading the first participant and then the second never finds the first
     * one applied and the second not: both applied before any publish ran.
     */
    @Test fun noThreadSeesOneParticipantAppliedWithoutTheOtherWhileAPublishIsAwaited() {
        val first = HopFirst()
        val second = HopSecond()
        val publishing = CountDownLatch(1)
        first {
            x bridge
                object : SuspendingBridge<Int> {
                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

                    override fun publish(value: Int): Boolean = true

                    override suspend fun publishAwaited(value: Int) {
                        publishing.countDown()
                        delay(100)
                    }
                }
        }
        val torn = ConcurrentLinkedQueue<String>()
        val done = AtomicBoolean(false)
        val reader =
            Thread {
                publishing.await(10, TimeUnit.SECONDS)
                while (!done.get()) {
                    // First, then second: once the first reads applied, the
                    // second must too, or they applied apart.
                    val x = first.x.value
                    val y = second.y.value
                    if (x == 1 && y == 0) torn += "x=$x y=$y"
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            settlesWithin(20, "a suspendAtomic frame with an awaited publish") {
                runBlocking {
                    suspendAtomic(first, second) {
                        first { x mutate 1 }
                        second { y mutate 1 }
                    }.getOrThrow()
                }
            }
        } finally {
            done.set(true)
            reader.join(10_000)
        }
        assertEquals(emptyList(), torn.toList().take(3), "a reader saw the first participant applied alone")
        assertEquals(1, second.y.value)
    }
}
