@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.effect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

private class DispatchStore : Store<DispatchStore>() {
    val count by state { 0 }
}

private class DispatchRollback : RuntimeException("boom")

/**
 * Issue #29: on iOS/wasmJs `suspendAtomic` started its body through
 * `withContext(frame + SlotBracketingInterceptor)`, which (a new interceptor)
 * dispatched the body after every participant was taken and its root
 * installed, so a coroutine queued earlier ran inside the frame even when the
 * body never suspends. The body now starts undispatched on every platform, as
 * it always did on the JVM.
 */
class SuspendAtomicDispatchTest {
    // The gap itself: a body that never suspends runs before a coroutine queued
    // earlier, and that coroutine finds the store idle.
    @Test
    fun aNonSuspendingFrameBodyRunsBeforeACoroutineQueuedEarlier() =
        runTest {
            val store = DispatchStore()
            val order = mutableListOf<String>()
            var queuedSawTransaction: Any? = "not run"
            var queuedSawOwner: Any? = "not run"
            launch {
                order += "queued"
                queuedSawTransaction = store.activeTransaction
                queuedSawOwner = store.suspendingOwner
            }
            val frame = suspendAtomic(store) { order += "body" }
            assertIs<TransactionResult.Success<*>>(frame)
            advanceUntilIdle()
            assertEquals(listOf("body", "queued"), order)
            assertNull(queuedSawTransaction)
            assertNull(queuedSawOwner)
        }

    // Consequence 1 (claim): the queued bare write joins the frame root and is
    // rolled back with it.
    @Test
    fun aWriteQueuedBeforeARolledBackSuspendAtomicIsNotSwallowedByIt() =
        runTest {
            val store = DispatchStore()
            launch { store { count mutate 7 } }
            val frame =
                suspendAtomic(store) {
                    store { count mutate 1 }
                    throw DispatchRollback()
                }
            assertIs<TransactionResult.Error>(frame)
            assertIs<DispatchRollback>(frame.exception)
            advanceUntilIdle()
            assertEquals(7, store.count.value)
        }

    // Consequence 1b: with a committing frame the queued write is overwritten
    // inside the frame instead of landing after it; an observer sees 1 then 7.
    @Test
    fun aWriteQueuedBeforeACommittingSuspendAtomicLandsAfterIt() =
        runTest {
            val store = DispatchStore()
            val seen = mutableListOf<Int>()
            val subscription = store.count effect { seen += this }
            launch { store { count mutate 7 } }
            val frame = suspendAtomic(store) { store { count mutate 1 } }
            assertIs<TransactionResult.Success<*>>(frame)
            advanceUntilIdle()
            subscription.dispose()
            assertEquals(listOf(0, 1, 7), seen)
            assertEquals(7, store.count.value)
        }

    // Control: suspendAction starts its body undispatched on every platform
    // (only the settle-scope carrier sits in front of it), so the same shape
    // keeps the queued write.
    @Test
    fun aWriteQueuedBeforeARolledBackSuspendActionIsNotSwallowedByIt() =
        runTest {
            val store = DispatchStore()
            launch { store { count mutate 7 } }
            val result =
                store.suspendAction {
                    count mutate 1
                    throw DispatchRollback()
                }
            assertIs<TransactionResult.Error>(result)
            advanceUntilIdle()
            assertEquals(7, store.count.value)
        }

    // Control: once the body REALLY suspends, a queued bare write joins the
    // frame on every platform (documented: Store.stagesInto, "not isolation").
    @Test
    fun aWriteQueuedBeforeASuspendingFrameBodyJoinsItOnEveryPlatform() =
        runTest {
            val store = DispatchStore()
            launch { store { count mutate 7 } }
            val frame =
                suspendAtomic(store) {
                    yield()
                    store { count mutate 1 }
                    throw DispatchRollback()
                }
            assertIs<TransactionResult.Error>(frame)
            advanceUntilIdle()
            assertEquals(0, store.count.value)
        }

    // The marker stays on the body's first (undispatched) segment and on its
    // resumptions: an unenrolled write is refused before and after a
    // suspension, and the frame rolls back.
    @Test
    fun enrollmentIsEnforcedBeforeAndAfterTheBodySuspends() =
        runTest {
            val enrolled = DispatchStore()
            val outsider = DispatchStore()
            assertFailsWith<UnenrolledStoreException> {
                suspendAtomic(enrolled) {
                    enrolled { count mutate 1 }
                    outsider { count mutate 5 }
                }
            }
            assertFailsWith<UnenrolledStoreException> {
                suspendAtomic(enrolled) {
                    enrolled { count mutate 2 }
                    yield()
                    outsider { count mutate 6 }
                }
            }
            assertEquals(0, enrolled.count.value)
            assertEquals(0, outsider.count.value)
            // The marker is popped once the frame returns.
            outsider action { count mutate 9 }
            assertEquals(9, outsider.count.value)
        }

    // The fix must keep nested frames reusing the outer frame's hold (the inner
    // frame finds the outer one in its context) and surface the body's exception.
    @Test
    fun aNestedFrameJoinsTheOuterAndTheBodyExceptionIsKept() =
        runTest {
            val first = DispatchStore()
            val second = DispatchStore()
            val outer =
                suspendAtomic(first, second) {
                    first { count mutate 1 }
                    val inner =
                        suspendAtomic(first, second) {
                            second { count mutate 2 }
                            yield()
                            first { count mutate 3 }
                        }
                    assertIs<TransactionResult.Success<*>>(inner)
                    throw DispatchRollback()
                }
            assertIs<TransactionResult.Error>(outer)
            assertIs<DispatchRollback>(outer.exception)
            assertEquals("boom", outer.exception.message)
            assertEquals(0, first.count.value)
            assertEquals(0, second.count.value)
            val again = suspendAtomic(first, second) { first { count mutate 4 } }
            assertIs<TransactionResult.Success<*>>(again)
            assertEquals(4, first.count.value)
        }

    // Consequence 2 (claim): a blocking action queued before the frame. On
    // wasmJs today it lands in the frame's gap and spins in
    // MutexSerializer.blockingAcquire (C1). Run separately (it hangs).
    @Test
    fun aBlockingActionQueuedBeforeASuspendAtomicRunsAfterTheFrame() =
        runTest {
            val store = DispatchStore()
            val results = mutableListOf<TransactionResult<Unit>>()
            launch { results += store action { count update { it + 1 } } }
            val frame = suspendAtomic(store) { store { count mutate 10 } }
            assertIs<TransactionResult.Success<*>>(frame)
            advanceUntilIdle()
            assertIs<TransactionResult.Success<*>>(results.single())
            assertEquals(11, store.count.value)
        }
}
