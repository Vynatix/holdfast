@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #20, R7 acceptance (eviction): evicting one key leaves every other
// key's observers and bridges live; eviction is transactional (staged,
// committed with its transaction, rolled back with it, the last operation on
// an entry winning); an evicted entry's State is a stale handle; an eviction
// from its own store's commit fanout is deferred (D16).

private class KeCache : Store<KeCache>() {
    val tick by state { 0 }
    val items by keyedState<String, Int> { key -> key.length }
}

private class KeOther : Store<KeOther>() {
    val flag by state { false }
}

/** An in-memory bridge that records what it is published and lets a test push inbound values. */
private class KeBridge : Bridge<Int> {
    val published = mutableListOf<Int>()
    var inbound: ((Int) -> Unit)? = null
    var disposed = 0

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable {
            disposed++
            inbound = null
        }
    }

    override fun publish(value: Int): Boolean = published.add(value)
}

/** Records every transaction's staged writes and evictions, in keyed-entry names. */
private class KeLog : Middleware<KeCache>() {
    val lines = mutableListOf<String>()

    override fun onTransactionCompleted(context: MiddlewareContext<KeCache>) {
        val txn = context.transaction
        lines += "${txn.id}: writes=${txn.modifiedStates.size} evicts=${txn.stagedEvictions.size}"
    }
}

class KeyedStateEvictionTest {
    @Test fun evictingOneKeyLeavesEveryOtherKeysObserversAndBridgesLive() {
        val store = KeCache()
        val a = store.items["a"]
        val b = store.items["bb"]
        val seenA = mutableListOf<Int>()
        val seenB = mutableListOf<Int>()
        a effect { seenA += this }
        b effect { seenB += this }
        val bridgeA = KeBridge()
        val bridgeB = KeBridge()
        store {
            a bridge bridgeA
            b bridge bridgeB
        }

        store.items.evict("a")
        store action {
            items["bb"] mutate 20
            tick mutate 1
        }
        bridgeB.inbound?.invoke(21)

        assertEquals(listOf(1), seenA, "the evicted entry's observer got no further notification")
        assertEquals(0, a.observerCount)
        assertEquals(1, bridgeA.disposed, "the evicted entry's bridge was detached")
        assertEquals(listOf(2, 20, 21), seenB, "the other key's observer stayed live")
        assertEquals(listOf(20), bridgeB.published, "…and its bridge still publishes")
        assertEquals(21, b.value, "…and still delivers inbound values")
        assertEquals(setOf("bb"), store.items.entries.keys)
    }

