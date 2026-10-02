@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TrrLeafStore : Store<TrrLeafStore>() {
    val n by state { 0 }
}

private open class TrrKeyedStore : Store<TrrKeyedStore>() {
    val n by state { 0 }
}

private class TrrSubKeyedStore : TrrKeyedStore()

/** A keyed store whose one state's initializer throws while [failing] is set. */
private class TrrFlakyStore(
    private val failing: () -> Boolean,
) : Store<TrrFlakyStore>() {
    val n by state {
        check(!failing()) { "initializer refused" }
        7
    }
}

/**
 * Each declaration consults a per-test hook and records the stores its
 * runs built, so a test can tell what was disposed.
 */
private class TrrParent : Store<TrrParent>() {
    var childHook: () -> TrrLeafStore = { TrrLeafStore() }
    var groupHook: () -> List<Store<*>> = { listOf(TrrKeyedStore()) }
    var keyedHook: (String) -> TrrKeyedStore = { TrrKeyedStore() }

    var flakyFailing = false

    val child by store { childHook() }
    val group by stores { groupHook() }
    val pinnedGroup by stores(names = mapOf(TrrKeyedStore::class to "pinned")) { groupHook() }
    val keyed by stores<String, TrrKeyedStore> { keyedHook(it) }
    val flaky by stores<String, TrrFlakyStore> { TrrFlakyStore { flakyFailing } }

    /** Declared as `stores<String, TrrSubKeyedStore>` while its factory builds plain [TrrKeyedStore]s. */
    val built = ArrayList<TrrKeyedStore>()

    @Suppress("UNCHECKED_CAST")
    val wrongClass by keyedDeclaration(String::class, TrrSubKeyedStore::class as KClass<TrrKeyedStore>, null) {
        TrrKeyedStore().also { built += it }
    }
}

/** Whether this tree lists [store] (by its node). */
private fun TreeSnapshot.lists(store: Store<*>): Boolean = this[store.treeAttachment().node] != null

/** Regression tests for the PR #25 review fixes that need no threads (the threaded ones are JVM tests). */
class TreeReviewRegressionTest {
    // Fix 3: churn before the first read leaves nothing behind.

    @Test
    fun keyedChurnBeforeTheValueIsSeededLeavesNoRecordedEdges() {
        val parent = TrrParent()
        val tree = parent.tree
        tree.internalHost() // Builds the value machinery (its listener) without seeding the node.
        val value = (tree as StoreTreeImpl).treeValueOrNull!!
        val churned =
            List(500) { i ->
                val store = parent.keyed.create("k$i")
                store.treeAttachment().node.also { store.dispose() }
            }
        assertEquals(0, value.pendingEdgeCount, "a leave before the seed only cancels its store's pending join")
        val first = tree.value
        assertTrue(first.lists(parent))
        assertTrue(churned.none { first[it] != null }, "the first read lists only the live stores")
    }

    // Fix 4: structure alone builds no host.

    @Test
    fun structuralUseBuildsNoHostAndALaterFirstReadFollowsEarlierAttaches() {
        val parent = TrrParent()
        val tree = parent.tree
        val impl = tree as StoreTreeImpl
        tree.snapshot()
        tree.stores()
        tree.children
        tree.middlewares(object : TreeMiddleware() {})
        val early = parent.keyed.create("early")
        assertNull(impl.treeValueOrNull, "snapshot, stores, children and middlewares build no value machinery")
        assertTrue(
            parent
                .treeAttachment()
                .registry.listeners
                .isEmpty(),
            "and register no membership listener",
        )
        assertEquals(0L, tree.internalSettleCount)
        val first = tree.value
        assertNotNull(impl.treeValueOrNull)
        assertTrue(first.lists(early), "the first read lists a store attached before it")
        early action { n mutate 3 }
        assertEquals(3, tree.value[early.n], "and follows it")
        val late = parent.keyed.create("late")
        assertTrue(tree.value.lists(late))
    }

    // Fix 7: a failed recompute never leaves a stale value behind.

