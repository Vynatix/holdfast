@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.tree.LeafNode
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.stores
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class HaFeedStore(
    private val remote: suspend () -> List<String>,
) : Store<HaFeedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    var baseRuns = 0
    val hydration =
        hydrator {
            base {
                baseRuns++
                items mutate listOf("seed")
            }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

private class HaPlainStore : Store<HaPlainStore>() {
    val n by state { 0 }
}

/** A keyed store whose refresh fetches its own key. */
private class HaKeyedStore(
    private val id: String,
) : Store<HaKeyedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { items mutate listOf("seed") }
            refresh { listOf(id) } adopt { fetched -> items mutate fetched }
        }
}

/** The receiver has no hydrator of its own: it is listed, first, as `NoHydrator`. */
private class HaParent(
    remoteA: suspend () -> List<String> = { listOf("a") },
    remoteB: suspend () -> List<String> = { listOf("b") },
) : Store<HaParent>() {
    val a by store { HaFeedStore(remoteA) }
    val b by store { HaFeedStore(remoteB) }
    val other by stores { listOf(HaPlainStore()) }
    val keyed by stores<String, HaKeyedStore> { HaKeyedStore(it) }

    val plain: HaPlainStore get() = other.stores.single() as HaPlainStore
}

/** A feed with a persisted overlay, whose seed reads [kv] before it takes the store. */
private class HaOverlayStore(
    kv: SuspendingKvStore,
) : Store<HaOverlayStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            overlay(kv, "ha-overlay")
            base { items mutate listOf("seed") }
            refresh { listOf("o") } adopt { fetched -> items mutate fetched }
        }
}

/**
 * A key-value store whose `get` disposes [victim] before answering: the
 * store is gone between `hydrateAll`'s check of it and the transaction its
 * `hydrate()` opens.
 */
private class DisposingKvStore : SuspendingKvStore {
    lateinit var victim: Store<*>

    override suspend fun get(key: String): String? {
        victim.dispose()
        return null
    }

    override suspend fun put(
        key: String,
        value: String,
    ) = Unit

    override suspend fun remove(key: String) = Unit

    override suspend fun snapshot(): Map<String, String> = emptyMap()
}

private class HaOverlayParent(
    kv: SuspendingKvStore,
) : Store<HaOverlayParent>() {
    val o by store { HaOverlayStore(kv) }
    val a by store { HaFeedStore { listOf("a") } }
}

/** A hydrated store that writes [label] into [order] when its seed runs. */
private class HaOrderedStore(
    private val label: String,
    private val order: MutableList<String>,
) : Store<HaOrderedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base {
                order += label
                items mutate listOf("seed")
            }
            refresh { listOf(label) } adopt { fetched -> items mutate fetched }
        }
}

/** A mid-tree store with a hydrator of its own and one hydrated child. */
private class HaOrderedMidStore(
    private val order: MutableList<String>,
) : Store<HaOrderedMidStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base {
                order += "mid"
                items mutate listOf("seed")
            }
            refresh { listOf("mid") } adopt { fetched -> items mutate fetched }
        }
    val leaf by store { HaOrderedStore("leaf", order) }
}

/** A receiver with a hydrator of its own, over a hydrated mid-tree store. */
private class HaOrderedParent : Store<HaOrderedParent>() {
    val order = mutableListOf<String>()
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base {
                order += "parent"
                items mutate listOf("seed")
            }
            refresh { listOf("parent") } adopt { fetched -> items mutate fetched }
        }
    val mid by store { HaOrderedMidStore(order) }
}

/** A parent whose one child counts its lambda's runs: materialization is visible. */
private class HaCountingParent : Store<HaCountingParent>() {
    var childRuns = 0
    val child by store {
        childRuns++
        HaPlainStore()
    }
}

