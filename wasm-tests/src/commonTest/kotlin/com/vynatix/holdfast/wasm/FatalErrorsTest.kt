@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalIsFatal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fatal-error predicate on wasmJs, with the JVM as the control (issue #61). */
class FatalErrorsTest {
    @Test
    fun theFatalErrorsOfThisPlatformAreFatal() {
        for (error in platformFatalErrors()) assertTrue(error.internalIsFatal(), "$error")
    }

    @Test
    fun ordinaryErrorsAreNot() {
        assertFalse(AssertionError("a test failure").internalIsFatal())
        assertFalse(NotImplementedError().internalIsFatal())
        assertFalse(IllegalStateException().internalIsFatal())
    }
}
