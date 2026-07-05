package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun suspendActionIsCapturedOnTheTimeline() =
        storeTest {
            // suspendAction runs the middleware chain, so the recorder (an
            // ordinary middleware) captures suspending actions on the same
            // timeline as blocking ones — the stale "not captured in 1.1"
            // note is no longer true.
            val ctr = track(CountVault())
            val result =
                ctr.suspendAction {
                    delay(0)
                    count mutate 3
                }
            assertIs<TransactionResult.Success<*>>(result)

            val types = ctr.timeline.map { it::class.simpleName }
            assertTrue(
                types.contains("TransactionStarted"),
                "suspendAction must push TransactionStarted; timeline was $types",
            )
            assertTrue(
                types.contains("TransactionCommitted"),
                "suspendAction must push TransactionCommitted; timeline was $types",
            )
            assertTrue(
                ctr.timeline.any { it is EmissionEvent && it.newValue == 3 },
                "suspendAction must record the count emission; timeline was ${ctr.timeline}",
            )
        }
}
