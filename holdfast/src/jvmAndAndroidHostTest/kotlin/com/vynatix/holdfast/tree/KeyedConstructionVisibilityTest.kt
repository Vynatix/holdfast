@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class VisSlowStore(
    val id: Int,
    root: VisRoot,
    gate: CountDownLatch?,
) : Store<VisSlowStore>(root.slow.at(id)) {
    val n by state { 0 }
    val doubled = derived(n) { n.value * 2 }
    val complete: Boolean

    init {
        action { n mutate 1 }
        n effect { }
        middlewares()
        gate?.await()
        complete = true
    }
}

private class VisRoot : Root() {
    val slow by keyed<Int, VisSlowStore>()
}

private const val WORKERS = 8
private const val LOOKUPS = 2_000

/** T3 under races: a keyed store is never visible half-constructed, and one key admits one store. */
class KeyedConstructionVisibilityTest {
    @Test
    fun aRacingLookupNeverObservesAHalfConstructedStore() =
        completesWithin(20, "racing lookups against a slow factory") {
            val root = VisRoot()
            val gate = CountDownLatch(1)
            val creator = daemon("creator") { root.slow.create(1) { VisSlowStore(it, root, gate) } }
            val failures = ArrayList<Throwable>()
            val lookers =
                List(WORKERS) { w ->
                    daemon("looker-$w", failures) {
                        repeat(LOOKUPS) {
                            val found = root[root.slow, 1]
                            if (found != null) check(found.complete) { "lookup returned a store still inside its constructor" }
                            root.entries(root.slow).values.forEach { check(it.complete) }
                            root.children(root).forEach { check((it as VisSlowStore).complete) }
                        }
                    }
                }
            Thread.sleep(50)
            assertNull(root[root.slow, 1], "the store must be invisible while its factory is parked")
            gate.countDown()
            creator.join()
            lookers.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertTrue(root[root.slow, 1]!!.complete)
        }

    @Test
    fun aThrowingFactoryIsVisibleToNoThread() =
        completesWithin(20, "a throwing factory racing lookups") {
            val root = VisRoot()
            val gate = CountDownLatch(1)
            val seen = AtomicInteger()
            val looker = daemon("looker") { repeat(LOOKUPS) { if (root[root.slow, 2] != null) seen.incrementAndGet() } }
            val creator =
                daemon("creator") {
                    runCatching {
                        root.slow.create(2) { id ->
                            VisSlowStore(id, root, gate)
                            error("after construction")
                        }
                    }
                }
            gate.countDown()
            creator.join()
            looker.join()
            assertEquals(0, seen.get())
            assertNull(root[root.slow, 2])
        }

    @Test
    fun concurrentCreateOfOneKeyAdmitsExactlyOne() =
        completesWithin(20, "concurrent create of one key") {
            val root = VisRoot()
            val barrier = CyclicBarrier(WORKERS)
            val successes = AtomicInteger()
            val duplicates = AtomicInteger()
            val workers =
                List(WORKERS) { w ->
                    daemon("create-$w") {
                        barrier.await()
                        try {
                            root.slow.create(3) { VisSlowStore(it, root, null) }
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
            assertTrue(root[root.slow, 3]!!.complete)
        }

    @Test
    fun concurrentGetOrCreateRunsTheFactoryOnce() =
        completesWithin(20, "concurrent getOrCreate") {
            val root = VisRoot()
            val barrier = CyclicBarrier(WORKERS)
            val runs = AtomicInteger()
            val results =
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet<VisSlowStore>()
            val workers =
                List(WORKERS) { w ->
                    daemon("get-or-create-$w") {
                        barrier.await()
                        results +=
                            root.slow.getOrCreate(4) {
                                runs.incrementAndGet()
                                VisSlowStore(it, root, null)
                            }
                    }
                }
            workers.forEach { it.join() }
            assertEquals(1, runs.get())
            assertEquals(1, results.size)
            assertSame(results.single(), root[root.slow, 4])
        }

    @Test
    fun rootDisposeRacingLeafDisposeNeverDeadlocksAndDetachesOnce() =
        completesWithin(20, "root dispose racing leaf dispose") {
            repeat(50) { round ->
                val root = VisRoot()
                val detached = AtomicInteger()
                root.internalAddMembershipListener(
                    object : LeafMembershipListener() {
                        override fun onDetached(leaf: LeafNode) {
                            detached.incrementAndGet()
                        }
                    },
                )
                val store = root.slow.create(round) { VisSlowStore(it, root, null) }
                val barrier = CyclicBarrier(2)
                val a =
                    daemon("root-dispose") {
                        barrier.await()
                        root.dispose()
                    }
                val b =
                    daemon("leaf-dispose") {
                        barrier.await()
                        store.dispose()
                    }
                a.join()
                b.join()
                assertTrue(detached.get() <= 1, "detached ${detached.get()} times")
                assertTrue(root.isDisposed && store.isDisposed)
            }
        }

    @Test
    fun mintMarkersAreThreadConfined() =
        completesWithin(20, "a factory constructing on another thread") {
            val root = VisRoot()
            val error =
                assertFailsWith<IllegalStateException> {
                    root.slow.create(5) { id ->
                        var built: VisSlowStore? = null
                        var thrown: Throwable? = null
                        val t = daemon("other-thread") { runCatching { built = VisSlowStore(id, root, null) }.onFailure { thrown = it } }
                        t.join()
                        thrown?.let { throw it }
                        built!!
                    }
                }
            assertTrue("create" in error.message!!, error.message)
            assertNull(root[root.slow, 5])
        }
}
