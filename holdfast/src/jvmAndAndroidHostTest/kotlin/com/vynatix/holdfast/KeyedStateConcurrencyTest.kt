@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #20, R7 under concurrency: an entry is created once however many
// threads ask for its key at once; an eviction and the writes of its commit
// (or of its frame) land in the same consistent cut, so a snapshot racing it
// never tears — nor one racing an inbound bridge write made after an entry
// came to life; a capture completes while entries keep coming to life; get,
// evict, evictAll and write churn neither hangs nor fails, and leaves every
// listed entry unretired, one State per key, and every key readable and
// writable again; a failed initializer's re-run by a waiter puts its
// declaration back, or retires it as an orphan (pinned deterministically by
// the two KcFlaky tests, not by the churn); and one entry's membership
// announcements stay ordered. Every test is watchdogged: its regression is a
// hang.

private class KcCounters : Store<KcCounters>() {
    val initializations = AtomicInteger()
    val generation by state { 0 }
    val items by keyedState<String, Int> { _ ->
        initializations.incrementAndGet()
        Thread.sleep(1)
        0
    }
}

/** An initializer that costs nothing. */
private class KcFast : Store<KcFast>() {
    val items by keyedState<String, Int> { 0 }
}

/** An initializer whose [onCall] decides each run, given the run's number. */
private class KcFlaky : Store<KcFlaky>() {
    val calls = AtomicInteger()

    @Volatile var onCall: (Int) -> Int = { 0 }
    val items by keyedState<String, Int> { onCall(calls.incrementAndGet()) }
}

/** A family a UserAuthored capture holds, and one it does not. */
private class KcChurn : Store<KcChurn>() {
    val generation by state { 0 }
    val cache by keyedState<Int, Int> { 0 }
    val pins by keyedState<Int, Int>(tags = setOf(StateTag.UserAuthored)) { 0 }
}

class KeyedStateConcurrencyTest {
    @Test fun threadsAskingForOneKeyAtOnceShareOneEntryAndOneInitializerRun() =
        completesWithin(60, "concurrent gets of one key") {
            val store = KcCounters()
            val threads = 12
            repeat(40) { round ->
                val barrier = CyclicBarrier(threads)
                val got = ConcurrentLinkedQueue<State<Int>>()
                val workers =
                    (1..threads).map {
                        daemon("get-$it") {
                            barrier.await()
                            got += store.items["k$round"]
                        }
                    }
                workers.forEach { it.join() }
                assertEquals(1, got.toSet().size, "round $round: one State for the key")
            }
            assertEquals(40, store.initializations.get(), "one initializer run per key")
        }

    @Test fun aSnapshotRacingEvictionsNeverSeesOneWithoutTheWritesOfItsCommit() =
        completesWithin(60, "snapshots racing evicting commits") {
            val store = KcCounters()
            store.items["g0"]
            val done = AtomicBoolean(false)
            val torn = ConcurrentLinkedQueue<String>()
            val checked = AtomicInteger()
            val readers =
                (1..3).map {
                    daemon("reader-$it") {
                        while (!done.get()) {
                            val snapshot = store.snapshot()
                            val generation = checkNotNull(snapshot[store.generation])
                            val keys = snapshot.keysOf(store.items).map { key -> key.removePrefix("g").toInt() }
                            if (generation !in keys || keys.any { key -> key < generation }) {
                                torn += "generation $generation with keys $keys"
                            }
                            checked.incrementAndGet()
                        }
                    }
                }
            for (i in 1..1_500) {
                store.items["g$i"]
                val committed =
                    store action {
                        items.evict("g${i - 1}")
                        generation mutate i
                    }
                committed.getOrThrow()
            }
            done.set(true)
            readers.forEach { it.join() }

            assertEquals(emptyList(), torn.toList())
            assertTrue(checked.get() > 0, "the readers took snapshots")
            assertEquals(setOf("g1500"), store.items.entries.keys)
        }

