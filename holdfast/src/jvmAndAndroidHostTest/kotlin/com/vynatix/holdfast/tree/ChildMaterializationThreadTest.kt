@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Both threads must be inside their lambdas before either reads on; a retry passes straight through. */
private fun CountDownLatch.meet() {
    countDown()
    check(await(10, TimeUnit.SECONDS)) { "the other lambda never started" }
}

private class CmtLeafStore : Store<CmtLeafStore>() {
    val n by state { 0 }
}

/** A child whose lambda parks on [gate] (after building its store) until every reader is inside it. */
private class CmtSlowParent : Store<CmtSlowParent>() {
    val runs = AtomicInteger()
    val built = ConcurrentLinkedQueue<CmtLeafStore>()
    val gate = CountDownLatch(1)
    val child by store {
        runs.incrementAndGet()
        val store = CmtLeafStore().also { built += it }
        check(gate.await(10, TimeUnit.SECONDS)) { "the gate never opened" }
        store
    }
}

/** `first`'s lambda needs `second` and `second`'s needs `first`: a genuine cycle when two threads start one each. */
private class CmtCycleParent : Store<CmtCycleParent>() {
    val bothInside = CountDownLatch(2)
    val first: CmtLeafStore by store {
        bothInside.meet()
        second
        CmtLeafStore()
    }
    val second: CmtLeafStore by store {
        bothInside.meet()
        first
        CmtLeafStore()
    }
}

/**
 * The reviewer's shapes: a `store { }` child and a keyed branch whose
 * factory and lambda need each other, and a lambda/factory that opens an
 * action on the parent while another thread reads the child (or creates
 * the key) from inside one.
 */
private class CmtMixedParent : Store<CmtMixedParent>() {
    val counter by state { 0 }

    /** Set per test: what the child lambda does before building its store. */
    @Volatile
    var childBody: () -> Unit = {}

    /** Set per test: what the keyed factory does before building its store. */
    @Volatile
    var factoryBody: (String) -> Unit = {}

    val builtChildren = ConcurrentLinkedQueue<CmtLeafStore>()
    val builtKeyed = ConcurrentLinkedQueue<CmtKeyedStore>()

    val child: CmtLeafStore by store {
        childBody()
        CmtLeafStore().also { builtChildren += it }
    }

    val threads by keyed<String, CmtKeyedStore> { key ->
        factoryBody(key)
        CmtKeyedStore().also { builtKeyed += it }
    }
}

private class CmtKeyedStore : Store<CmtKeyedStore>() {
    val n by state { 0 }
}

/**
 * Child materialization and keyed creation under real threads: the child
 * lambda and the keyed factory run holding no lock, so racing readers may
 * each run them (one result wins, the losers' stores are disposed), and no
 * shape of mutual need across threads — child against child, child
 * against keyed factory, either against a store's `transactionLock` — can
 * hang: a same-thread re-entry throws a cycle error, everything else
 * completes.
 */
