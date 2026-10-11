package com.vynatix.holdfast

import com.vynatix.holdfast.testing.concurrency.parallel
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private class AtomicUpdateVault : Store<AtomicUpdateVault>() {
    val count by state { 0 }
}

class StandaloneUpdateAtomicityTest {
    @Test
    fun tenThousandConcurrentStandaloneIncrementsAllSurvive() =
        storeTest(timeout = 120.seconds) {
            val v = track(AtomicUpdateVault()).store
            val workers = 8
            val incrementsPerWorker = 1250 // 8 × 1250 = 10 000
            parallel(workers) {
                repeat(incrementsPerWorker) {
                    v { count update { it + 1 } } // bare invoke — OUTSIDE any action
                }
            }
            assertEquals(workers * incrementsPerWorker, v.count.value)
        }

    @Test
    fun standaloneUpdateCommitsThroughItsImplicitTransaction() =
        storeTest {
            val h = track(AtomicUpdateVault())
            h.store { count update { it + 5 } }
            assertEquals(5, h.read { count.value })
        }

    @Test
    fun updateInsideOwnerActionChainsReadYourOwnWrites() =
        storeTest {
            val h = track(AtomicUpdateVault())
            val result =
                h.action {
                    count update { it + 10 }
                    count update { it * 2 }
                    count update { it + 1 }
                }
            result.shouldBeSuccess()
            // 0 → 10 → 20 → 21
            assertEquals(21, h.read { count.value })
        }

    @Test
    fun throwingBlockPropagatesAndTheWriteIsRolledBack() =
        storeTest {
            val h = track(AtomicUpdateVault())
            h.action { count mutate 5 }.shouldBeSuccess()
            val thrown =
                assertFailsWith<IllegalStateException> { h.store { count update { error("boom") } } }
            assertEquals("boom", thrown.message, "the user lambda's original exception propagates (getOrThrow)")
            assertEquals(5, h.read { count.value }, "nothing committed — the rollback left the prior value")
            assertNull(h.read { activeTransaction }, "no transaction is left behind")
        }

    @Test
    fun standaloneUpdateSurfacesCommitPhaseFailuresThatMutateSwallows() =
        storeTest {
            val v = track(AtomicUpdateVault()).store
            v.middlewares(
                object : Middleware<AtomicUpdateVault>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<AtomicUpdateVault>) {
                        error("validation failed")
                    }
                },
            )
            // update consumes its one-shot action's result with getOrThrow, so a
            // middleware failure in the commit phase surfaces to the caller.
            val thrown =
                assertFailsWith<IllegalStateException> { v { count update { it + 1 } } }
            assertEquals("validation failed", thrown.message)
            assertEquals(0, v.count.value, "the rolled-back update committed nothing")
            // mutate discards the same one-shot result — update is deliberately
            // stricter than mutate; this pins the contrast.
            v { count mutate 7 }
            assertEquals(0, v.count.value, "mutate stays silent about its rolled-back one-shot action")
        }
}
