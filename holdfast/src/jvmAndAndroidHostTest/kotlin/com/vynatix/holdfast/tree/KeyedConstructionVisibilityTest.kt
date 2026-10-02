@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A keyed store whose constructor parks on [gate] (when given) and then runs
 * [tail] (when given) before its last field, [complete], is assigned: a
 * lookup that returns this store with `complete == false` observed it
 * half-constructed.
 */
private class VisSlowStore(
    val id: Int,
    gate: CountDownLatch?,
    tail: (() -> Unit)? = null,
) : Store<VisSlowStore>() {
    val n by state { 0 }
    val doubled = derived(n) { n.value * 2 }
    val complete: Boolean

    init {
        action { n mutate 1 }
        n effect { }
        middlewares()
        gate?.await()
        tail?.invoke()
        complete = true
    }
}

/**
 * The branch's factory is declared once; a test that needs a key built
 * differently registers a per-key [builds] entry the factory runs instead.
 */
private class VisParent : Store<VisParent>() {
    val builds = ConcurrentHashMap<Int, (Int) -> VisSlowStore>()
    val slow by stores<Int, VisSlowStore> { id -> builds[id]?.invoke(id) ?: VisSlowStore(id, null) }
}

private const val WORKERS = 8

/**
 * The least number of lookups that must have overlapped the window under
 * test (the constructor's tail, or a built-but-abandoned store) before the
 * factory is allowed to finish: the factory waits for them, so the overlap
 * is a fact of the run, not of the scheduler.
 */
private const val MIN_OVERLAP = 500

/** T3 under races: a keyed store is never visible half-constructed, and one key admits one store. */
class KeyedConstructionVisibilityTest {
    @Test
    fun aRacingLookupNeverObservesAHalfConstructedStore() =
        completesWithin(20, "racing lookups against a slow factory") {
            val parent = VisParent()
            val gate = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            // Set by the constructor once past the gate, while it is still running.
            val inTail = AtomicBoolean(false)
            // Lookups that began while the constructor was in its tail.
            val tailLookups = AtomicInteger()
            val creatorDone = AtomicBoolean(false)
            val seenLive = AtomicInteger()
            parent.builds[1] = { id ->
                VisSlowStore(id, gate) {
                    // Hold the constructor open until the lookers have overlapped it.
                    inTail.set(true)
                    while (tailLookups.get() < MIN_OVERLAP && failures.isEmpty()) Thread.onSpinWait()
                }
            }
            val creator =
                daemon("creator", failures) {
                    try {
                        parent.slow.create(1)
                    } finally {
                        creatorDone.set(true)
                    }
                }
            val lookers =
                List(WORKERS) { w ->
                    daemon("looker-$w", failures) {
                        var sawLive = false
                        // Run until the creator has returned AND this looker saw the live store, so the
                        // lookups span the constructor's tail, the promotion and the first live reads.
                        while ((!creatorDone.get() || !sawLive) && failures.isEmpty()) {
                            val tail = inTail.get()
                            val found = parent.slow[1]
                            if (found != null) {
                                check(found.complete) { "lookup returned a store still inside its constructor" }
                                if (!sawLive) {
                                    sawLive = true
                                    seenLive.incrementAndGet()
                                }
                            }
                            for (live in parent.slow.entries.values) check(live.complete)
                            parent.tree.stores(parent.slow).forEach { check((it as VisSlowStore).complete) }
                            if (tail) tailLookups.incrementAndGet()
                        }
                    }
                }
            Thread.sleep(50)
            assertNull(parent.slow[1], "the store must be invisible while its factory is parked")
            gate.countDown()
            creator.join()
            lookers.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertTrue(tailLookups.get() >= MIN_OVERLAP, "lookups overlapping the constructor's tail: ${tailLookups.get()}")
            assertEquals(WORKERS, seenLive.get(), "every looker saw the store once it was live")
            assertTrue(parent.slow[1]!!.complete)
        }

    @Test
    fun aThrowingFactoryIsVisibleToNoThread() =
        completesWithin(20, "a throwing factory racing lookups") {
            val parent = VisParent()
            val failures = ConcurrentLinkedQueue<Throwable>()
            // Set by the factory once the store is fully constructed, before it throws.
            val constructed = AtomicBoolean(false)
            // Lookups that began while the built store was waiting to be abandoned.
            val overlapping = AtomicInteger()
            val creatorDone = AtomicBoolean(false)
            val seen = AtomicInteger()
            val looker =
                daemon("looker", failures) {
                    while (!creatorDone.get()) {
                        val built = constructed.get()
                        if (parent.slow[2] != null) seen.incrementAndGet()
                        if (built) overlapping.incrementAndGet()
                    }
                }
            parent.builds[2] = { id ->
                VisSlowStore(id, null)
                constructed.set(true)
                // Hold the built store unpromoted until the looker has overlapped it.
                while (overlapping.get() < MIN_OVERLAP && failures.isEmpty()) Thread.onSpinWait()
                error("after construction")
            }
            val creator =
                daemon("creator", failures) {
                    try {
                        val thrown = assertFailsWith<IllegalStateException> { parent.slow.create(2) }
                        check(thrown.message == "after construction") { "create must propagate the factory's own throw: $thrown" }
                    } finally {
                        creatorDone.set(true)
                    }
                }
            creator.join()
            looker.join()
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertTrue(overlapping.get() >= MIN_OVERLAP, "lookups overlapping the built, unpromoted store: ${overlapping.get()}")
            assertEquals(0, seen.get(), "a store its factory abandoned was visible to a lookup")
            assertNull(parent.slow[2])
        }

