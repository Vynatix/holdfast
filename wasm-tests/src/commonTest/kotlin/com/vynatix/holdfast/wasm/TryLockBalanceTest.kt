@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalTransactionLockFree
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.tree.keyed
import com.vynatix.holdfast.tree.store
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val doubled by derivedState(count) { count.value * 2 }
}

private class DocsStore : Store<DocsStore>() {
    val trigger by state { 0 }
    val docs by keyedState<String, Int> { key -> key.length }
}

private class InitializerFailure : RuntimeException("initializer failed")

private class ThrowingStore : Store<ThrowingStore>() {
    val broken by state<Int> { throw InitializerFailure() }
}

private class ParentStore : Store<ParentStore>() {
    val child by store { CounterStore() }
    val rooms by keyed<String, CounterStore> { CounterStore() }
}

/**
 * Records what [this] reports through its uncaughtObserverHandler (instead of
 * logging it). A deferred eviction reports a failure to release the store after
 * its commit; a derived recompute's does not get here, since the settle loop or
 * the post-commit drain drops it (see the derived tests).
 */
private fun Store<*>.recordUncaughtFailures(): MutableList<Throwable> =
    mutableListOf<Throwable>().also { failures -> uncaughtObserverHandler = { failures += it } }

private fun assertNoneReported(failures: List<Throwable>) =
    assertTrue(failures.isEmpty(), "reported through uncaughtObserverHandler: ${failures.map { it.message }}")

/**
 * Issue #26: atomicfu's js/wasm `SynchronousMutex.tryLock()` returns `true`
 * without counting the hold, so the `unlock()` paired with it threw "Mutex
 * already unlocked". Each test drives one path that pairs the two, through
 * the surface a consumer uses; they pass on the JVM either way and are the
 * wasmJs regression.
 */
class TryLockBalanceTest {
    // InitializerGraph.claim → latch.tryLockCounted(), released by InitializerGraph.release.
    @Test
    fun firstReadOfADeclaredStateMaterializesIt() {
        val store = CounterStore()
        assertEquals(0, store.count.value)
        assertEquals(0, store.count.value)
    }

    // The release runs in hold's finally: an unbalanced unlock there replaced
    // the initializer's own exception with "Mutex already unlocked".
    @Test
    fun aThrowingInitializerSurfacesItsOwnExceptionAndStaysRetryable() {
        val store = ThrowingStore()
        assertFailsWith<InitializerFailure> { store.broken.value }
        assertFailsWith<InitializerFailure> { store.broken.value }
    }

    // StoreLock.tryAcquireIfUnheld → tryAcquire → mutex.tryLockCounted(), then release().
    @Test
    fun theTransactionLockProbeIsBalanced() {
        val store = CounterStore()
        assertTrue(store.internalTransactionLockFree())
        assertTrue(store.internalTransactionLockFree())
        store action { count mutate 1 }
        assertEquals(1, store.count.value)
    }

    // A derived state's recompute commits through tryTopLevelAction → StoreLock.tryAcquire.
    // Its unbalanced release came after the commit and the settle loop drops a
    // task's throw (SettleScope.settle), so this guards the recompute only.
    @Test
    fun aDerivedStateRecomputesAfterACommit() {
        val store = CounterStore()
        val failures = store.recordUncaughtFailures()
        assertEquals(0, store.doubled.value)
        store action { count mutate 3 }
        assertEquals(6, store.doubled.value)
        store action { count mutate 4 }
        assertEquals(8, store.doubled.value)
        assertNoneReported(failures)
    }

    // Same path for a legacy derived, queued through postCommit and run by the
    // action's post-commit drain, whose runCatching (PostCommitQueue.drain)
    // drops the throw the same way: this guards the recompute only.
    @Test
    fun aLegacyDerivedRecomputesAfterACommit() {
        val store = CounterStore()
        val failures = store.recordUncaughtFailures()
        val (plusHundred, subscription) = store.derived(store.count) { count.value + 100 }
        assertEquals(100, plusHundred.value)
        store action { count mutate 7 }
        assertEquals(107, plusHundred.value)
        subscription.dispose()
        assertNoneReported(failures)
    }

    // An eviction from the store's own commit fanout is deferred (D16) and
    // runs through tryTopLevelAction → StoreLock.tryAcquire.
    @Test
    fun anEvictionFromTheCommitFanoutRunsDeferred() {
        val store = DocsStore()
        val failures = store.recordUncaughtFailures()
        store.action { docs["abc"] mutate 42 }.getOrThrow()
        assertEquals(42, store.docs["abc"].value)
        // effect fires once at once (trigger is 0 then), then in each commit's fanout.
        val subscription = store.trigger effect { if (this == 1) store.docs.evict("abc") }
        store action { trigger mutate 1 }
        subscription.dispose()
        assertEquals(3, store.docs["abc"].value)
        assertNoneReported(failures)
    }

    // A tree child's first read claims its entry with ChildEntry.attachLock.tryAcquire().
    @Test
    fun aTreeChildAttachesOnFirstRead() {
        val parent = ParentStore()
        val child = parent.child
        assertSame(child, parent.child)
        child action { count mutate 2 }
        assertEquals(4, child.doubled.value)
    }

    // A keyed branch's create claims its key with constructionLock.tryAcquire().
    @Test
    fun aKeyedBranchCreatesAndFindsItsStore() {
        val parent = ParentStore()
        val room = parent.rooms.getOrCreate("a")
        assertSame(room, parent.rooms.getOrCreate("a"))
        assertSame(room, parent.rooms["a"])
        assertEquals(setOf("a"), parent.rooms.entries().keys)
    }

    // suspendAction waits out lock-only holders through internalTransactionLockFree().
    @Test
    fun aSuspendActionCommits() =
        runTest {
            val store = CounterStore()
            val failures = store.recordUncaughtFailures()
            store.suspendAction { count mutate 5 }
            assertEquals(5, store.count.value)
            assertEquals(10, store.doubled.value)
            assertNoneReported(failures)
        }

    // Each newly held suspendAtomic participant waits out lock-only holders the same way.
    @Test
    fun aSuspendAtomicCommitsEveryParticipant() =
        runTest {
            val first = CounterStore()
            val second = CounterStore()
            val firstFailures = first.recordUncaughtFailures()
            val secondFailures = second.recordUncaughtFailures()
            suspendAtomic(first, second) {
                first { count mutate 1 }
                second { count mutate 2 }
            }
            assertEquals(1, first.count.value)
            assertEquals(4, second.doubled.value)
            assertNoneReported(firstFailures + secondFailures)
        }
}
