@file:OptIn(StoreInternalApi::class, DelicateCoroutinesApi::class)

package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext
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
 * it reaches the ring too (`OuterMiddlewareRingSuspendTest`).
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

/** The ids of the transactions a middleware started and completed; guarded, since the racing workers' actions write it. */
private class RingTransactionLog {
    private val lock = StoreLock()
    private val started = HashSet<String>()
    private val completed = HashSet<String>()

    fun started(id: String) {
        lock.withLock { started += id }
    }

    fun completed(id: String) {
        lock.withLock { completed += id }
    }

    /** Whether the middleware observed transaction [id] from `started` through `completed`. */
    fun observed(id: String): Boolean = lock.withLock { id in started && id in completed }
}

/** A consumer-list member (`middlewares(...)`): the chain lists every one of these before any ring member. */
private open class RingConsumerMember(
    val log: RingTransactionLog = RingTransactionLog(),
) : Middleware<RingTestVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<RingTestVault>) = log.started(context.transaction.id)

    override fun onTransactionCompleted(context: MiddlewareContext<RingTestVault>) = log.completed(context.transaction.id)
}

/** One member of a whole-set ring install: every ring member one snapshot lists shares a [generation]. */
private class RingMember(
    val generation: Int,
) : Middleware<RingTestVault>()

/** The ring member every whole-set install keeps, so it must observe every transaction of the run. */
private class RingSentinelMember(
    val log: RingTransactionLog = RingTransactionLog(),
) : Middleware<RingTestVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<RingTestVault>) = log.started(context.transaction.id)

    override fun onTransactionCompleted(context: MiddlewareContext<RingTestVault>) = log.completed(context.transaction.id)
}

/** A guarded list the racing workers append to: the invariant violations they found, the transaction ids they ran. */
private class RingGuardedList {
    private val lock = StoreLock()
    private val items = ArrayList<String>()

    operator fun plusAssign(item: String) {
        lock.withLock { items += item }
    }

    fun toList(): List<String> = lock.withLock { items.toList() }
}

private const val RING_SIZE = 3
private const val OPS_PER_WORKER = 200

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

    /**
     * Whole-set ring installs, consumer installs, `clearMiddleware()`,
     * identity removals and actions race on one store, and every
     * [Store.snapshotMiddleware] taken meanwhile — by an observer, by the
     * mutating workers after each step, and from inside every action — must
     * be a consistent chain: the consumer list first, then the ring; the ring
     * either empty or exactly one whole install (its sentinel plus
     * [RING_SIZE] members of one generation), never a partial set or two
     * generations mixed. Installed middleware is seen by actions: the
     * sentinel every install keeps observes every transaction of the run,
     * a consumer member still installed after an action observed it, and a
     * removed member is gone from the chain as soon as the removal returns.
     */
    @Test
    fun concurrentSetRemoveAndClearDuringInFlightActionsKeepEverySnapshotConsistent() =
        runBlocking {
            val v = RingTestVault()
            val sentinel = RingSentinelMember()
            val generations = atomic(0)

            fun wholeInstall(): List<Middleware<RingTestVault>> {
                val generation = generations.incrementAndGet()
                return listOf(sentinel) + List(RING_SIZE) { RingMember(generation) }
            }
            v.internalSetOuterMiddleware(wholeInstall())
            val violations = RingGuardedList()
            val transactions = RingGuardedList()

            fun verify(where: String) {
                val chain = v.snapshotMiddleware()
                val firstRing = chain.indexOfFirst { it !is RingConsumerMember }.let { if (it < 0) chain.size else it }
                val consumers = chain.subList(0, firstRing)
                val ring = chain.subList(firstRing, chain.size)
                if (ring.any { it is RingConsumerMember }) {
                    violations += "$where: a consumer middleware after a ring member in $chain"
                }
                if (consumers.any { it !is RingConsumerMember }) {
                    violations += "$where: a ring member among the consumer list in $chain"
                }
                val ringGenerations = ring.filterIsInstance<RingMember>().map { it.generation }.toSet()
                val isWholeInstall =
                    ring.firstOrNull() === sentinel && ring.size == RING_SIZE + 1 && ringGenerations.size == 1
                if (!isWholeInstall) {
                    violations += "$where: ring is not one whole install (sentinel + $RING_SIZE members of one generation): $ring"
                }
            }

            val ringSetter: suspend (Int) -> Unit = { w ->
                repeat(OPS_PER_WORKER) {
                    v.internalSetOuterMiddleware(wholeInstall())
                    verify("setter $w after set")
                }
            }
            val consumerAdder: suspend (Int) -> Unit = { w ->
                repeat(OPS_PER_WORKER) {
                    v.middlewares(RingConsumerMember())
                    verify("adder $w after middlewares")
                }
            }
            val clearer: suspend (Int) -> Unit = { w ->
                repeat(OPS_PER_WORKER) {
                    v.clearMiddleware()
                    verify("clearer $w after clearMiddleware")
                }
            }
            val remover: suspend (Int) -> Unit = { w ->
                repeat(OPS_PER_WORKER) {
                    val member = RingConsumerMember()
                    v.middlewares(member)
                    // `true` unless a racing clearMiddleware() wiped it first; either way it is gone afterwards.
                    v.internalRemoveMiddleware(member)
                    if (v.snapshotMiddleware().any { it === member }) violations += "remover $w: a removed member is still in the chain"
                    verify("remover $w after remove")
                }
            }
            val actor: suspend (Int) -> Unit = { w ->
                repeat(OPS_PER_WORKER) { i ->
                    val member = RingConsumerMember()
                    v.middlewares(member)
                    val result =
                        v action {
                            verify("actor $w inside action $i")
                            n mutate i
                        }
                    val txn = (result as TransactionResult.Success).transaction
                    transactions += txn.id
                    val stillInstalled = v.snapshotMiddleware().any { it === member }
                    if (stillInstalled && !member.log.observed(txn.id)) {
                        violations += "actor $w: a consumer member installed throughout action $i did not observe it"
                    }
                }
            }
            val observer: suspend (Int) -> Unit = { w ->
                repeat(OPS_PER_WORKER * 4) { verify("observer $w") }
            }

            val roles = listOf(actor, ringSetter, observer, clearer, actor, ringSetter, consumerAdder, remover)
            // One thread per role, so every role overlaps every other whatever the host's core count.
            val pool = newFixedThreadPoolContext(roles.size, "ring-race")
            try {
                roles
                    .mapIndexed { w, role -> async(pool) { role(w) } }
                    .awaitAll()
            } finally {
                pool.close()
            }

            assertEquals(emptyList(), violations.toList().take(5))
            val ran = transactions.toList()
            assertEquals(2 * OPS_PER_WORKER, ran.size, "every action ran")
            val unseen = ran.filterNot { sentinel.log.observed(it) }
            assertEquals(emptyList(), unseen.take(5), "the sentinel ring member observes every transaction of the run")

            // The store is still usable afterwards: a fresh chain runs an action to completion.
            v.clearMiddleware()
            v.internalSetOuterMiddleware(emptyList())
            val finalEvents = mutableListOf<String>()
            v.middlewares(RingRecordingMiddleware("FINAL", finalEvents))
            v action { n mutate 999 }
            assertEquals(listOf("FINAL:started", "FINAL:completed"), finalEvents)
            assertEquals(999, v.n.value)
        }
}