    @Test
    fun aReadAfterAFailedRecomputeIsNotStale() {
        val parent = TrrParent()
        val reported = ArrayList<Throwable>()
        parent.uncaughtObserverHandler = { reported += it }
        val tree = parent.tree
        assertTrue(tree.value.lists(parent))
        parent.flakyFailing = true
        val flaky = parent.flaky.create("f") // Its attach recomputes; the capture first-reads `n` and throws.
        assertTrue(reported.any { "initializer refused" in it.message.orEmpty() }, "the failed recompute is reported: $reported")
        parent.flakyFailing = false
        val after = tree.value
        assertTrue(after.lists(flaky), "the read after the failure sees the store that joined")
        assertEquals(7, after[flaky.n])
    }

    // Fix 6: what a run built is disposed when its attach fails after it returned.

    @Test
    fun aKeyedStoreOfTheWrongClassIsDisposed() {
        val parent = TrrParent()
        assertFailsWith<IllegalStateException> { parent.wrongClass.create("w") }
        assertTrue(parent.built.single().isDisposed, "the store the factory built is disposed")
    }

    @Test
    fun aKeyedFactoryWhoseOwnerDisposesMidRunHasItsFreshStoreDisposedButNeverAnOlderOne() {
        val parent = TrrParent()
        var fresh: TrrKeyedStore? = null
        parent.keyedHook = {
            parent.dispose()
            TrrKeyedStore().also { fresh = it }
        }
        assertFailsWith<IllegalStateException> { parent.keyed.create("x") }
        assertTrue(assertNotNull(fresh).isDisposed, "a store built during the run is disposed")

        val older = TrrKeyedStore()
        val parent2 = TrrParent()
        parent2.keyedHook = {
            parent2.dispose()
            older
        }
        assertFailsWith<IllegalStateException> { parent2.keyed.getOrCreate("x") }
        assertFalse(older.isDisposed, "a store that existed before the run is never disposed")
    }

    @Test
    fun aKeyedFactoryReturningAStoreAnotherParentHoldsDisposesNothing() {
        val parent = TrrParent()
        val other = TrrParent()
        var foreign: TrrKeyedStore? = null
        // Built during the run, but attached under `other`: it is that parent's, not an orphan.
        parent.keyedHook = { id -> other.keyed.create(id).also { foreign = it } }
        val error = assertFailsWith<IllegalStateException> { parent.keyed.create("f") }
        assertTrue("already belongs to TrrParent/keyed" in error.message!!, error.message)
        assertFalse(assertNotNull(foreign).isDisposed)
        assertSame(foreign, other.keyed["f"])
    }

    @Test
    fun aChildWhoseOwnerDisposesMidRunHasItsFreshStoreDisposedButNeverAnOlderOne() {
        val parent = TrrParent()
        var fresh: TrrLeafStore? = null
        parent.childHook = {
            parent.dispose()
            TrrLeafStore().also { fresh = it }
        }
        assertFailsWith<IllegalStateException> { parent.child }
        assertTrue(assertNotNull(fresh).isDisposed, "a store built during the run is disposed")

        val older = TrrLeafStore()
        val parent2 = TrrParent()
        parent2.childHook = {
            parent2.dispose()
            older
        }
        assertFailsWith<IllegalStateException> { parent2.child }
        assertFalse(older.isDisposed, "a store that existed before the run is never disposed")
    }

    @Test
    fun aRefusedGroupDisposesTheStoresItsRunBuiltAndKeepsOlderOnes() {
        val parent = TrrParent()
        val built = ArrayList<Store<*>>()
        parent.groupHook = { listOf(TrrLeafStore(), TrrLeafStore()).also { built += it } }
        val duplicate = assertFailsWith<IllegalArgumentException> { parent.group }
        assertTrue("two leaves would be named 'TrrLeaf'" in duplicate.message!!, duplicate.message)
        assertTrue(built.all { it.isDisposed }, "both stores the run built are disposed")

        val older = TrrLeafStore()
        val parent2 = TrrParent()
        val built2 = ArrayList<Store<*>>()
        // The pin names TrrKeyedStore, which this listing lacks: refused after the lambda returned.
        parent2.groupHook = { listOf(older, TrrLeafStore().also { built2 += it }) }
        assertFailsWith<IllegalArgumentException> { parent2.pinnedGroup }
        assertTrue(built2.single().isDisposed, "the fresh member is disposed")
        assertFalse(older.isDisposed, "the older member is not")
        // The declaration stays retryable.
        parent2.groupHook = { listOf(older, TrrKeyedStore()) }
        assertEquals(listOf("TrrLeaf", "pinned"), parent2.pinnedGroup.leaves.map { it.name })
    }
}
