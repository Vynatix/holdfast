@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What counts as fatal on JVM/Android, and how [FatalHold] keeps one (issue #61). */
class FatalErrorsTest {
    @Test
    fun everyVirtualMachineErrorIsFatal() {
        for (error in listOf(StackOverflowError(), OutOfMemoryError(), InternalError(), UnknownError())) {
            assertTrue(error.internalIsFatal(), "$error")
        }
    }

    @Test
    fun otherThrowablesAreNot() {
        val ordinary =
            listOf(
                AssertionError("a test failure"),
                NotImplementedError(),
                IllegalStateException(),
                CancellationException("cancelled"),
                ExceptionInInitializerError(),
                InterruptedException(),
                RuntimeException(StackOverflowError()),
            )
        for (error in ordinary) assertFalse(error.internalIsFatal(), "$error")
    }

    @Test
    fun rethrowIfFatalThrowsTheSameInstanceAndLetsOthersPass() {
        val fatal = StackOverflowError()
        assertSame(fatal, assertFailsWith<StackOverflowError> { fatal.internalRethrowIfFatal() })
        IllegalStateException().internalRethrowIfFatal()
        val failure = runCatching { error("x") }
        assertEquals(failure, failure.internalRethrowFatal())
        assertSame(fatal, assertFailsWith<StackOverflowError> { Result.failure<Unit>(fatal).internalRethrowFatal() })
    }

    @Test
    fun aHoldKeepsTheFirstFatalErrorAndSuppressesTheRest() {
        val hold = FatalHold()
        hold.rethrowHeld() // nothing held: no throw
        assertFalse(hold.holdIfFatal(IllegalStateException()))
        assertFalse(hold.isHolding)
        val first = StackOverflowError()
        val second = OutOfMemoryError()
        assertTrue(hold.holdIfFatal(first))
        assertTrue(hold.holdIfFatal(second))
        assertTrue(hold.holdIfFatal(first)) // the same instance again is not self-suppressed
        assertTrue(hold.isHolding)
        val cancellation = CancellationException("unwinding")
        val thrown = assertFailsWith<StackOverflowError> { hold.rethrowHeld(alongside = cancellation) }
        assertSame(first, thrown)
        assertEquals(listOf(second, cancellation), thrown.suppressed.toList())
    }
}