    @Test fun aSnapshotRacingInboundBridgeWritesNeverMissesAnEntryCreatedBeforeThem() =
        completesWithin(60, "snapshots racing inbound bridge writes") {
            val store = KcCounters()
            store.items["g0"]
            val pushes = AtomicReference<(Int) -> Unit>()
            store {
                generation observeFrom
                    Observable { observer ->
                        pushes.set(observer)
                        Disposable { }
                    }
            }
            val push = checkNotNull(pushes.get())
            val done = AtomicBoolean(false)
            val torn = ConcurrentLinkedQueue<String>()
            val checked = AtomicInteger()
            val readers =
                (1..3).map {
                    daemon("bridge-reader-$it") {
                        while (!done.get()) {
                            val snapshot = store.snapshot()
                            val generation = checkNotNull(snapshot[store.generation])
                            val keys = snapshot.keysOf(store.items)
                            // g<i> came to life before i was pushed: a cut holding i holds it.
                            if ("g$generation" !in keys) torn += "generation $generation with ${keys.size} keys"
                            checked.incrementAndGet()
                        }
                    }
                }
            for (i in 1..1_500) {
                store.items["g$i"]
                // No action: an inbound bridge write, bracketed on its own.
                push(i)
            }
            done.set(true)
            readers.forEach { it.join() }

            assertEquals(emptyList(), torn.toList())
            assertTrue(checked.get() > 0, "the readers took snapshots")
            assertEquals(1_500, store.generation.value)
        }

    @Test fun aSnapshotNeverSeesHalfOfOneCommitsEvictions() =
        completesWithin(60, "snapshots racing commits that evict two entries at once") {
            val store = KcCounters()
            store.items["p0"]
            store.items["q0"]
            val done = AtomicBoolean(false)
            val torn = ConcurrentLinkedQueue<String>()
            val readers =
                (1..3).map {
                    daemon("pair-reader-$it") {
                        while (!done.get()) {
                            val keys = store.snapshot().keysOf(store.items)
                            val indices = keys.map { key -> key.drop(1).toInt() }
                            val newest = indices.maxOrNull() ?: continue
                            // Only the newest pair may be half there: its q is created after its p.
                            val halves = indices.filter { i -> i < newest && ("p$i" in keys) != ("q$i" in keys) }
                            if (halves.isNotEmpty()) torn += "keys $keys"
                        }
                    }
                }
            for (i in 1..1_500) {
                store.items["p$i"]
                store.items["q$i"]
                val committed =
                    store action {
                        items.evict("p${i - 1}")
                        items.evict("q${i - 1}")
                    }
                committed.getOrThrow()
            }
            done.set(true)
            readers.forEach { it.join() }

            assertEquals(emptyList(), torn.toList(), "one commit's two evictions are seen together or not at all")
            assertEquals(setOf("p1500", "q1500"), store.items.entries.keys)
        }