    @Test
    fun concurrentCreateOfOneKeyAdmitsExactlyOne() =
        completesWithin(20, "concurrent create of one key") {
            val parent = VisParent()
            val barrier = CyclicBarrier(WORKERS)
            val successes = AtomicInteger()
            val duplicates = AtomicInteger()
            val workers =
                List(WORKERS) { w ->
                    daemon("create-$w") {
                        barrier.await()
                        try {
                            parent.slow.create(3)
                            successes.incrementAndGet()
                        } catch (e: IllegalStateException) {
                            check("already exists" in e.message!!) { e.message!! }
                            duplicates.incrementAndGet()
                        }
                    }
                }
            workers.forEach { it.join() }
            assertEquals(1, successes.get())
            assertEquals(WORKERS - 1, duplicates.get())
            assertTrue(parent.slow[3]!!.complete)
        }

    @Test
    fun concurrentGetOrCreateReturnsTheOneWinnerAndDisposesTheLosers() =
        completesWithin(20, "concurrent getOrCreate") {
            val parent = VisParent()
            val barrier = CyclicBarrier(WORKERS)
            val runs = AtomicInteger()
            val built = ConcurrentLinkedQueue<VisSlowStore>()
            parent.builds[4] = { id ->
                runs.incrementAndGet()
                VisSlowStore(id, null).also { built += it }
            }
            val announced = AtomicInteger()
            parent.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onAttached(leaf: LeafNode) {
                        announced.incrementAndGet()
                    }
                },
            )
            val results =
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet<VisSlowStore>()
            val workers =
                List(WORKERS) { w ->
                    daemon("get-or-create-$w") {
                        barrier.await()
                        results += parent.slow.getOrCreate(4)
                    }
                }
            workers.forEach { it.join() }
            // The factory runs holding no lock, so racing callers may each run it; exactly one store wins.
            assertTrue(runs.get() in 1..WORKERS, "factory runs: ${runs.get()}")
            assertEquals(1, results.size, "every caller got the same store")
            val winner = results.single()
            assertSame(winner, parent.slow[4])
            assertFalse(winner.isDisposed)
            val losers = built.filter { it !== winner }
            assertEquals(runs.get() - 1, losers.size)
            assertTrue(losers.all { it.isDisposed }, "every losing run's store is disposed")
            assertTrue(losers.none { parent.tree.nodeOf(it) != null }, "no losing store was attached")
            assertEquals(listOf<Store<*>>(parent, winner), parent.tree.stores())
            assertEquals(1, announced.get(), "only the winner was announced")
        }

    @Test
    fun parentDisposeRacingChildDisposeNeverDeadlocksAndDetachesOnce() =
        completesWithin(20, "parent dispose racing child dispose") {
            repeat(50) { round ->
                val parent = VisParent()
                val detached = AtomicInteger()
                parent.internalAddMembershipListener(
                    object : LeafMembershipListener() {
                        override fun onDetached(leaf: LeafNode) {
                            detached.incrementAndGet()
                        }
                    },
                )
                val store = parent.slow.create(round)
                val barrier = CyclicBarrier(2)
                val a =
                    daemon("parent-dispose") {
                        barrier.await()
                        parent.dispose()
                    }
                val b =
                    daemon("child-dispose") {
                        barrier.await()
                        store.dispose()
                    }
                a.join()
                b.join()
                assertTrue(detached.get() <= 1, "detached ${detached.get()} times")
                assertTrue(parent.isDisposed && store.isDisposed)
            }
        }

    @Test
    fun aLookupOnAnotherThreadParksUntilTheAttachHasToldTheListenersAndSyncedTheRing() =
        completesWithin(30, "lookups parking on an attach still being announced") {
            val parent = VisParent()
            val trace = object : TreeMiddleware() {}
            parent.tree.middlewares(trace)
            val announcing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val told = AtomicBoolean(false)
            parent.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onAttached(leaf: LeafNode) {
                        // Test-only stall of phase 6, standing in for a slow listener on a busy machine.
                        announcing.countDown()
                        release.await()
                        told.set(true)
                    }
                },
            )
            val failures = ConcurrentLinkedQueue<Throwable>()
            val creator = daemon("creator", failures) { parent.slow.create(1) }
            announcing.await()
            // Registered (phase 4) and ring-synced (phase 5), still announcing (phase 6).
            val seen = ConcurrentLinkedQueue<String>()
            val lookups =
                listOf<Pair<String, () -> VisSlowStore?>>(
                    "get" to { parent.slow[1] },
                    "entries" to { parent.slow.entries[1] },
                    "getOrCreate" to { parent.slow.getOrCreate(1) },
                ).map { (name, lookup) ->
                    daemon(name, failures) {
                        val store = lookup()
                        seen += "$name told=${told.get()} ring=${store?.treeRingAdapters()?.map { it.middleware }}"
                    }
                }
            Thread.sleep(PARK_PROBE_MILLIS)
            assertEquals(emptyList<String>(), seen.toList(), "no lookup answered while the attach was announced")
            release.countDown()
            creator.join()
            lookups.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertEquals(
                listOf("entries", "get", "getOrCreate").map { "$it told=true ring=[$trace]" },
                seen.toList().sorted(),
            )
        }
}

private const val PARK_PROBE_MILLIS = 200L
