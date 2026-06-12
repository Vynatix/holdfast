package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TinyVault : Store<TinyVault>() {
    val n by state { 0 }
}

class ResultMatcherTest {
    // ----- shouldBeSuccess -----

    @Test
    fun shouldBeSuccessPassesOnSuccess() =
        storeTest {
            val ctr = track(TinyVault())
            val result: TransactionResult<Unit> = ctr.action { n mutate 5 }
            val success = result.shouldBeSuccess()
            // The matcher returns the success and runs the optional block.
            assertEquals(5, ctr.read { n.value })
            assertTrue(result === success, "shouldBeSuccess should return the same instance")
        }

    @Test
    fun shouldBeSuccessRunsBlockReceiver() =
        storeTest {
            val ctr = track(TinyVault())
            var blockRan = false
            ctr.action { n mutate 7 }.shouldBeSuccess {
                blockRan = true
                assertEquals(Unit, value) // body returned Unit (mutate returns Unit)
            }
            assertEquals(true, blockRan)
        }

    @Test
    fun shouldBeSuccessFailsOnError() =
        storeTest {
            val ctr = track(TinyVault())
            val err =
                assertFailsWith<AssertionError> {
                    ctr.action<Unit> { throw IllegalStateException("oops") }.shouldBeSuccess()
                }
            assertContains(err.message.orEmpty(), "Expected TransactionResult.Success")
            // shouldBeSuccess does NOT consume on failure; explicitly opt out so
            // the scope-exit guard doesn't double-fail this test.
            ctr.consumeAllPendingErrors()
        }

    // ----- shouldBeError<E> -----

    @Test
    fun shouldBeErrorPassesOnMatchingType() =
        storeTest {
            val ctr = track(TinyVault())
            ctr
                .action<Unit> { throw IllegalStateException("oops") }
                .shouldBeError<IllegalStateException> {
                    assertEquals("oops", exception.message)
                }
        }

    @Test
    fun shouldBeErrorRunsBlockReceiverAndConsumes() =
        storeTest {
            val ctr = track(TinyVault())
            var blockRan = false
            ctr
                .action<Unit> { throw IllegalArgumentException("nope") }
                .shouldBeError<IllegalArgumentException> {
                    blockRan = true
                    assertEquals("nope", exception.message)
                }
            assertEquals(true, blockRan)
            // No need to consume — shouldBeError already cleared the mark.
        }

    @Test
    fun shouldBeErrorFailsOnTypeMismatch() =
        storeTest {
            val ctr = track(TinyVault())
            val err =
                assertFailsWith<AssertionError> {
                    ctr
                        .action<Unit> { throw IllegalStateException("oops") }
                        .shouldBeError<IllegalArgumentException>()
                }
            assertContains(err.message.orEmpty(), "exception was IllegalStateException")
            ctr.consumeAllPendingErrors()
        }

    @Test
    fun shouldBeErrorFailsWhenResultIsSuccess() =
        storeTest {
            val ctr = track(TinyVault())
            val err =
                assertFailsWith<AssertionError> {
                    ctr.action { n mutate 1 }.shouldBeError<IllegalStateException>()
                }
            assertContains(err.message.orEmpty(), "but got Success")
        }

    // ----- shouldRollbackWith -----

    @Test
    fun shouldRollbackWithPassesOnRolledBack() =
        storeTest {
            val ctr = track(TinyVault())
            val result = ctr.action<Unit> { throw IllegalStateException("oops") }
            // Sanity: a thrown action rolls back the transaction.
            assertEquals(TransactionStatus.RolledBack, (result as TransactionResult.Error).transaction.status)
            result shouldRollbackWith IllegalStateException::class
        }

    @Test
    fun shouldRollbackWithFailsOnSuccess() =
        storeTest {
            val ctr = track(TinyVault())
            val err =
                assertFailsWith<AssertionError> {
                    ctr.action { n mutate 1 } shouldRollbackWith IllegalStateException::class
                }
            assertContains(err.message.orEmpty(), "but got Success")
        }

    @Test
    fun shouldRollbackWithFailsOnTypeMismatch() =
        storeTest {
            val ctr = track(TinyVault())
            val err =
                assertFailsWith<AssertionError> {
                    ctr.action<Unit> { throw IllegalStateException("oops") } shouldRollbackWith IllegalArgumentException::class
                }
            assertContains(err.message.orEmpty(), "rollback caused by IllegalArgumentException")
            ctr.consumeAllPendingErrors()
        }

    // ----- scope-exit unconsumed-error guard -----

    @Test
    fun unconsumedErrorFailsTeardown() {
        val err =
            assertFailsWith<AssertionError> {
                storeTest {
                    val ctr = track(TinyVault())
                    ctr.action<Unit> { throw IllegalStateException("oops") }
                    // Don't consume!
                }
            }
        val msg = err.message.orEmpty()
        assertContains(msg, "unconsumed")
        assertContains(msg, "IllegalStateException")
        assertContains(msg, "oops")
    }

    @Test
    fun consumedErrorPassesTeardown() =
        storeTest {
            // Reaching scope-exit cleanly = matched-and-consumed errors don't fail.
            val ctr = track(TinyVault())
            ctr
                .action<Unit> { throw IllegalStateException("oops") }
                .shouldBeError<IllegalStateException>()
        }

    @Test
    fun consumeAllClearsPending() =
        storeTest {
            val ctr = track(TinyVault())
            ctr.action<Unit> { throw IllegalStateException("oops") }
            ctr.consumeAllPendingErrors()
            // Reaching scope-exit cleanly = the explicit opt-out worked.
        }

    @Test
    fun bodyFailureSuppressesUnconsumedReport() {
        // When the body itself throws, the user-visible failure should be the
        // body's own exception, NOT a teardown-time "unconsumed" report. This
        // test demonstrates that ordering: the AssertionError thrown inside
        // the body is what propagates, even though there's an unconsumed
        // TransactionResult.Error sitting on the handle.
        val original = AssertionError("body-thrown sentinel")
        val err =
            assertFailsWith<AssertionError> {
                storeTest {
                    val ctr = track(TinyVault())
                    ctr.action<Unit> { throw IllegalStateException("oops") }
                    throw original
                }
            }
        assertSame(original, err)
    }

    @Test
    fun multipleUnconsumedErrorsAllReported() {
        val err =
            assertFailsWith<AssertionError> {
                storeTest {
                    val ctr = track(TinyVault())
                    ctr.action<Unit> { throw IllegalStateException("first") }
                    ctr.action<Unit> { throw IllegalArgumentException("second") }
                }
            }
        val msg = err.message.orEmpty()
        assertContains(msg, "2 unconsumed")
        assertContains(msg, "first")
        assertContains(msg, "second")
    }
}
