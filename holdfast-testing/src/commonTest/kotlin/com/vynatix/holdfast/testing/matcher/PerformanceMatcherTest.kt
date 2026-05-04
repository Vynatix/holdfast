package com.vynatix.holdfast.testing.matcher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class PerformanceMatcherTest {

    @Test
    fun syncFastBlockPasses() {
        { /* near-zero */ } shouldCompleteWithin 100.milliseconds
    }

    @Test
    fun syncSlowBlockFails() {
        assertFailsWith<AssertionError> {
            { burnCpu(BURN_MS) } shouldCompleteWithin 1.milliseconds
        }
    }

    @Test
    fun suspendFastBlockPasses() = runTest {
        suspend { /* near-zero */ } shouldCompleteWithin 100.milliseconds
    }

    @Test
    fun suspendSlowBlockFails() = runTest {
        assertFailsWith<AssertionError> {
            suspend { burnCpu(BURN_MS) } shouldCompleteWithin 1.milliseconds
        }
    }

    private fun burnCpu(ms: Long) {
        // Busy-wait. Do NOT use Thread.sleep (JVM-only). Don't use delay (virtual under runTest).
        val deadline = TimeSource.Monotonic.markNow() + ms.milliseconds
        while (deadline.hasNotPassedNow()) {
            // burn
        }
    }

    private companion object {
        const val BURN_MS: Long = 50L
    }
}
