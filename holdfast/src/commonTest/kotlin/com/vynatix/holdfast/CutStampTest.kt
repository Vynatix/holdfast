@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CsStore : Store<CsStore>() {
    val n by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

/**
 * A capture's cut stamp (`CutStamp`) tells a later cut of the same store
 * whether it may reuse the capture unchanged: same scope and schema, the same
 * states listed (by identity, without holding them), every counter unmoved,
 * and the same keyed state families listed — a family declared since, even
 * with no entry yet, lists the store differently.
 */
class CutStampTest {
    @Test
    fun anUnmovedStoreIsReusedByReference() {
        val store = CsStore()
        val first = captureConsistent(listOf(store))[0]
        val stats = CaptureStats()
        val second = captureConsistent(listOf(store), SnapshotScope.All, listOf(first), stats)[0]
        assertSame(first, second)
        assertEquals(1, stats.reused)
        assertEquals(0, stats.captured)
    }

    @Test
    fun aWriteMovesTheStamp() {
        val store = CsStore()
        val first = captureConsistent(listOf(store))[0]
        store action { n mutate 1 }
        val stats = CaptureStats()
        val second = captureConsistent(listOf(store), SnapshotScope.All, listOf(first), stats)[0]
        assertNotSame(first, second)
        assertEquals(1, second[store.n])
        assertEquals(1, stats.captured)
        assertEquals(0, stats.reused)
    }

    @Test
    fun aCaptureInAnotherScopeIsNeverReused() {
        val store = CsStore()
        val first = captureConsistent(listOf(store), SnapshotScope.All)[0]
        val stats = CaptureStats()
        val second = captureConsistent(listOf(store), SnapshotScope.Raw, listOf(first), stats)[0]
        assertNotSame(first, second)
        assertEquals(1, stats.captured)
    }

    @Test
    fun aReMaterializedStateIsNotReused() {
        val store = CsStore()
        val first = captureConsistent(listOf(store))[0]
        store.removeState("n")
        val stats = CaptureStats()
        val second = captureConsistent(listOf(store), SnapshotScope.All, listOf(first), stats)[0]
        assertNotSame(first, second, "a dropped state re-materializes as a new state instance, with fresh counters")
        assertEquals(1, stats.captured)
    }

    @Test
    fun aFamilyDeclaredWithNoEntriesSinceTheCaptureIsNotReused() {
        val store = CsStore()
        val first = captureConsistent(listOf(store))[0]
        assertEquals(setOf("n", "docs"), first.stateNames)
        val late: KeyedState<String, Int> by store.keyedState { 0 }
        assertTrue(late.entries.isEmpty(), "declared, no entry yet: nothing to list among the states")
        val stats = CaptureStats()
        val second = captureConsistent(listOf(store), SnapshotScope.All, listOf(first), stats)[0]
        assertNotSame(first, second, "the store lists a family it did not before")
        assertEquals(0, stats.reused)
        assertContains(second.stateNames, "late")
        assertEquals(store.snapshot().stateNames, second.stateNames)
        assertEquals(store.snapshot(), second)
        assertEquals(emptySet(), second.keysOf(late))
    }
}