    /**
     * A smoke test of get/evict/evictAll/write churn: it catches a hang (the
     * watchdog) or a failure, and checks the end state. It cannot provoke a
     * live orphan — one is only reachable after a failed initializer, which
     * [KcFast] never has — so the orphan paths are pinned deterministically
     * elsewhere: the put-back by [aWaiterReRunningAFailedInitializerPutsItsDeclarationBack],
     * retiring the orphan and `stateFor`'s retired re-check by
     * [aFailedDeclarationReRunAfterANewerEntryExistsIsRetiredAsAnOrphan].
     */
    @Test fun getsWritesAndEvictionsChurningNeverHangOrFail() =
        completesWithin(60, "gets, writes and evictions churning") {
            val store = KcFast()
            val keys = (0 until 8).map { "k$it" }
            val stop = AtomicBoolean(false)
            val failures = ConcurrentLinkedQueue<Throwable>()
            // Every State handed out, by identity (MutableState keeps Any's equals).
            val handedOut = ConcurrentHashMap<State<Int>, String>()
            val getters =
                (1..4).map { n ->
                    daemon("getter-$n") {
                        var i = n
                        try {
                            while (!stop.get()) {
                                val key = keys[i++ % keys.size]
                                handedOut[store.items[key]] = key
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                }
            val writers =
                (1..2).map { n ->
                    daemon("writer-$n") {
                        var i = n
                        while (!stop.get()) {
                            val key = keys[i++ % keys.size]
                            val r = store action { items[key] update { it + 1 } }
                            (r as? TransactionResult.Error)?.let { failures += it.exception }
                        }
                    }
                }
            val evictor =
                daemon("evictor") {
                    repeat(2_000) { round ->
                        if (round % 3 == 0) store.items.evictAll() else store.items.evict(keys[round % keys.size])
                    }
                }
            evictor.join()
            stop.set(true)
            (getters + writers).forEach { it.join() }

            assertEquals(emptyList(), failures.toList(), "no get or write inside an action ever failed")
            for ((state, key) in handedOut) {
                assertTrue(
                    (state as MutableState<*>).retired || state === store.items.getOrNull(key),
                    "a State handed out is live or retired (never dropped without being retired)",
                )
            }
            val live = store.items.entries
            assertTrue(live.values.all { !(it as MutableState<*>).retired }, "every listed entry is live")
            assertEquals(live.keys.size, live.values.toSet().size, "one State per key")
            for (key in keys) {
                store.items[key]
                store.action { items[key] update { it + 1 } }.getOrThrow()
            }
        }

    @Test fun stagedEvictionsIsReadOnTheOwnerThreadOnly() {
        val store = KcFast()
        store.items["a"]
        val offThread = AtomicReference<Throwable?>()

        store
            .action {
                items.evict("a")
                val txn = checkNotNull(activeTransaction)
                assertEquals(1, txn.stagedEvictions.size)
                daemon("off-owner") { offThread.set(runCatching { txn.stagedEvictions }.exceptionOrNull()) }.join()
            }.getOrThrow()

        assertIs<IllegalStateException>(offThread.get(), "another thread may not read it")
    }

    @Test fun aWaiterReRunningAFailedInitializerPutsItsDeclarationBack() {
        val store = KcFlaky()
        val graph = InitializerGraph(::currentThreadId)
        store.initializerGraph = graph
        val firstEntered = CountDownLatch(1)
        val firstFailed = CountDownLatch(1)
        store.onCall = { call ->
            when (call) {
                1 -> {
                    firstEntered.countDown()
                    while (graph.waitingCount < 1) Thread.yield() // B waits for this latch
                    error("the first run fails")
                }
                2 -> {
                    check(firstFailed.await(10, TimeUnit.SECONDS)) { "A never returned its failure" }
                    7
                }
                else -> error("unexpected initializer run $call")
            }
        }
        val a = AtomicReference<Result<State<Int>>>()
        val b = AtomicReference<Result<State<Int>>>()

        completesWithin(20, "a waiter re-running a failed initializer") {
            val ta =
                daemon("first-getter") {
                    a.set(runCatching { store.items["k"] })
                    firstFailed.countDown()
                }
            check(firstEntered.await(10, TimeUnit.SECONDS)) { "the first run never started" }
            val tb = daemon("waiting-getter") { b.set(runCatching { store.items["k"] }) }
            ta.join()
            tb.join()
        }

        assertTrue(a.get().isFailure, "A saw its initializer fail")
        val s = b.get().getOrThrow()
        assertSame(s, store.items.getOrNull("k"), "B's declaration went back into the family")
        assertSame(s, store.items["k"])
        assertEquals(setOf("k"), store.items.entries.keys)
        assertEquals(7, s.value)
        assertEquals(2, store.calls.get(), "the initializer ran twice: failed, then re-run by the waiter")
        assertEquals(0, graph.waitingCount)
    }

    @Test fun aFailedDeclarationReRunAfterANewerEntryExistsIsRetiredAsAnOrphan() {
        val store = KcFlaky()
        val graph = InitializerGraph(::currentThreadId)
        store.initializerGraph = graph
        val firstEntered = CountDownLatch(1)
        val firstFailed = CountDownLatch(1)
        val thirdStarted = CountDownLatch(1)
        store.onCall = { call ->
            when (call) {
                1 -> {
                    firstEntered.countDown()
                    while (graph.waitingCount < 1) Thread.yield() // B waits for this latch
                    error("the first run fails")
                }
                2 -> {
                    check(thirdStarted.await(10, TimeUnit.SECONDS)) { "C never started its run" }
                    2
                }
                3 -> {
                    thirdStarted.countDown()
                    while (graph.waitingCount < 1) Thread.yield() // B, its orphan retired, waits for C's entry
                    3
                }
                else -> error("unexpected initializer run $call")
            }
        }
        val a = AtomicReference<Result<State<Int>>>()
        val b = AtomicReference<Result<State<Int>>>()
        val c = AtomicReference<Result<State<Int>>>()

        completesWithin(20, "an orphaned re-run") {
            val ta =
                daemon("first-getter") {
                    a.set(runCatching { store.items["k"] })
                    firstFailed.countDown()
                }
            check(firstEntered.await(10, TimeUnit.SECONDS)) { "the first run never started" }
            val tb = daemon("waiting-getter") { b.set(runCatching { store.items["k"] }) }
            check(firstFailed.await(10, TimeUnit.SECONDS)) { "A never returned its failure" }
            val tc = daemon("late-getter") { c.set(runCatching { store.items["k"] }) }
            listOf(ta, tb, tc).forEach { it.join() }
        }

        assertTrue(a.get().isFailure)
        val fromB = b.get().getOrThrow()
        val fromC = c.get().getOrThrow()
        assertSame(fromC, fromB, "B dropped its orphan and returned C's entry")
        assertFalse((fromB as MutableState<*>).retired)
        assertEquals(mapOf("k" to fromC), store.items.entries)
        assertEquals(3, fromC.value)
        assertEquals(3, store.calls.get())
        assertEquals(0, graph.waitingCount)
    }

    @Test fun oneEntrysMembershipAnnouncementsAreOrderedUnderRaces() =
        completesWithin(60, "gets racing evictions, heard by a membership listener") {
            val store = KcFast()
            val events = ConcurrentHashMap<State<*>, MutableList<Char>>()
            val tracked = ConcurrentHashMap<State<*>, Unit>()
            store.internalObserveKeyedMembership(
                object : KeyedMembershipListener {
                    override fun onEntryAdded(
                        family: String,
                        key: Any,
                        entry: State<*>,
                    ) {
                        events.computeIfAbsent(entry) { Collections.synchronizedList(ArrayList()) } += '+'
                        tracked[entry] = Unit
                    }

                    override fun onEntryEvicted(
                        family: String,
                        key: Any,
                        entry: State<*>,
                    ) {
                        events.computeIfAbsent(entry) { Collections.synchronizedList(ArrayList()) } += '-'
                        tracked.remove(entry)
                    }
                },
            )
            val stop = AtomicBoolean(false)
            val getters =
                (1..3).map { n ->
                    daemon("getter-$n") { while (!stop.get()) store.items["k${n % 2}"] }
                }
            val evictors =
                (1..2).map { n ->
                    daemon("evictor-$n") { repeat(3_000) { store.action { items.evict("k${n % 2}") }.getOrThrow() } }
                }
            evictors.forEach { it.join() }
            stop.set(true)
            getters.forEach { it.join() }

            val sequences = events.values.map { it.joinToString("") }
            assertTrue(sequences.isNotEmpty())
            assertEquals(emptyList(), sequences.filter { it != "+" && it != "+-" }, "each entry: added, then maybe evicted")
            val live = store.items.entries
            assertEquals<Set<State<*>>>(live.values.toSet(), tracked.keys, "identity tracking matches the live entries")
        }

    @Test fun aUserAuthoredSnapshotCompletesWhileAnotherFamilyChurns() =
        completesWithin(60, "UserAuthored snapshots under churn in a family they do not capture") {
            val store = KcChurn()
            repeat(2_000) { store.pins[it] }
            val stop = AtomicBoolean(false)
            val progress = AtomicInteger()
            val creator = daemon("cache-creator") { churn(store, stop, progress) }
            awaitChurning(progress)

            repeat(20) {
                val authored = store.snapshot(SnapshotScope.UserAuthored)
                assertEquals(setOf("pins"), authored.stateNames)
                assertEquals(2_000, authored.keysOf(store.pins).size)
            }
            stop.set(true)
            creator.join()
        }

    @Test fun aSnapshotCompletesWhileEntriesKeepComingToLifeAndCommitsKeepApplying() =
        completesWithin(60, "snapshots under entry creation and commit churn") {
            val store = KcChurn()
            // Makes each listing and cut long enough for an entry to come to life,
            // and a commit to write it, during every one: without holding creation
            // back, a capture here lists again for as long as the churn lasts.
            repeat(50_000) { store.pins[it] }
            val stop = AtomicBoolean(false)
            val progress = AtomicInteger()
            val creator = daemon("cache-creator") { churn(store, stop, progress) }
            awaitChurning(progress)

            repeat(10) {
                val snapshot = store.snapshot()
                assertEquals(50_000, snapshot.keysOf(store.pins).size)
            }
            stop.set(true)
            creator.join()
        }

    @Test fun aConsistentCutNeverSeesHalfOfAFramesEvictions() =
        completesWithin(60, "cuts of two stores racing frames that evict an entry of each") {
            val a = KcFast()
            val b = KcFast()
            a.items["p0"]
            b.items["q0"]
            val done = AtomicBoolean(false)
            val torn = ConcurrentLinkedQueue<String>()
            val checked = AtomicInteger()
            val readers =
                (1..3).map {
                    daemon("frame-reader-$it") {
                        while (!done.get()) {
                            val (l, r) = captureConsistent(listOf(a, b))
                            val left = l.keysOf(a.items).map { key -> key.drop(1).toInt() }.toSet()
                            val right = r.keysOf(b.items).map { key -> key.drop(1).toInt() }.toSet()
                            val newest = (left + right).maxOrNull() ?: continue
                            // Only the newest pair may be half there: q is created after p.
                            val halves = (left + right).filter { i -> i < newest && (i in left) != (i in right) }
                            if (halves.isNotEmpty()) torn += "left $left, right $right"
                            checked.incrementAndGet()
                        }
                    }
                }
            for (i in 1..1_500) {
                a.items["p$i"]
                b.items["q$i"]
                // No writes: a pending write would bracket a declared state and mask a tear.
                atomic(a, b) {
                    a.items.evict("p${i - 1}")
                    b.items.evict("q${i - 1}")
                }.getOrThrow()
            }
            done.set(true)
            readers.forEach { it.join() }

            assertEquals(emptyList(), torn.toList(), "a frame's two evictions are seen together or not at all")
            assertTrue(checked.get() > 0)
            assertEquals(setOf("p1500"), a.items.entries.keys)
            assertEquals(setOf("q1500"), b.items.entries.keys)
        }
}

/**
 * Until [stop]: create a fresh cache entry, and evict the one created a
 * hundred creations earlier (a commit of its own), over and over — and count
 * the first thousand on [progress]. Every eviction writes an entry a capture
 * may have listed, holding its cut up, and every creation comes to life
 * unlisted: a capture that saw both must list again.
 */
private fun churn(
    store: KcChurn,
    stop: AtomicBoolean,
    progress: AtomicInteger,
) {
    var i = 0
    while (!stop.get()) {
        store.cache[i++]
        if (i == CHURN_WARM_UP) progress.incrementAndGet()
        if (i > 100) store.cache.evict(i - 100)
    }
}

/** Wait until [churn] is running flat out. */
private fun awaitChurning(progress: AtomicInteger) {
    while (progress.get() < 1) Thread.yield()
}

private const val CHURN_WARM_UP = 1_000