/** `StoreTree.hydrateAll`: every hydrator of the subtree driven, the receiver's first; failures aggregated; entries refused. */
class HydrateAllTest {
    @Test
    fun drivesEveryHydratorOfTheSubtreeAndReportsStoresWithoutOne() =
        runBlocking {
            val parent = HaParent()
            val tree = parent.tree
            assertEquals(4, tree.children.size, "a, b, other, keyed — materialized first, so the keyed store sorts after them")
            val k = parent.keyed.create("k")
            val report = tree.hydrateAll()
            assertEquals(listOf("HaParent", "a", "b", "HaPlain", "k"), report.entries.map { it.node.name })
            assertEquals(
                listOf(parent, parent.a, parent.b, parent.plain, k).map { it.lockOrderKey },
                report.entries.map { it.store.lockOrderKey },
                "lockOrderKey order: the receiver first",
            )
            assertTrue(report.isHealthy)
            assertEquals(
                Hydration.Hydrated,
                (report.entries.first { it.store === parent.a }.outcome as HydrateAllReport.Outcome.Ran).hydration,
            )
            assertEquals(listOf("a"), parent.a.items.value)
            assertEquals(listOf("b"), parent.b.items.value)
            assertEquals(listOf("k"), k.items.value)
            assertEquals(listOf<Store<*>>(parent, parent.plain), report.skipped.map { it.store })
            for (skipped in report.skipped) assertIs<HydrateAllReport.Outcome.NoHydrator>(skipped.outcome)
            assertEquals(emptyList<HydrateAllReport.Entry>(), report.failed)
        }

    @Test
    fun theReceiversOwnHydratorRunsFirst() =
        runBlocking {
            val parent = HaOrderedParent()
            val report = parent.tree.hydrateAll()
            assertEquals(listOf("HaOrderedParent", "mid", "leaf"), report.entries.map { it.node.name })
            assertSame(parent, report.entries.first().store)
            assertEquals(listOf("parent", "mid", "leaf"), parent.order, "the seeds ran receiver first, then down the tree")
            for (entry in report.entries) {
                assertEquals(Hydration.Hydrated, assertIs<HydrateAllReport.Outcome.Ran>(entry.outcome).hydration)
            }
            assertEquals(listOf("parent"), parent.items.value)
        }

    @Test
    fun aSubNodeCallExcludesTheReceiverAndIncludesTheSubNodesOwnStore() =
        runBlocking {
            val parent = HaOrderedParent()
            val tree = parent.tree
            val midNode = checkNotNull(tree.nodeOf(parent.mid))
            val report = tree.hydrateAll(midNode)
            assertEquals(listOf("mid", "leaf"), report.entries.map { it.node.name })
            assertEquals(listOf("mid", "leaf"), parent.order)
            assertEquals(Hydration.Detached, parent.hydration.current, "the receiver is not under the sub-node")
            assertTrue(report.isHealthy)
        }

    @Test
    fun withoutAScopeEachStoreRefreshesOnItsOwnScope() =
        runBlocking {
            val ran = mutableListOf<String>()
            val parent =
                HaParent(
                    remoteA = {
                        ran += "a on ${currentCoroutineContext()[CoroutineName]?.name}"
                        listOf("a")
                    },
                    remoteB = {
                        ran += "b on ${currentCoroutineContext()[CoroutineName]?.name}"
                        listOf("b")
                    },
                )
            // Unconfined: each refresh runs inline as its store's hydrate() launches it.
            val scopeA = CoroutineScope(Dispatchers.Unconfined + Job() + CoroutineName("scope-a"))
            val scopeB = CoroutineScope(Dispatchers.Unconfined + Job() + CoroutineName("scope-b"))
            try {
                parent.a.bindToScope(scopeA)
                parent.b.bindToScope(scopeB)
                val report = parent.tree.hydrateAll()
                assertEquals(listOf("a on scope-a", "b on scope-b"), ran, "scope ?: store.scope, per store")
                assertTrue(report.isHealthy)
                assertEquals(listOf("a"), parent.a.items.value)
                assertEquals(listOf("b"), parent.b.items.value)
            } finally {
                scopeA.cancel()
                scopeB.cancel()
            }
        }

    @Test
    fun aggregatesFailuresWithoutThrowing() =
        runBlocking {
            val offline = IllegalStateException("offline")
            val parent = HaParent(remoteA = { throw offline })
            val report = parent.tree.hydrateAll()
            assertFalse(report.isHealthy)
            assertEquals(listOf<Store<*>>(parent.a), report.failed.map { it.store })
            val outcome = assertIs<HydrateAllReport.Outcome.Ran>(report.failed.single().outcome)
            assertEquals(Hydration.Failed(offline), outcome.hydration)
            assertEquals(listOf("b"), parent.b.items.value, "the other store hydrated regardless")
        }

