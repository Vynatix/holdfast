package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class DispatchCommonStore : Store<DispatchCommonStore>() {
    val count by state { 0 }
}

private class DispatchCommonRollback : RuntimeException("boom")

/**
 * Issue #29, in common code so the iOS simulator runs it too (`:wasm-tests`
 * covers wasmJs and has no iOS target): `suspendAtomic` starts its body
 * undispatched, so under `runTest`'s single thread a coroutine queued before
 * the frame runs after the frame, never inside it. On iOS and wasmJs the body
 * used to be dispatched through the frame marker's carrier after every
 * participant was taken, so the queued coroutine ran in that gap.
 */
class SuspendAtomicDispatchCommonTest {
    @Test fun aNonSuspendingFrameBodyRunsBeforeACoroutineQueuedEarlier() =
        runTest {
            val store = DispatchCommonStore()
            val order = mutableListOf<String>()
            launch { order += "queued" }
            val frame = suspendAtomic(store) { order += "body" }
            assertIs<TransactionResult.Success<*>>(frame)
            advanceUntilIdle()
            assertEquals(listOf("body", "queued"), order)
        }

    // In the gap the queued bare write joined the frame's root and was rolled
    // back with it.
    @Test fun aWriteQueuedBeforeARolledBackSuspendAtomicIsNotSwallowedByIt() =
        runTest {
            val store = DispatchCommonStore()
            launch { store { count mutate 7 } }
            val frame =
                suspendAtomic(store) {
                    store { count mutate 1 }
                    throw DispatchCommonRollback()
                }
            assertIs<TransactionResult.Error>(frame)
            advanceUntilIdle()
            assertEquals(7, store.count.value)
        }

    // In the gap the queued write was overwritten by the committing frame, so
    // an observer never saw it.
    @Test fun aWriteQueuedBeforeACommittingSuspendAtomicLandsAfterIt() =
        runTest {
            val store = DispatchCommonStore()
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
}
