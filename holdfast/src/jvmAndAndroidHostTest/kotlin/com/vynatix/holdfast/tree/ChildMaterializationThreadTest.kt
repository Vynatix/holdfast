@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.InitializerGraph
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both threads must be inside their lambdas before either reads on; a retry passes straight through. */
private fun CountDownLatch.meet() {
    countDown()
    check(await(10, TimeUnit.SECONDS)) { "the other lambda never started" }
}

private class CmtLeafStore : Store<CmtLeafStore>() {
    val n by state { 0 }
}

/** A child whose lambda parks on [gate] until every reader is waiting on it. */
private class CmtSlowParent : Store<CmtSlowParent>() {
    val runs = AtomicInteger()
    val gate = CountDownLatch(1)
    val child by store {
        runs.incrementAndGet()
        check(gate.await(10, TimeUnit.SECONDS)) { "the gate never opened" }
        CmtLeafStore()
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

/** Child materialization under real threads: one run per declaration, and cycles that throw instead of hanging. */
class ChildMaterializationThreadTest {
    @Test
    fun concurrentFirstReadsRunTheLambdaOnceAndGetTheSameChild() =
        completesWithin(20, "concurrent first reads of a child") {
            val parent = CmtSlowParent()
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
            awaitUntil("one reader inside the lambda") { parent.runs.get() == 1 }
            awaitUntil("the other readers waiting on its latch") { InitializerGraph.Process.waitingCount >= readers - 1 }
            parent.gate.countDown()
            threads.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertEquals(1, parent.runs.get(), "the lambda ran once")
            assertEquals(1, results.size, "every reader got the same child")
            assertEquals(listOf(parent, results.single()), parent.tree.stores())
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
        // The thread whose wait would close the cycle reports it; the other then takes over the
        // released latch, meets its own declaration again and reports the cycle on its own thread.
        assertEquals(2, failures.size, "both reads fail: no evaluation order can finish a cycle: $failures")
        assertTrue(
            failures.all { it is IllegalStateException && it.message.orEmpty().startsWith("Materialization cycle") },
            "$failures",
        )
        assertTrue(
            failures.any {
                val message = it.message.orEmpty()
                "across threads" in message &&
                    "child declaration 'CmtCycleParent.first'" in message &&
                    "child declaration 'CmtCycleParent.second'" in message
            },
            "the cross-thread report names both declarations: $failures",
        )
        val registry = parent.treeAttachment().registry
        assertTrue(registry.liveChildNodes().isEmpty(), "nothing was published")
        assertEquals(0, InitializerGraph.Process.waitingCount, "no thread is left registered as waiting")
    }
}
