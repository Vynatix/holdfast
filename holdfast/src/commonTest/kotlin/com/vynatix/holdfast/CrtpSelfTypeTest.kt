package com.vynatix.holdfast

import com.vynatix.holdfast.testing.matcher.shouldBeError
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.storeTest
import com.vynatix.holdfast.testing.track
import kotlin.test.Test
import kotlin.test.assertEquals

private sealed class CrtpEvent {
    data object Saved : CrtpEvent()
}

/**
 * The library's own intermediate-base shape (issue 006 A2, binding): a
 * concrete subclass of [EventfulStore] must construct and dispatch fine —
 * the construction-time check walks and substitutes through generic
 * intermediates, it does not inspect the immediate superclass only.
 */
private class EventfulCrtpStore : EventfulStore<EventfulCrtpStore, CrtpEvent>() {
    val count by state { 0 }
}

private class PlainCrtpStore : Store<PlainCrtpStore>() {
    val count by state { 0 }
}

/**
 * Correct CRTP subclasses are unaffected by the self-type enforcement on
 * every target that runs this suite (JVM, Android host, iOS sim), plus the
 * gate's negative row: a user's own ClassCastException from inside a body is
 * never relabeled as a CRTP violation.
 */
class CrtpSelfTypeTest {
    @Test
    fun correctCrtpSubclassesConstructAndDispatchUnaffected() =
        storeTest {
            val store = PlainCrtpStore()
            track(store)
            val result: TransactionResult<Unit> = store action { count mutate 1 }
            result.shouldBeSuccess()
            assertEquals(1, store.count.value)
        }

    @Test
    fun eventfulStoreIntermediateBaseConstructsAndDispatches() =
        storeTest {
            val store = EventfulCrtpStore()
            track(store)
            val result: TransactionResult<Unit> =
                store action {
                    count mutate 2
                    emit(CrtpEvent.Saved)
                }
            result.shouldBeSuccess()
            assertEquals(2, store.count.value)
        }

    @Test
    fun userClassCastExceptionInsideBodyIsNotRelabeled() =
        storeTest {
            val store = PlainCrtpStore()
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
}
