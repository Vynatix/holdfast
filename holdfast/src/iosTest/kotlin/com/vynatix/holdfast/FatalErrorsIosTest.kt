@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin/Native's only catchable fatal error is `kotlin.OutOfMemoryError`; a
 * stack overflow kills the process before any catch runs (issue #61).
 */
class FatalErrorsIosTest {
    @Test
    fun outOfMemoryErrorIsFatal() {
        assertTrue(OutOfMemoryError().internalIsFatal())
    }

    @Test
    fun otherErrorsAreNot() {
        assertFalse(AssertionError("a test failure").internalIsFatal())
        assertFalse(NotImplementedError().internalIsFatal())
        assertFalse(IllegalStateException().internalIsFatal())
    }
}
