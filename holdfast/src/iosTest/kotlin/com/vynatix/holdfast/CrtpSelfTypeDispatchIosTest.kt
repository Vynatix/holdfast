package com.vynatix.holdfast

import com.vynatix.holdfast.testing.matcher.shouldBeError
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.storeTest
import com.vynatix.holdfast.testing.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class IosAnchor : Store<IosAnchor>()

/**
 * Mistyped: constructs fine on Kotlin/Native (no construction-time check) and
 * fails at the FIRST DISPATCH — the receiver-bridge `this as Self` cast.
 */
private class IosMistyped : Store<IosAnchor>()

private class IosCorrect : Store<IosCorrect>() {
    val n by state { 0 }
}

/**
 * iOS first-dispatch rows (issue 006 A5/A6/A7): the CRTP violation rethrows
 * RAW from `action` with the one-type teaching message (NOT wrapped into a
 * `TransactionResult.Error`), `invoke` throws the same teaching message, and
 * a user's own `ClassCastException` from inside a body is never relabeled.
 */
class CrtpSelfTypeDispatchIosTest {
    @Test
    fun wrongSelfTypeRethrowsTeachingClassCastExceptionRawFromAction() {
        val store = IosMistyped()
        val failure =
            assertFailsWith<ClassCastException> {
                store action { }
            }
        assertEquals(
            "IosMistyped is not its own self type: declare it as " +
                "`class IosMistyped : Store<IosMistyped>()` — a Store's Self type argument must be " +
                "the store class itself, and this store failed the cast to its declared Self at " +
                "first dispatch. (Kotlin/Native and wasmJs cannot verify this at construction; " +
                "JVM and Android stores are checked at construction when generic signatures are " +
                "available.)",
            failure.message,
        )
    }

    @Test
    fun wrongSelfTypeInvokeThrowsTeachingMessage() {
        val store = IosMistyped()
        val failure =
            assertFailsWith<ClassCastException> {
                store { }
            }
        assertEquals(
            "IosMistyped is not its own self type: declare it as " +
                "`class IosMistyped : Store<IosMistyped>()` — a Store's Self type argument must be " +
                "the store class itself, and this store failed the cast to its declared Self at " +
                "first dispatch. (Kotlin/Native and wasmJs cannot verify this at construction; " +
                "JVM and Android stores are checked at construction when generic signatures are " +
                "available.)",
            failure.message,
        )
    }

    @Test
    fun userClassCastExceptionInsideBodyIsNotRelabeled() =
        storeTest {
            val store = IosCorrect()
            track(store)
            val original = ClassCastException("deliberate user cast failure")
            val result: TransactionResult<Unit> = store action { throw original }
            result.shouldBeError<ClassCastException> {
                assertEquals(
                    original,
                    exception,
                    "a user CCE from the body must reach the Error result as itself, never the teaching message",
                )
            }
        }

    @Test
    fun correctCrtpStoreConstructsAndDispatches() =
        storeTest {
            val store = IosCorrect()
            track(store)
            val result: TransactionResult<Unit> = store action { n mutate 1 }
            result.shouldBeSuccess()
            assertEquals(1, store.n.value)
        }
}
