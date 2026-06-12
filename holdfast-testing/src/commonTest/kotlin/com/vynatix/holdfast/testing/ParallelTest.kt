package com.vynatix.holdfast.testing

import com.vynatix.holdfast.testing.concurrency.parallel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelTest {
    @Test
    fun fansOutAndJoinsResultsInIndexOrder() =
        storeTest {
            val results = parallel(8) { it * 2 }
            assertEquals(listOf(0, 2, 4, 6, 8, 10, 12, 14), results)
        }

    @Test
    fun zeroWorkersReturnsEmptyImmediately() =
        storeTest {
            val results = parallel<Int>(0) { error("body should not run for n=0") }
            assertTrue(results.isEmpty())
        }

    @Test
    fun propagatesWorkerException() =
        storeTest {
            val ex =
                assertFailsWith<IllegalStateException> {
                    parallel(3) { idx ->
                        if (idx == 1) error("worker $idx failed")
                    }
                }
            assertEquals("worker 1 failed", ex.message)
        }
}
