@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.internalTransactionLockFree
import com.vynatix.holdfast.reset
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class BlockingWaitStore : Store<BlockingWaitStore>() {
    val n by state { 0 }
}

private class BlockingWaitParent : Store<BlockingWaitParent>() {
    val n by state { 0 }
    val child by store { BlockingWaitStore() }
}

/**
 * Launch, on [Dispatchers.Default], a suspendAction on [store] that stages
 * `n = 5`, signals that it holds the store, parks for 50 ms, then stages
 * `n + 1` and commits: the store ends at 6 unless something writes after it.
 * Returns once the holder is parked.
 */
private suspend fun <S : Store<S>> CoroutineScope.parkHolder(
    store: S,
    write: S.(Int) -> Unit,
    read: S.() -> Int,
): Job {
    val parked = CompletableDeferred<Unit>()
    val holder =
        launch(Dispatchers.Default) {
            store.suspendAction {
                write(5)
                parked.complete(Unit)
                delay(50)
                write(read() + 1)
            }
        }
    parked.await()
    return holder
}

private suspend fun CoroutineScope.parkHolder(store: BlockingWaitStore): Job = parkHolder(store, { v -> n mutate v }, { n.value })

/**
 * What a blocking call beside a parked holder does, checked once the holder
 * has finished. On the JVM (as on Android and iOS, where the holder resumes on
 * another thread) it waits the holder out and commits on top of its 6:
 * [actual] reads [expectedOnSuccess]. On wasmJs, where the holder can only
 * resume once the call returns, it refuses with the #27 message naming
 * [heldStore] and leaves the holder's 6 standing; [retry], the same call made
 * now that the holder has finished, then commits and [actual] reads
 * [expectedOnSuccess].
 */
private fun <T> assertWaitedOrRefused(
    outcome: Result<TransactionResult<T>>,
    heldStore: String,
    actual: () -> Int,
    expectedOnSuccess: Int,
    retry: () -> TransactionResult<*>,
) {
    if (blockingWaitsCanEnd) {
        val result = outcome.getOrThrow()
        if (result is TransactionResult.Error) fail("blocking call returned Error: ${result.exception}")
        assertEquals(expectedOnSuccess, actual())
    } else {
        val refused = outcome.exceptionOrNull()
        assertIs<IllegalStateException>(refused, "expected the #27 refusal, got $outcome")
        val message = refused.message.orEmpty()
        assertContains(message, "single-threaded")
        assertContains(message, heldStore)
        assertEquals(6, actual(), "the refusal left the holder's commit standing")
        val again = retry()
        assertIs<TransactionResult.Success<*>>(again, "the same call once the holder has finished: $again")
        assertEquals(expectedOnSuccess, actual())
    }
}

/**
 * Issue #27: a blocking top-level entry beside a parked `suspendAction` spun
 * forever in `MutexSerializer.blockingAcquire` on wasmJs, freezing the event
 * loop the holder needed to resume on. Each test drives one entry that takes
 * the store through `holdSerialized`; a hang fails the run (and everything
 * registered after it). On wasmJs each must refuse at once and then work once
 * the holder has finished; on the JVM, the control, each must wait and commit
 * ([blockingWaitsCanEnd]).
 */
class BlockingWaitTest {
    // Precondition, without a blocking wait: the parked holder owns the
    // serializer, and it is free again once the holder has finished.
    @Test
    fun theSerializerIsTakenWhileASuspendActionIsParkedAndFreeAfter() =
        runTest {
            val store = BlockingWaitStore()
            val holder = parkHolder(store)
            val serializer = assertNotNull(store.asyncSerializer, "suspendAction installs the serializer")
            assertFalse(serializer.tryBlockingAcquire(), "the parked holder owns the serializer")
            holder.join()
            assertTrue(serializer.tryBlockingAcquire(), "free once the holder has finished")
            serializer.blockingRelease()
            assertEquals(6, store.n.value)
        }

