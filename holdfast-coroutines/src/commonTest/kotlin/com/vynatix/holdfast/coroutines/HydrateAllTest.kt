@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.tree.Root
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

private class HaKeyedStore(
    id: String,
    root: HaRoot,
    remote: suspend () -> List<String>,
) : Store<HaKeyedStore>(root.keyed.at(id)) {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { items mutate listOf("seed") }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

private class HaRoot(
    remoteA: suspend () -> List<String> = { listOf("a") },
    remoteB: suspend () -> List<String> = { listOf("b") },
) : Root("ha") {
    val a = HaFeedStore(remoteA)
    val b = HaFeedStore(remoteB)
    val plain = HaPlainStore()
    val feeds by branch(a, b).named(a, "a").named(b, "b")
    val other by branch(plain)
    val keyed by keyed<String, HaKeyedStore>(under = feeds)
}

/** `Root.hydrateAll`: every hydrator under a node driven, failures aggregated, entries refused. */
class HydrateAllTest {
    @Test
    fun drivesEveryHydratorUnderTheNodeAndReportsLeavesWithoutOne() =
        runBlocking {
            val root = HaRoot()
            val k = root.keyed.create("k") { HaKeyedStore(it, root) { listOf("k") } }
            val report = root.hydrateAll()
            assertEquals(listOf("a", "b", "HaPlain", "k").sorted(), report.entries.map { it.node.name }.sorted())
            assertEquals(listOf(root.a, root.b, root.plain, k).map { it.lockOrderKey }, report.entries.map { it.store.lockOrderKey })
            assertTrue(report.isHealthy)
            assertEquals(
                Hydration.Hydrated,
                (report.entries.first { it.store === root.a }.outcome as HydrateAllReport.Outcome.Ran).hydration,
            )
            assertEquals(listOf("a"), root.a.items.value)
            assertEquals(listOf("b"), root.b.items.value)
            assertEquals(listOf("k"), k.items.value)
            assertEquals(listOf(root.plain), report.skipped.map { it.store })
            assertIs<HydrateAllReport.Outcome.NoHydrator>(report.skipped.single().outcome)
            assertEquals(emptyList<HydrateAllReport.Entry>(), report.failed)
        }

    @Test
    fun aggregatesFailuresWithoutThrowing() =
        runBlocking {
            val offline = IllegalStateException("offline")
            val root = HaRoot(remoteA = { throw offline })
            val report = root.hydrateAll()
            assertFalse(report.isHealthy)
            assertEquals(listOf(root.a), report.failed.map { it.store })
            val outcome = assertIs<HydrateAllReport.Outcome.Ran>(report.failed.single().outcome)
            assertEquals(Hydration.Failed(offline), outcome.hydration)
            assertEquals(listOf("b"), root.b.items.value, "the other leaf hydrated regardless")
        }

    @Test
    fun aThrowingSeedIsReportedAsFailedAndDoesNotStopTheRest() =
        runBlocking {
            val root = HaRoot()
            root.a.middlewares(
                object : com.vynatix.holdfast.Middleware<HaFeedStore>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<HaFeedStore>) = error("seed rejected")
                },
            )
            val report = root.hydrateAll()
            val failedA = assertIs<HydrateAllReport.Outcome.Ran>(report.entries.first { it.store === root.a }.outcome)
            val failure = assertIs<Hydration.Failed>(failedA.hydration)
            assertContains(failure.cause.message!!, "seed rejected")
            assertEquals(Hydration.Detached, root.a.hydration.current, "the seed never committed")
            assertEquals(listOf("b"), root.b.items.value)
        }

    @Test
    fun touchesOnlyTheSubtreeAndIsIdempotentAfterHydrated() =
        runBlocking {
            val root = HaRoot()
            val report = root.hydrateAll(root.other)
            assertEquals(listOf("HaPlain"), report.entries.map { it.node.name })
            assertEquals(Hydration.Detached, root.a.hydration.current)

            root.hydrateAll(root.feeds)
            assertEquals(1, root.a.baseRuns)
            val again = root.hydrateAll(root.feeds)
            assertEquals(1, root.a.baseRuns, "a hydrated leaf's hydrator does nothing")
            assertTrue(again.isHealthy)
            assertEquals(Hydration.Hydrated, (again.entries.first().outcome as HydrateAllReport.Outcome.Ran).hydration)
        }

    @Test
    fun withoutAwaitingItReportsThePhaseAfterTheSeed() =
        runBlocking {
            val gate = CompletableDeferred<List<String>>()
            val root = HaRoot(remoteA = { gate.await() })
            val report = root.hydrateAll(root.feeds, scope = this, awaitSettled = false)
            assertEquals(Hydration.Seeded, (report.entries.first { it.store === root.a }.outcome as HydrateAllReport.Outcome.Ran).hydration)
            assertEquals(listOf("seed"), root.a.items.value)
            gate.complete(listOf("late"))
            assertEquals(Hydration.Hydrated, root.a.hydration.awaitSettled())
            assertEquals(listOf("late"), root.a.items.value)
        }

    @Test
    fun insideAFrameBodyOrAnEntryItFailsBeforeTouchingAnyLeaf() =
        runBlocking {
            val root = HaRoot()
            val refusals = mutableListOf<Throwable?>()
            suspendAtomic(root.a, root.b) { refusals += runCatching { root.hydrateAll() }.exceptionOrNull() }.getOrThrow()
            root.b.suspendAction { refusals += runCatching { root.hydrateAll() }.exceptionOrNull() }.getOrThrow()
            root.plain.action { refusals += runCatching { runBlocking { root.hydrateAll() } }.exceptionOrNull() }.getOrThrow()
            atomic(root.plain) { refusals += runCatching { runBlocking { root.hydrateAll() } }.exceptionOrNull() }.getOrThrow()
            assertEquals(4, refusals.size)
            for (refusal in refusals) assertIs<IllegalStateException>(refusal)
            assertEquals(0, root.a.baseRuns, "no leaf was touched")
            assertEquals(Hydration.Detached, root.a.hydration.current)
            assertEquals(Hydration.Detached, root.b.hydration.current)
        }

    @Test
    fun distinguishesNoHydratorFromDisposed() =
        runBlocking {
            val root = HaRoot()
            // b's seed disposes the keyed store created after it (a higher key: driven after b).
            val victim = root.keyed.create("victim") { HaKeyedStore(it, root) { listOf("v") } }
            val disposing = HaRoot()
            val late = disposing.keyed.create("late") { HaKeyedStore(it, disposing) { listOf("l") } }
            disposing.plain.middlewares(
                object : com.vynatix.holdfast.Middleware<HaPlainStore>() {},
            )
            // Dispose the keyed store right after b's seed committed: from b's hydration observer.
            disposing.b.hydration.state effect { if (this == Hydration.Seeded) late.dispose() }
            val report = disposing.hydrateAll()
            assertIs<HydrateAllReport.Outcome.Disposed>(report.entries.first { it.store === late }.outcome)
            assertIs<HydrateAllReport.Outcome.NoHydrator>(report.entries.first { it.store === disposing.plain }.outcome)
            assertEquals(2, report.skipped.size)
            assertTrue(report.isHealthy)
            assertEquals(listOf("v"), root.hydrateAll().let { victim.items.value })
        }

    @Test
    fun cancellationPropagatesWhileRefreshJobsKeepRunning() =
        runBlocking {
            val gate = CompletableDeferred<List<String>>()
            val root = HaRoot(remoteA = { gate.await() })
            val refreshScope = this
            val caller =
                launch {
                    root.hydrateAll(scope = refreshScope)
                }
            yield()
            assertEquals(Hydration.Seeded, root.a.hydration.current, "seeded, awaiting the refresh")
            caller.cancel()
            caller.join()
            assertTrue(caller.isCancelled)
            gate.complete(listOf("kept running"))
            assertEquals(Hydration.Hydrated, root.a.hydration.awaitSettled())
            assertEquals(listOf("kept running"), root.a.items.value)
            assertEquals(Hydration.Hydrated, root.b.hydration.awaitSettled(), "b's refresh was launched before the cancel")
        }

    @Test
    fun throwsOnADisposedRootAndAForeignNode() =
        runBlocking {
            val root = HaRoot()
            val other = HaRoot()
            assertFailsWith<IllegalArgumentException> { root.hydrateAll(other.feeds) }
            root.dispose()
            val failure = assertFailsWith<IllegalStateException> { root.hydrateAll() }
            assertContains(failure.message!!, "disposed")
        }

    @Test
    fun theReportNamesNodesAndOutcomesNeverValues() =
        runBlocking {
            val root = HaRoot()
            val report = root.hydrateAll(root.feeds)
            val text = report.toString()
            assertContains(text, "Entry(a: Ran(Hydrated))")
            assertFalse("seed" in text || "[a]" in text)
        }
}
