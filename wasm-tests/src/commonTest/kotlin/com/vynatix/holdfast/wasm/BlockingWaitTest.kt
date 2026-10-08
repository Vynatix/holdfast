@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAction
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * What every blocking call beside a parked holder may do: wait the holder out
 * and commit on top of its 6 (JVM, Android, iOS: the holder resumes on another
 * thread), or refuse with an IllegalStateException and leave the holder's 6
 * standing (wasmJs, where the holder can only resume once the call returns).
 */
private fun <T> assertWaitedOrRefused(
    outcome: Result<TransactionResult<T>>,
    actual: Int,
    expectedOnSuccess: Int,
) {
    val refused = outcome.exceptionOrNull()
    if (refused == null) {
        val result = outcome.getOrThrow()
        if (result is TransactionResult.Error) fail("blocking call returned Error: ${result.exception}")
        assertEquals(expectedOnSuccess, actual)
    } else {
        assertIs<IllegalStateException>(refused, "unexpected throw: $refused")
        assertEquals(6, actual)
    }
}

/**
 * Issue #27: a blocking top-level entry beside a parked `suspendAction` spun
 * forever in `MutexSerializer.blockingAcquire` on wasmJs, freezing the event
 * loop the holder needed to resume on. Each test drives one entry that takes
 * the store through `holdSerialized`; a hang fails the run (and everything
 * registered after it), so these tests pass only once wasmJs refuses instead.
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
            assertWaitedOrRefused(outcome, store.n.value, 60)
        }

    // reset() runs through action: waited out, it lands on the initializer's 0.
    @Test
    fun aResetBesideAParkedSuspendAction() =
        runTest {
            val store = BlockingWaitStore()
            val holder = parkHolder(store)
            val outcome = runCatching { store.reset() }
            holder.join()
            assertWaitedOrRefused(outcome, store.n.value, 0)
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
            assertWaitedOrRefused(outcome, store.n.value, 3)
        }

    // atomic(...) takes each participant through holdSerialized.
    @Test
    fun anAtomicBesideAParkedSuspendAction() =
        runTest {
            val store = BlockingWaitStore()
            val other = BlockingWaitStore()
            val holder = parkHolder(store)
            val outcome =
                runCatching {
                    atomic(other, store) {
                        other action { n mutate 1 }
                        store action { n update { it * 10 } }
                    }
                }
            holder.join()
            assertWaitedOrRefused(outcome, store.n.value, 60)
            // The frame either committed whole or never touched `other`.
            assertEquals(if (outcome.isSuccess) 1 else 0, other.n.value)
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
            assertWaitedOrRefused(outcome, parent.n.value, 0)
        }
}