    // Once the holder has finished, the same blocking call works everywhere.
    @Test
    fun aBlockingActionAfterTheHolderFinishedCommits() =
        runTest {
            val store = BlockingWaitStore()
            parkHolder(store).join()
            assertIs<TransactionResult.Success<*>>(store action { n update { it * 10 } })
            assertEquals(60, store.n.value)
        }

    @Test
    fun aBlockingActionBesideAParkedSuspendAction() =
        runTest {
            val store = BlockingWaitStore()
            val holder = parkHolder(store)
            val outcome = runCatching { store action { n update { it * 10 } } }
            holder.join()
            assertWaitedOrRefused(outcome, "BlockingWaitStore", { store.n.value }, 60) {
                store action { n update { it * 10 } }
            }
        }

    // reset() runs through action: waited out, it lands on the initializer's 0.
    @Test
    fun aResetBesideAParkedSuspendAction() =
        runTest {
            val store = BlockingWaitStore()
            val holder = parkHolder(store)
            val outcome = runCatching { store.reset() }
            holder.join()
            assertWaitedOrRefused(outcome, "BlockingWaitStore", { store.n.value }, 0) { store.reset() }
        }

    // restore() runs through action: waited out, it lands on the snapshot's 3.
    @Test
    fun aRestoreBesideAParkedSuspendAction() =
        runTest {
            val store = BlockingWaitStore()
            store action { n mutate 3 }
            val snapshot = store.snapshot()
            val holder = parkHolder(store)
            val outcome = runCatching { store.restore(snapshot) }
            holder.join()
            assertWaitedOrRefused(outcome, "BlockingWaitStore", { store.n.value }, 3) { store.restore(snapshot) }
        }

    // atomic(...) takes each participant through holdSerialized, in lock
    // order: `other`, built first, is taken before the held `store`, so a
    // refusal has a participant to unwind.
    @Test
    fun anAtomicBesideAParkedSuspendAction() =
        runTest {
            val other = BlockingWaitStore()
            val store = BlockingWaitStore()
            val holder = parkHolder(store)
            val frame = {
                atomic(other, store) {
                    other action { n mutate 1 }
                    store action { n update { it * 10 } }
                }
            }
            val outcome = runCatching { frame() }
            holder.join()
            if (!blockingWaitsCanEnd) {
                // The refusal released `other` untouched.
                assertEquals(0, other.n.value)
                assertTrue(other.internalTransactionLockFree(), "the refused frame released other's lock")
                assertNull(other.activeTransaction, "the refused frame left no transaction on other")
            }
            assertWaitedOrRefused(outcome, "BlockingWaitStore", { store.n.value }, 60, frame)
            assertEquals(1, other.n.value, "the frame committed whole")
        }

    // The holder itself makes the blocking call: README's first known issue.
    // It still spins on the JVM, so this runs on wasmJs only, where it is
    // refused with advice for this case, and the body's commit stands.
    @Test
    fun aBlockingActionInsideTheHoldersOwnBodyIsRefused() =
        runTest {
            if (blockingWaitsCanEnd) return@runTest
            val store = BlockingWaitStore()
            var refused: Throwable? = null
            val result =
                store.suspendAction {
                    n mutate 3
                    refused = runCatching { store action { n mutate 4 } }.exceptionOrNull()
                }
            assertIs<TransactionResult.Success<*>>(result)
            assertEquals(3, store.n.value)
            val message = assertIs<IllegalStateException>(refused).message.orEmpty()
            assertContains(message, "Inside that holder's own body, write with mutate/update")
        }

    // tree.reset() opens one atomic over the subtree's stores.
    @Test
    fun aTreeResetBesideAParkedSuspendAction() =
        runTest {
            val parent = BlockingWaitParent()
            assertIs<BlockingWaitStore>(parent.child) // materialize the child: two stores in the subtree
            val holder = parkHolder(parent, { v -> n mutate v }, { n.value })
            val outcome = runCatching { parent.tree.reset() }
            holder.join()
            assertWaitedOrRefused(outcome, "BlockingWaitParent", { parent.n.value }, 0) { parent.tree.reset() }
        }
}
