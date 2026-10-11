package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.testing.matcher.shouldFireInOrder
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

private class CountVault : Store<CountVault>() {
    val count by state { 0 }
}

class StoreHandleActionTest {
    @Test
    fun actionCommitsAndReturnsSuccess() =
        storeTest {
            val ctr = track(CountVault())
            val result = ctr.action { count mutate 5 }
            assertIs<TransactionResult.Success<*>>(result)
            assertEquals(5, ctr.read { count.value })
        }

    @Test
    fun actionPropagatesThrowAsError() =
        storeTest {
            val ctr = track(CountVault())
            val ise = IllegalStateException("boom")
            val result = ctr.action<Unit> { throw ise }
            assertIs<TransactionResult.Error>(result)
            assertSame(ise, result.exception)
            // Strict scope-exit guard requires the error to be acknowledged.
            ctr.consumeAllPendingErrors()
        }

    @Test
    fun suspendActionCommitsAndReturnsSuccess() =
        storeTest {
            val ctr = track(CountVault())
            val result =
                ctr.suspendAction {
                    delay(0)
                    count mutate 7
                    count.value
                }
            assertIs<TransactionResult.Success<*>>(result)
            assertEquals(7, ctr.read { count.value })
        }

    @Test
    fun suspendActionPropagatesThrowAsError() =
        storeTest {
            val ctr = track(CountVault())
            val iae = IllegalArgumentException("nope")
            val result = ctr.suspendAction<Unit> { throw iae }
            assertIs<TransactionResult.Error>(result)
            assertSame(iae, result.exception)
            // Strict scope-exit guard requires the error to be acknowledged.
            ctr.consumeAllPendingErrors()
        }

    @Test
    fun suspendActionFeedsTheTimeline() =
        storeTest {
            val ctr = track(CountVault())
            val result =
                ctr.suspendAction {
                    delay(0)
                    count mutate 3
                    count.value
                }
            assertIs<TransactionResult.Success<*>>(result)
            // suspendAction runs the middleware chain (concentric-ring order,
            // matching the blocking path), so the recorder sees the suspending
            // transaction like any other: started + emission + committed. This
            // pins that a coroutine-first test's timeline is NOT inexplicably
            // empty (the old "middleware not invoked for 1.1" claim is stale).
            ctr shouldFireInOrder {
                started
                emitted(CountVault::count)
                committed
            }
        }
}