class ChildMaterializationThreadTest {
    @Test
    fun concurrentFirstReadsGetTheOneChildThatAttachedFirstAndTheLosersStoresAreDisposed() =
        completesWithin(20, "concurrent first reads of a child") {
            val parent = CmtSlowParent()
            val announced = AtomicInteger()
            parent.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onAttached(leaf: LeafNode) {
                        announced.incrementAndGet()
                    }
                },
            )
            val readers = 8
            val barrier = CyclicBarrier(readers)
            val results = ConcurrentHashMap.newKeySet<CmtLeafStore>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val threads =
                List(readers) { i ->
                    daemon("reader-$i", failures) {
                        barrier.await()
                        results += parent.child
                    }
                }
            // Nothing serializes the lambda: every reader is inside it at once.
            awaitUntil("every reader inside the lambda") { parent.runs.get() == readers }
            parent.gate.countDown()
            threads.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertEquals(1, results.size, "every reader got the same child")
            val winner = results.single()
            assertFalse(winner.isDisposed)
            val losers = parent.built.filter { it !== winner }
            assertEquals(readers - 1, losers.size)
            assertTrue(losers.all { it.isDisposed }, "every losing run's store is disposed")
            assertTrue(losers.none { it.hasTreeMembership() }, "no losing store was attached")
            assertEquals(1, announced.get(), "only the winner was announced")
            assertEquals(listOf(parent, winner), parent.tree.stores())
        }

    @Test
    fun aTwoThreadCycleThroughChildDeclarationsThrowsInsteadOfHanging() {
        val parent = CmtCycleParent()
        val failures = ConcurrentLinkedQueue<Throwable>()
        completesWithin(20, "a two-thread child declaration cycle") {
            val t1 = daemon("first") { runCatching { parent.first }.exceptionOrNull()?.let { failures += it } }
            val t2 = daemon("second") { runCatching { parent.second }.exceptionOrNull()?.let { failures += it } }
            t1.join()
            t2.join()
        }
        // No thread waits for another: each runs the other declaration's lambda itself and meets
        // its own declaration again on its own thread.
        assertEquals(2, failures.size, "both reads fail: no evaluation order can finish a cycle: $failures")
        assertTrue(
            failures.all { it is IllegalStateException && it.message.orEmpty().startsWith("Materialization cycle") },
            "$failures",
        )
        assertTrue(
            failures.all {
                val message = it.message.orEmpty()
                "child declaration 'CmtCycleParent.first'" in message && "child declaration 'CmtCycleParent.second'" in message
            },
            "each report names both declarations: $failures",
        )
        val registry = parent.treeAttachment().registry
        assertTrue(registry.liveChildNodes().isEmpty(), "nothing was published")
    }

    @Test
    fun aTwoThreadCycleThroughAChildAndAKeyedFactoryThrowsInsteadOfHanging() {
        val parent = CmtMixedParent()
        val bothInside = CountDownLatch(2)
        parent.factoryBody = {
            bothInside.meet()
            parent.child
        }
        parent.childBody = {
            bothInside.meet()
            parent.threads.getOrCreate("a")
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        completesWithin(20, "a child lambda and a keyed factory needing each other on two threads") {
            val t1 = daemon("keyed") { runCatching { parent.threads.getOrCreate("a") }.exceptionOrNull()?.let { failures += it } }
            val t2 = daemon("child") { runCatching { parent.child }.exceptionOrNull()?.let { failures += it } }
            t1.join()
            t2.join()
        }
        assertEquals(2, failures.size, "both fail: $failures")
        assertTrue(
            failures.all {
                val message = it.message.orEmpty()
                it is IllegalStateException &&
                    message.startsWith("Materialization cycle") &&
                    "child declaration 'CmtMixedParent.child'" in message &&
                    "keyed factory 'CmtMixedParent.threads'" in message
            },
            "$failures",
        )
        assertEquals(emptyMap(), parent.threads.entries())
        assertTrue(
            parent
                .treeAttachment()
                .registry
                .liveChildNodes()
                .none { it is LeafNode },
            "no child attached",
        )
        assertTrue(parent.builtChildren.isEmpty() && parent.builtKeyed.isEmpty(), "no run got as far as building")
    }

    @Test
    fun twoKeyedFactoriesNeedingEachOthersKeyThrowInsteadOfHanging() {
        val parent = CmtMixedParent()
        val bothInside = CountDownLatch(2)
        parent.factoryBody = { key ->
            bothInside.meet()
            parent.threads.getOrCreate(if (key == "a") "b" else "a")
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        completesWithin(20, "two keyed factories needing each other's key") {
            val t1 = daemon("a") { runCatching { parent.threads.getOrCreate("a") }.exceptionOrNull()?.let { failures += it } }
            val t2 = daemon("b") { runCatching { parent.threads.getOrCreate("b") }.exceptionOrNull()?.let { failures += it } }
            t1.join()
            t2.join()
        }
        assertEquals(2, failures.size, "both fail: $failures")
        assertTrue(failures.all { it.message.orEmpty().startsWith("Materialization cycle") }, "$failures")
        assertEquals(emptyMap(), parent.threads.entries())
    }

    @Test
    fun aChildLambdaOpeningAParentActionNeverDeadlocksAReaderInsideOne() {
        val parent = CmtMixedParent()
        val lambdaInside = CountDownLatch(1)
        val readerHoldsTheLock = CountDownLatch(1)
        parent.childBody = {
            if (Thread.currentThread().name == "first-reader") {
                lambdaInside.countDown()
                check(readerHoldsTheLock.await(10, TimeUnit.SECONDS)) { "the action reader never started" }
            }
            parent action { counter mutate counter.value + 1 }
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val firstGot = AtomicReference<CmtLeafStore?>(null)
        val actionGot = AtomicReference<CmtLeafStore?>(null)
        completesWithin(20, "a child lambda opening a parent action against a reader inside one") {
            val first = daemon("first-reader", failures) { firstGot.set(parent.child) }
            check(lambdaInside.await(10, TimeUnit.SECONDS))
            val inAction =
                daemon("action-reader", failures) {
                    parent action {
                        readerHoldsTheLock.countDown()
                        actionGot.set(parent.child)
                    }
                }
            first.join()
            inAction.join()
        }
        assertTrue(failures.isEmpty(), failures.joinToString())
        assertSame(firstGot.get(), actionGot.get(), "both readers got the one child")
        assertSame(actionGot.get(), parent.child)
        assertEquals(2, parent.counter.value, "both lambda runs opened their action")
        val losers = parent.builtChildren.filter { it !== parent.child }
        assertTrue(losers.all { it.isDisposed }, "the losing run's store is disposed: $losers")
    }

    @Test
    fun aKeyedFactoryOpeningAParentActionNeverDeadlocksACreatorInsideOne() {
        val parent = CmtMixedParent()
        val factoryInside = CountDownLatch(1)
        val creatorHoldsTheLock = CountDownLatch(1)
        parent.factoryBody = {
            if (Thread.currentThread().name == "first-creator") {
                factoryInside.countDown()
                check(creatorHoldsTheLock.await(10, TimeUnit.SECONDS)) { "the action creator never started" }
            }
            parent action { counter mutate counter.value + 1 }
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val firstGot = AtomicReference<CmtKeyedStore?>(null)
        val actionGot = AtomicReference<CmtKeyedStore?>(null)
        completesWithin(20, "a keyed factory opening a parent action against a creator inside one") {
            val first = daemon("first-creator", failures) { firstGot.set(parent.threads.getOrCreate("k")) }
            check(factoryInside.await(10, TimeUnit.SECONDS))
            val inAction =
                daemon("action-creator", failures) {
                    parent action {
                        creatorHoldsTheLock.countDown()
                        actionGot.set(parent.threads.getOrCreate("k"))
                    }
                }
            first.join()
            inAction.join()
        }
        assertTrue(failures.isEmpty(), failures.joinToString())
        assertSame(firstGot.get(), actionGot.get(), "both creators got the one store")
        assertSame(actionGot.get(), parent.threads["k"])
        val losers = parent.builtKeyed.filter { it !== parent.threads["k"] }
        assertEquals(1, losers.size)
        assertTrue(losers.single().isDisposed, "the losing run's store is disposed")
    }
}
