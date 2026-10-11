package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class SuspendingUpdateVault : Store<SuspendingUpdateVault>() {
    val n by state { 0 }
}

class UpdateInsideSuspendingBodyTest {
    @Test
    fun updateInsideSuspendActionAfterDispatcherHopStagesWithoutDeadlock() =
        runBlocking {
            val v = SuspendingUpdateVault()
            val r =
                v.suspendAction {
                    n mutate 1
                    withContext(Dispatchers.Default) {
                        // A dispatcher hop away from the thread that opened
                        // the suspending transaction: `update` must stage into
                        // that transaction (its suspending owner is set) —
                        // never take the blocking one-shot path against the
                        // store's own suspend mutex (issue #31's refusal would
                        // fire; a mutex wait would deadlock). The read side
                        // stays thread-id-only (issue 004's deliberate
                        // asymmetry): off the opening thread it reads the
                        // committed value, so the staged write REPLACES the
                        // body's pending one (GUIDE §8.2).
                        n update { it + 10 }
                    }
                    assertEquals(10, n.value, "the update staged into the suspending transaction, replacing the pending 1")
                    // Back on the opening thread the read sees the pending
                    // value (read-your-own-writes): 10 → 20.
                    n update { it * 2 }
                }
            assertIs<TransactionResult.Success<*>>(r)
            // One commit: 0 → (1 staged, replaced) → 10 → 20.
            assertEquals(20, v.n.value)
        }
}
