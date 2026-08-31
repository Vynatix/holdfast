package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Account : Store<Account>() {
    val balance by state { 0 }
}

/**
 * Blocking `atomic()` took only each participant's `transactionLock` and never
 * its `AsyncSerializer`, so it was not mutually exclusive with an in-flight
 * `suspendAction` — the one lock that makes blocking `action` safe against
 * suspending peers. A frame could install a fresh root over a suspending
 * transaction, and since `suspendingOwner` relaxes `mutate`'s owner check, the
 * suspending body would then stage its writes into the frame's transaction.
 *
 * `atomic` was, in other words, less safe than plain `action`.
 */
class AtomicSerializationTest {
    @Test
    fun `atomic does not interleave with an in-flight suspendAction on a participant`() =
        runBlocking {
            val account = Account()
            val order = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()

            val suspending =
                async(Dispatchers.Default) {
                    account.suspendAction {
                        order += "suspend-start"
                        started.complete(Unit)
                        delay(80)
                        balance mutate 1
                        order += "suspend-end"
                    }
                }
            started.await()

            val frame =
                async(Dispatchers.Default) {
                    atomic(account) {
                        order += "frame-start"
                        account.action { balance update { it + 100 } }
                        order += "frame-end"
                    }
                }

            suspending.await()
            frame.await()

            assertTrue(
                order.indexOf("suspend-end") < order.indexOf("frame-start"),
                "atomic ran concurrently with a suspendAction on the same store: $order",
            )
            assertEquals(101, account.balance.value, "the frame must build on the suspending commit, not race it")
        }
}