    @Test fun anEvictionIsStagedCommittedAndRolledBackWithItsTransaction() {
        val store = KeCache()
        store.items["a"]

        val rolledBack =
            store action {
                items.evict("a")
                assertFalse("a" in items, "the action reads its own staged eviction")
                assertTrue(items.entries.isEmpty())
                error("roll back")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertTrue("a" in store.items, "a rollback discards the eviction")

        store action {
            items.evict("a")
            assertTrue(items.entries.isEmpty())
        }
        assertFalse("a" in store.items, "the commit applies it")
    }

    @Test fun anEvictedEntrysStateIsAStaleHandle() {
        val store = KeCache()
        val stale = store.items["abc"]
        store action { items["abc"] mutate 7 }
        store.items.evict("abc")

        assertEquals(7, stale.value, "a stale handle keeps its last value for reads")
        val mutate = assertFailsWith<IllegalStateException> { store { stale mutate 1 } }
        assertContains(mutate.message.orEmpty(), "Cannot write KeCache.items[*]: this entry of the keyed state family")
        assertContains(mutate.message.orEmpty(), "stale handle")
        assertFailsWith<IllegalStateException> { store { stale bridge KeBridge() } }
        assertFailsWith<IllegalStateException> { store { stale observeFrom Observable { Disposable { } } } }
        assertEquals(7, stale.value)

        val fresh = store.items["abc"]
        assertNotSame(stale, fresh, "the next get creates a new entry")
        assertEquals(3, fresh.value, "…from the family's initializer")
    }

    @Test fun anUpdateThroughAStaleHandleFailsItsAction() {
        val store = KeCache()
        val stale = store.items["x"]
        store.items.evict("x")

        val r = store action { stale update { it + 1 } }
        val error = assertIs<TransactionResult.Error>(r).exception
        assertContains(error.message.orEmpty(), "stale handle")
    }

    @Test fun theLastOperationOnAnEntryWinsInOneTransaction() {
        val store = KeCache()
        val entry = store.items["k"]
        store action { items["k"] mutate 5 }

        store action {
            items["k"] mutate 6
            items.evict("k")
            assertSame(entry, items["k"], "a get after the eviction cancels it: the same entry")
        }
        assertSame(entry, store.items["k"])
        assertEquals(5, entry.value, "the write the eviction dropped stays dropped")

        store action {
            items.evict("k")
            entry.mutate(9)
        }
        assertSame(entry, store.items.getOrNull("k"), "a write after the eviction cancels it too")
        assertEquals(9, entry.value)
    }

    @Test fun evictionsMergeAcrossSavepointsLastOperationWinning() {
        val store = KeCache()
        val entry = store.items["k"]

        store action {
            (this action { items.evict("k") }).getOrThrow()
            assertFalse("k" in items, "the committed savepoint's eviction is the outer transaction's now")
        }
        assertFalse("k" in store.items)

        val revived = store.items["k"]
        store action {
            items.evict("k")
            (this action { items["k"] }).getOrThrow()
        }
        assertSame(revived, store.items.getOrNull("k"), "the savepoint's get, committed into its parent, wins")

        store action {
            items.evict("k")
            val inner =
                this action {
                    items["k"]
                    error("the savepoint rolls back")
                }
            assertIs<TransactionResult.Error>(inner)
        }
        assertNull(store.items.getOrNull("k"), "a rolled-back savepoint's get cancels nothing")
        assertNotSame(entry, revived)

        val log = KeLog()
        store.middlewares(log)
        val w = store.items["w"]
        store action { items["w"] mutate 5 }
        log.lines.clear()
        store action {
            items["w"] mutate 6
            (this action { items.evict("w") }).getOrThrow()
        }
        assertFalse("w" in store.items)
        assertEquals(5, w.value, "the savepoint's eviction dropped the parent's pending write too")
        assertEquals(
            "writes=0 evicts=1",
            log.lines.last().substringAfter(": "),
            "the merged eviction is disjoint from the outer transaction's writes",
        )
    }

    @Test fun anEvictionDropsThePendingWriteAndIsDisjointFromModifiedStates() {
        val store = KeCache()
        val log = KeLog()
        store.middlewares(log)
        store.items["a"]
        store.items["b"]

        store action {
            items["a"] mutate 1
            items["b"] mutate 2
            items.evict("a")
        }
        store.items.evict("b")

        assertEquals(
            listOf("writes=1 evicts=1", "writes=0 evicts=1"),
            log.lines.map { it.substringAfter(": ") },
            "the eviction dropped a's write; outside an action, evict is a one-shot action",
        )
    }

    @Test fun anEvictionFromItsOwnStoresFanoutIsDeferredUntilTheCommitEnds() {
        val store = KeCache()
        store.items["a"]
        val during = mutableListOf<Boolean>()
        store.tick effect {
            if (this == 1) {
                store.items.evict("a")
                during += "a" in store.items
            }
        }
        var reported: Throwable? = null
        store.uncaughtObserverHandler = { reported = it }

        val r = store action { tick mutate 1 }

        assertIs<TransactionResult.Success<Unit>>(r)
        assertNull(reported, "not refused: an eviction is the one write that defers")
        assertEquals(listOf(true), during, "inside the fanout the entry is still live")
        assertFalse("a" in store.items, "it is evicted once the commit has released the store")
    }

    @Test fun aDeferredEvictionIsATransactionOfItsOwnNamedEvict() {
        val store = KeCache()
        store.items["a"]
        val log = KeLog()
        store.middlewares(log)
        store.tick effect { if (this == 1) store.items.evict("a") }

        store action { tick mutate 1 }

        assertEquals("Evict: writes=0 evicts=1", log.lines.last())
        assertFalse("a" in store.items)
    }

    @Test fun aDeferredEvictAllEvictsOnlyTheEntriesLiveAtTheCall() {
        val store = KeCache()
        val before = listOf(store.items["a"], store.items["b"])
        val seen = mutableListOf<Int>()
        store.tick effect {
            if (this == 1) {
                store.items.evictAll()
                val created = store.items["new"]
                created effect { seen += this }
            }
        }

        store action { tick mutate 1 }

        assertEquals(setOf("new"), store.items.entries.keys, "the entries live before the commit are gone")
        assertTrue(before.all { (it as MutableState<*>).retired })
        store action { items["new"] mutate 30 }
        assertEquals(listOf(3, 30), seen, "the entry created after the call kept its observer")
    }

    @Test fun aDeferredEvictionNeverTouchesAnEntryCreatedAgainForItsKey() {
        val store = KeCache()
        store.items["a"]
        store.tick effect { if (this == 1) store.items.evict("a") }
        store action { tick mutate 1 }
        val fresh = store.items["a"]

        store action { tick mutate 2 }

        assertSame(fresh, store.items.getOrNull("a"), "a later drain left the new entry alone")
    }

    @Test fun aWriteFromTheSameFanoutIsStillRefused() {
        val store = KeCache()
        val entry = store.items["a"]
        store.tick effect { if (this == 1) store { entry mutate 3 } }
        val reported = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { reported += it }

        store action { tick mutate 1 }

        assertIs<IllegalStateException>(reported.single())
        assertEquals(1, entry.value)
    }

    @Test fun evictAllOfAThousandKeysLeavesTheFamilyEmptyWithNoObservers() {
        val store = KeCache()
        val entries = (1..1_000).map { store.items["key$it"] }
        val subscriptions = entries.map { it effect { } }
        assertEquals(1_000, store.items.entries.size)

        store.items.evictAll()

        assertTrue(store.items.entries.isEmpty())
        assertTrue(entries.all { it.observerCount == 0 }, "every evicted entry's observers are gone")
        subscriptions.forEach { it.dispose() }
        assertEquals(0, store.snapshot().keysOf(store.items).size)
    }

    @Test fun observeFromSubscriptionsAreDisposedByTheEviction() {
        val store = KeCache()
        val entry = store.items["a"]
        val live = mutableListOf<String>()
        val source =
            Observable<Int> { observer ->
                live += "on"
                observer(40)
                Disposable { live += "off" }
            }
        store { entry observeFrom source }
        assertEquals(40, entry.value)

        store.items.evict("a")

        assertEquals(listOf("on", "off"), live)
    }

    @Test fun anInboundValueForAnEvictedEntryIsDropped() {
        val store = KeCache()
        val entry = store.items["a"]
        var push: ((Int) -> Unit)? = null
        store {
            entry observeFrom
                Observable { observer ->
                    push = observer
                    Disposable { }
                }
        }
        store.items.evict("a")

        push?.invoke(99)

        assertEquals(1, entry.value, "the retired entry ignores it")
        assertNull(store.items.getOrNull("a"))
    }

    @Test fun anInitializerMayNotEvict() {
        val store = KeCache()
        store.items["a"]
        val evicting: KeyedState<Int, Int> by KeOther().keyedState { _ ->
            store.items.evict("a")
            0
        }

        val refused = assertFailsWith<IllegalStateException> { evicting[1] }
        assertContains(refused.message.orEmpty(), "Cannot evict an entry of KeCache.items")
        assertTrue("a" in store.items)
    }

    @Test fun anUnenrolledStoresEvictionInsideAFrameThrows() {
        val store = KeCache()
        val other = KeOther()
        store.items["a"]

        val refused =
            assertFailsWith<UnenrolledStoreException> {
                atomic(other) { store.items.evict("a") }
            }
        assertContains(refused.message.orEmpty(), "via action", message = "a one-shot action of its own, refused")
        assertTrue("a" in store.items)
        atomic(other, store) { store.items.evict("a") }.getOrThrow()
        assertFalse("a" in store.items)
    }

    @Test fun anUnenrolledStoresEvictionInsideAFrameInItsOwnActionThrows() {
        val store = KeCache()
        val other = KeOther()
        store.items["a"]

        for (evict in listOf<() -> Unit>({ store.items.evict("a") }, { store.items.evictAll() })) {
            val outer = store.action { atomic(other) { evict() } }

            val error = assertIs<TransactionResult.Error>(outer)
            val refused = assertIs<UnenrolledStoreException>(error.exception)
            assertContains(refused.message.orEmpty(), "via evict")
            assertTrue("a" in store.items, "the eviction did not commit with the enclosing action")
        }
    }

    @Test fun aGetFromAFrameThatDoesNotEnrollTheStoreLeavesItsEnclosingEvictionStaged() {
        val store = KeCache()
        val other = KeOther()
        store.items["a"]

        store
            .action {
                items.evict("a")
                val frame =
                    atomic(other) {
                        store.items["a"]
                        error("the frame fails")
                    }
                assertIs<TransactionResult.Error>(frame)
            }.getOrThrow()

        assertFalse("a" in store.items, "the failed frame's get did not cancel the eviction")
    }

    @Test fun aGetFromAFrameAllowingUnenrolledWritesCancelsTheEnclosingEviction() {
        val store = KeCache()
        val other = KeOther()
        val a = store.items["a"]

        store
            .action {
                items.evict("a")
                atomic(other, policy = FramePolicy.AllowUnenrolled) { store.items["a"] }.getOrThrow()
            }.getOrThrow()

        assertSame(a, store.items.getOrNull("a"), "the get cancelled the eviction, as an allowed write would")
    }

    @Test fun aThrowingMembershipListenerIsReportedAndTheOthersAreStillTold() {
        val store = KeCache()
        val reported = mutableListOf<String?>()
        store.uncaughtObserverHandler = { reported += it.message }
        val heard = mutableListOf<String>()
        store.internalObserveKeyedMembership(
            object : KeyedMembershipListener {
                override fun onEntryAdded(
                    family: String,
                    key: Any,
                    entry: State<*>,
                ): Unit = error("added")

                override fun onEntryEvicted(
                    family: String,
                    key: Any,
                    entry: State<*>,
                ): Unit = error("evicted")
            },
        )
        store.internalObserveKeyedMembership(
            object : KeyedMembershipListener {
                override fun onEntryAdded(
                    family: String,
                    key: Any,
                    entry: State<*>,
                ) {
                    heard += "+$key"
                }

                override fun onEntryEvicted(
                    family: String,
                    key: Any,
                    entry: State<*>,
                ) {
                    heard += "-$key"
                }
            },
        )

        store.items["a"]
        store.items.evict("a")

        assertEquals(listOf("+a", "-a"), heard, "the second listener heard both")
        assertEquals(listOf<String?>("added", "evicted"), reported, "the first one's failures were reported")
    }

    @Test fun aThrowingDisposeNeitherStopsTheShutdownNorGoesUnreported() {
        val store = KeCache()
        val a = store.items["a"]
        val b = store.items["bb"]
        val disposed = mutableListOf<String>()

        fun source(
            name: String,
            fails: Boolean,
        ) = Observable<Int> { _ ->
            Disposable {
                disposed += name
                if (fails) error("$name failed")
            }
        }
        val failingBridge =
            object : Bridge<Int> {
                override fun observe(observer: (Int) -> Unit): Disposable =
                    Disposable {
                        disposed += "a-bridge"
                        error("a-bridge failed")
                    }

                override fun publish(value: Int): Boolean = true
            }
        a effect { }
        b effect { }
        store {
            a bridge failingBridge
            a observeFrom source("a-in", fails = false)
            b observeFrom source("b-in-1", fails = true)
            b observeFrom source("b-in-2", fails = false)
        }
        val reported = mutableListOf<String?>()
        val atReport = mutableListOf<String>()
        store.uncaughtObserverHandler = { failure ->
            reported += failure.message
            atReport += "${a.observerCount} ${b.observerCount} ${(a as MutableState<*>).bridge == null} ${disposed.size}"
        }

        store.items.evictAll()

        assertEquals(setOf("a-bridge", "a-in", "b-in-1", "b-in-2"), disposed.toSet(), "every subscription was disposed")
        assertEquals(0, a.observerCount)
        assertEquals(0, b.observerCount)
        assertNull((a as MutableState<*>).bridge, "a's bridge was detached though its dispose threw")
        assertEquals(setOf<String?>("a-bridge failed", "b-in-1 failed"), reported.toSet())
        assertEquals(2, reported.size)
        assertEquals(List(2) { "0 0 true 4" }, atReport, "reported once every evicted entry was shut down")
    }

    @Test fun aDeferredEvictionThatFailsIsReported() {
        val store = KeCache()
        store.items["a"]
        store.middlewares(
            object : Middleware<KeCache>() {
                override fun onTransactionCompleted(context: MiddlewareContext<KeCache>) {
                    check(context.transaction.id != "Evict") { "no evictions today" }
                }
            },
        )
        val reported = mutableListOf<String?>()
        store.uncaughtObserverHandler = { reported += it.message }
        store.tick effect { if (this == 1) store.items.evict("a") }

        store.action { tick mutate 1 }.getOrThrow()

        assertEquals(listOf<String?>("no evictions today"), reported)
        assertTrue("a" in store.items, "the rejected eviction evicted nothing")
    }

    @Test fun aDeferredEvictionOnAStoreDisposedMeanwhileDoesNothing() {
        val store = KeCache()
        store.items["a"]
        val reported = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { reported += it }
        store.tick effect { if (this == 1) store.items.evict("a") }
        store.tick effect { if (this == 1) store.dispose() }

        store.action { tick mutate 1 }

        assertTrue(store.isDisposed)
        assertEquals(emptyList(), reported, "nothing to report: the store is gone")
    }

    @Test fun evictingAMissingKeyStagesNothing() {
        val store = KeCache()
        val log = KeLog()
        store.middlewares(log)

        store.items.evict("never")
        store.items.evictAll()

        assertEquals(listOf("writes=0 evicts=0", "writes=0 evicts=0"), log.lines.map { it.substringAfter(": ") })
    }

    @Test fun aDisposedStoresFamilyRefusesEverything() {
        val store = KeCache()
        val entry = store.items["a"]
        entry effect { }
        val bridge = KeBridge()
        val live = mutableListOf<String>()
        store {
            entry bridge bridge
            entry observeFrom
                Observable<Int> { observer ->
                    live += "on"
                    observer(40)
                    Disposable { live += "off" }
                }
        }
        assertEquals(1, entry.observerCount)
        store.dispose()

        for (call in listOf<() -> Unit>(
            { store.items["b"] },
            { store.items.getOrNull("a") },
            { "a" in store.items },
            { store.items.entries },
            { store.items.evict("a") },
            { store.items.evictAll() },
        )) {
            assertContains(assertFailsWith<IllegalStateException> { call() }.message.orEmpty(), "disposed")
        }
        assertEquals(0, entry.observerCount, "dispose dropped the entry's observers")
        assertEquals(1, bridge.disposed, "…detached its bridge")
        assertNull(bridge.inbound)
        assertEquals(listOf("on", "off"), live, "…and disposed its observeFrom subscription")
    }

    @Test fun aBridgeCannotBeAttachedToAnEvictedEntryEvenPastTheStoresCheck() {
        val store = KeCache()
        val stale = store.items["a"]
        store.items.evict("a")
        val bridge = KeBridge()

        @Suppress("UNCHECKED_CAST")
        val refused = assertFailsWith<IllegalStateException> { (stale as MutableState<Int>).bridge = bridge }

        assertContains(refused.message.orEmpty(), "stale handle")
        assertNull(bridge.inbound, "the bridge was never observed")
        assertNull((stale as MutableState<*>).bridge)
    }
}