    @Test
    fun aThrowingSeedIsReportedAsFailedAndDoesNotStopTheRest() =
        runBlocking {
            val parent = HaParent()
            parent.a.middlewares(
                object : Middleware<HaFeedStore>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<HaFeedStore>) = error("seed rejected")
                },
            )
            val report = parent.tree.hydrateAll()
            val failedA = assertIs<HydrateAllReport.Outcome.Ran>(report.entries.first { it.store === parent.a }.outcome)
            val failure = assertIs<Hydration.Failed>(failedA.hydration)
            assertContains(failure.cause.message!!, "seed rejected")
            assertEquals(Hydration.Detached, parent.a.hydration.current, "the seed never committed")
            assertEquals(listOf("b"), parent.b.items.value)
        }

    @Test
    fun touchesOnlyTheSubtreeAndIsIdempotentAfterHydrated() =
        runBlocking {
            val parent = HaParent()
            val tree = parent.tree
            val a = parent.a
            val report = tree.hydrateAll(parent.other)
            assertEquals(listOf("HaPlain"), report.entries.map { it.node.name }, "a sub-node call lists no receiver")
            assertEquals(Hydration.Detached, a.hydration.current)

            val aNode = checkNotNull(tree.nodeOf(a))
            tree.hydrateAll(aNode)
            assertEquals(1, a.baseRuns)
            val again = tree.hydrateAll(aNode)
            assertEquals(1, a.baseRuns, "a hydrated store's hydrator does nothing")
            assertTrue(again.isHealthy)
            assertEquals(Hydration.Hydrated, (again.entries.single().outcome as HydrateAllReport.Outcome.Ran).hydration)
            assertEquals(Hydration.Detached, parent.b.hydration.current, "b is not under a's node")
        }

    @Test
    fun withoutAwaitingItReportsThePhaseAfterTheSeed() =
        runBlocking {
            val gate = CompletableDeferred<List<String>>()
            val parent = HaParent(remoteA = { gate.await() })
            val a = parent.a
            val report = parent.tree.hydrateAll(scope = this, awaitSettled = false)
            assertEquals(Hydration.Seeded, (report.entries.first { it.store === a }.outcome as HydrateAllReport.Outcome.Ran).hydration)
            assertEquals(listOf("seed"), a.items.value)
            gate.complete(listOf("late"))
            assertEquals(Hydration.Hydrated, a.hydration.awaitSettled())
            assertEquals(listOf("late"), a.items.value)
        }

    @Test
    fun insideAFrameBodyOrAnEntryItFailsBeforeTouchingAnyStore() =
        runBlocking {
            val parent = HaParent()
            val tree = parent.tree
            val a = parent.a
            val b = parent.b
            val plain = parent.plain
            val refusals = mutableListOf<Throwable?>()
            suspendAtomic(a, b) { refusals += runCatching { tree.hydrateAll() }.exceptionOrNull() }.getOrThrow()
            b.suspendAction { refusals += runCatching { tree.hydrateAll() }.exceptionOrNull() }.getOrThrow()
            parent.suspendAction { refusals += runCatching { tree.hydrateAll() }.exceptionOrNull() }.getOrThrow()
            plain.action { refusals += runCatching { runBlocking { tree.hydrateAll() } }.exceptionOrNull() }.getOrThrow()
            atomic(plain) { refusals += runCatching { runBlocking { tree.hydrateAll() } }.exceptionOrNull() }.getOrThrow()
            assertEquals(5, refusals.size)
            for (refusal in refusals) assertIs<IllegalStateException>(refusal)
            assertEquals(0, a.baseRuns, "no store was touched")
            assertEquals(Hydration.Detached, a.hydration.current)
            assertEquals(Hydration.Detached, b.hydration.current)
        }

    @Test
    fun distinguishesNoHydratorFromDisposed() =
        runBlocking {
            // Another parent's keyed store is no business of this call.
            val bystander = HaParent()
            val victim = bystander.keyed.create("victim")
            val parent = HaParent()
            val tree = parent.tree
            assertEquals(4, tree.children.size, "materialized first: the keyed store sorts (and is driven) after b")
            val late = parent.keyed.create("late")
            parent.plain.middlewares(object : Middleware<HaPlainStore>() {})
            // Dispose the keyed store right after b's seed committed: from b's hydration observer.
            parent.b.hydration.state effect { if (this == Hydration.Seeded) late.dispose() }
            val report = tree.hydrateAll()
            assertIs<HydrateAllReport.Outcome.Disposed>(report.entries.first { it.store === late }.outcome)
            assertIs<HydrateAllReport.Outcome.NoHydrator>(report.entries.first { it.store === parent.plain }.outcome)
            assertIs<HydrateAllReport.Outcome.NoHydrator>(report.entries.first { it.store === parent }.outcome)
            assertEquals(3, report.skipped.size)
            assertTrue(report.isHealthy)
            assertEquals(emptyList<String>(), victim.items.value, "the other parent's store was never driven")
            assertEquals(listOf("victim"), bystander.tree.hydrateAll().let { victim.items.value })
        }

    @Test
    fun cancellationPropagatesWhileRefreshJobsKeepRunning() =
        runBlocking {
            val gate = CompletableDeferred<List<String>>()
            val parent = HaParent(remoteA = { gate.await() })
            val tree = parent.tree
            val a = parent.a
            val b = parent.b
            val refreshScope = this
            val caller =
                launch {
                    tree.hydrateAll(scope = refreshScope)
                }
            yield()
            assertEquals(Hydration.Seeded, a.hydration.current, "seeded, awaiting the refresh")
            caller.cancel()
            caller.join()
            assertTrue(caller.isCancelled)
            gate.complete(listOf("kept running"))
            assertEquals(Hydration.Hydrated, a.hydration.awaitSettled())
            assertEquals(listOf("kept running"), a.items.value)
            assertEquals(Hydration.Hydrated, b.hydration.awaitSettled(), "b's refresh was launched before the cancel")
        }

    @Test
    fun throwsOnADisposedStoreAndAForeignNode(): Unit =
        runBlocking {
            val parent = HaParent()
            val foreign = HaParent()
            val tree = parent.tree
            assertFailsWith<IllegalArgumentException> { tree.hydrateAll(foreign.other) }
            assertFailsWith<IllegalArgumentException> { tree.hydrateAll(foreign.tree.node) }
            parent.dispose()
            val failure = assertFailsWith<IllegalStateException> { tree.hydrateAll() }
            assertContains(failure.message!!, "disposed")
            assertFailsWith<IllegalStateException> { parent.tree }
        }

    @Test
    fun theReportNamesNodesAndOutcomesNeverValues() =
        runBlocking {
            val parent = HaParent()
            val report = parent.tree.hydrateAll()
            val text = report.toString()
            assertContains(text, "Entry(HaParent: NoHydrator)")
            assertContains(text, "Entry(a: Ran(Hydrated))")
            assertFalse("seed" in text || "[a]" in text)
        }

    @Test
    fun aStoreDisposedWhileAwaitedIsReportedDisposedAndNothingThrows() =
        runBlocking {
            val parent = HaParent()
            val a = parent.a
            val b = parent.b
            // a's refresh adopts first (a lower key, launched first); its
            // observer disposes b after b's hydrate() ran, before b's awaitSettled().
            a.hydration.state effect { if (this == Hydration.Hydrated) b.dispose() }
            val report = parent.tree.hydrateAll(scope = this)
            assertTrue(b.isDisposed)
            val ranA = assertIs<HydrateAllReport.Outcome.Ran>(report.entries.first { it.store === a }.outcome)
            assertEquals(Hydration.Hydrated, ranA.hydration)
            val entryB = report.entries.first { it.store === b }
            assertIs<HydrateAllReport.Outcome.Disposed>(entryB.outcome)
            assertEquals("b", entryB.node.name, "the entry keeps the node the store sat at")
            assertEquals(listOf<Store<*>>(parent, b, parent.plain), report.skipped.map { it.store })
            assertTrue(report.isHealthy, "a store gone meanwhile is not a failure")
            assertEquals(emptyList<HydrateAllReport.Entry>(), report.failed)
        }

    @Test
    fun aStoreDisposedAsItsHydrateBeginsIsReportedDisposedNotFailed() =
        runBlocking {
            val kv = DisposingKvStore()
            val parent = HaOverlayParent(kv)
            val o = parent.o
            kv.victim = o
            val report = parent.tree.hydrateAll()
            assertTrue(o.isDisposed)
            assertIs<HydrateAllReport.Outcome.Disposed>(report.entries.first { it.store === o }.outcome)
            assertEquals(emptyList<HydrateAllReport.Entry>(), report.failed)
            assertTrue(report.isHealthy)
            assertEquals(listOf("a"), parent.a.items.value, "the rest hydrated regardless")
        }

    @Test
    fun withoutAwaitingAStoreDisposedAfterItsSeedIsReportedDisposed() =
        runBlocking {
            // Unconfined: each refresh runs inline as it is launched, so b's
            // refresh disposes b inside b's own hydrate(), which returns normally.
            lateinit var parent: HaParent
            parent =
                HaParent(remoteB = {
                    parent.b.dispose()
                    listOf("b")
                })
            val inline = CoroutineScope(Dispatchers.Unconfined + Job())
            try {
                val report = parent.tree.hydrateAll(scope = inline, awaitSettled = false)
                val b = report.entries.first { it.node.name == "b" }
                assertTrue(b.store.isDisposed)
                assertEquals(Hydration.Seeded, (b.store as HaFeedStore).hydration.current, "the phase keeps answering")
                assertIs<HydrateAllReport.Outcome.Disposed>(b.outcome)
                val a = assertIs<HydrateAllReport.Outcome.Ran>(report.entries.first { it.store === parent.a }.outcome)
                assertEquals(Hydration.Hydrated, a.hydration)
                assertTrue(report.isHealthy)
            } finally {
                inline.cancel()
            }
        }

    @Test
    fun aDisposedStoresEntryKeepsItsOwnNode() =
        runBlocking {
            val parent = HaParent()
            val tree = parent.tree
            assertEquals(4, tree.children.size, "materialized first: the keyed store sorts (and is driven) after b")
            val late = parent.keyed.create("late")
            parent.b.hydration.state effect { if (this == Hydration.Seeded) late.dispose() }
            val report = tree.hydrateAll()
            val entry = report.entries.first { it.store === late }
            assertIs<HydrateAllReport.Outcome.Disposed>(entry.outcome)
            assertNull(tree.nodeOf(late), "a disposed store is no longer found")
            val leaf = assertIs<LeafNode>(entry.node)
            assertEquals("late", leaf.name)
            assertEquals("late", leaf.key)
            assertSame(parent.keyed, leaf.parent, "a disposed store's node keeps the place it had")
            assertEquals("Entry(late: Disposed)", entry.toString())
        }

    @Test
    fun theInsideAnEntryRefusalComesBeforeTheListingMaterializesAnyChild() =
        runBlocking {
            val parent = HaCountingParent()
            val tree = parent.tree
            val refused = parent.suspendAction { runCatching { tree.hydrateAll() }.exceptionOrNull() }.getOrThrow()
            assertIs<IllegalStateException>(refused)
            assertEquals(0, parent.childRuns, "refused before any child lambda ran")
            plainAction(parent) { runCatching { runBlocking { tree.hydrateAll() } }.exceptionOrNull() }
            assertEquals(0, parent.childRuns, "nor from a blocking action")
            val report = tree.hydrateAll()
            assertEquals(1, parent.childRuns, "outside every entry the listing materializes the child once")
            assertEquals(listOf("HaCountingParent", "child"), report.entries.map { it.node.name })
        }

    private fun plainAction(
        parent: HaCountingParent,
        body: () -> Throwable?,
    ) {
        var thrown: Throwable? = null
        parent.action { thrown = body() }.getOrThrow()
        assertIs<IllegalStateException>(thrown)
    }
}
