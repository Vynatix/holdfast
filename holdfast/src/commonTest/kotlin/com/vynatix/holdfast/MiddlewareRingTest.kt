@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for the outer middleware ring (issue #21 plan, PR 21-1, decision
 * U12): [internalSetOuterMiddleware], [internalRemoveMiddleware] and their
 * effect on [Store.snapshotMiddleware] / the blocking [Store.action] chain.
 * `:holdfast-coroutines`' `SuspendAction`/`SuspendAtomic` route through the
 * same [Store.snapshotMiddleware], so this store-level coverage is the one
 * place the ordering contract needs proving; the coroutines module pins that
 * it reaches the ring too (`RingOutermostOnSuspendActionTest`).
 */
private class RingTestVault : Store<RingTestVault>() {
    val n by state { 0 }
}

private class RingRecordingMiddleware(
    private val tag: String,
    private val events: MutableList<String>,
) : Middleware<RingTestVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<RingTestVault>) {
        events.add("$tag:started")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<RingTestVault>) {
        events.add("$tag:completed")
    }

    override fun onTransactionError(
        context: MiddlewareContext<RingTestVault>,
        error: Throwable,
    ) {
        events.add("$tag:error")
    }
}

class MiddlewareRingTest {
    @Test
    fun ringIsOutermostRegardlessOfRegistrationOrder() {
        val v = RingTestVault()
        val events = mutableListOf<String>()
        // Consumer middleware registered AFTER the ring is installed; the ring
        // must still wrap it, because installer order never governs the ring.
        v.internalSetOuterMiddleware(listOf(RingRecordingMiddleware("RING", events)))
        v.middlewares(RingRecordingMiddleware("CONSUMER", events))

        v action {
            events.add("BLOCK")
            n mutate 1
        }

        assertEquals(
            listOf("RING:started", "CONSUMER:started", "BLOCK", "CONSUMER:completed", "RING:completed"),
            events,
            "the ring must wrap every consumer middleware, whichever was registered first",
        )
    }

    @Test
    fun ringSurvivesClearMiddleware() {
        val v = RingTestVault()
        val events = mutableListOf<String>()
        v.internalSetOuterMiddleware(listOf(RingRecordingMiddleware("RING", events)))
        v.middlewares(RingRecordingMiddleware("CONSUMER", events))

        v.clearMiddleware()
        v action { n mutate 1 }

        assertTrue(events.any { it.startsWith("RING:") }, "clearMiddleware() must not drop the ring; events=$events")
        assertTrue(events.none { it.startsWith("CONSUMER:") }, "clearMiddleware() must drop the consumer list; events=$events")
    }

    @Test
    fun wholeSetReplaceKeepsTheConsumerListUntouched() {
        val v = RingTestVault()
        val events = mutableListOf<String>()
        v.middlewares(RingRecordingMiddleware("CONSUMER", events))
        v.internalSetOuterMiddleware(listOf(RingRecordingMiddleware("RING-1", events)))
        v.internalSetOuterMiddleware(listOf(RingRecordingMiddleware("RING-2", events)))

        v action { n mutate 1 }

        assertTrue(events.any { it.startsWith("CONSUMER:") })
        assertTrue(events.none { it.startsWith("RING-1:") }, "a later set() must fully replace the ring; events=$events")
        assertTrue(events.any { it.startsWith("RING-2:") })
    }

    @Test
    fun identityRemovalDropsOnlyThatMember() {
        val v = RingTestVault()
        val events = mutableListOf<String>()
        val a = RingRecordingMiddleware("A", events)
        val b = RingRecordingMiddleware("B", events)
        v.internalSetOuterMiddleware(listOf(a, b))

        val removed = v.internalRemoveMiddleware(a)
        v action { n mutate 1 }

        assertTrue(removed)
        assertTrue(events.none { it.startsWith("A:") })
        assertTrue(events.any { it.startsWith("B:") })
    }

    @Test
    fun identityRemovalOfAnAbsentMemberIsANoOp() {
        val v = RingTestVault()
        assertFalse(v.internalRemoveMiddleware(RingRecordingMiddleware("NEVER-INSTALLED", mutableListOf())))
    }

    @Test
    fun setOnADisposedStoreThrows() {
        val v = RingTestVault()
        v.dispose()
        val result = runCatching { v.internalSetOuterMiddleware(emptyList()) }
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun removeOnADisposedStoreReturnsFalseInsteadOfThrowing() {
        val v = RingTestVault()
        val mw = RingRecordingMiddleware("R", mutableListOf())
        v.internalSetOuterMiddleware(listOf(mw))
        v.dispose()

        assertFalse(v.internalRemoveMiddleware(mw), "dispose() already cleared the ring; remove must answer false, not throw")
    }

    @Test
    fun snapshotMiddlewareReturnsConsumerListThenRing() {
        val v = RingTestVault()
        val events = mutableListOf<String>()
        val consumer = RingRecordingMiddleware("CONSUMER", events)
        val ring = RingRecordingMiddleware("RING", events)
        v.middlewares(consumer)
        v.internalSetOuterMiddleware(listOf(ring))

        assertEquals(listOf(consumer, ring), v.snapshotMiddleware())
    }

    @Test
    fun concurrentSetRemoveAndClearDuringInFlightActionsNeverCorruptEitherList() =
        runBlocking {
            val v = RingTestVault()
            val workers = 8
            val opsPerWorker = 50

            val jobs =
                List(workers) { workerId ->
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            when (workerId % 5) {
                                0 -> v.internalSetOuterMiddleware(listOf(RingRecordingMiddleware("w$workerId-$it", mutableListOf())))
                                1 -> v.internalRemoveMiddleware(RingRecordingMiddleware("never-here", mutableListOf()))
                                2 -> v.middlewares(RingRecordingMiddleware("c$workerId-$it", mutableListOf()))
                                3 -> v.clearMiddleware()
                                4 -> v action { n mutate it }
                            }
                        }
                    }
                }
            jobs.awaitAll()

            // The store is still usable: both lists are internally consistent lists
            // (no crash, no torn read), and a fresh action still runs to completion.
            v.clearMiddleware()
            v.internalSetOuterMiddleware(emptyList())
            val finalEvents = mutableListOf<String>()
            v.middlewares(RingRecordingMiddleware("FINAL", finalEvents))
            v action { n mutate 999 }
            assertEquals(listOf("FINAL:started", "FINAL:completed"), finalEvents)
            assertEquals(999, v.n.value)
        }
}
