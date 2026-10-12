@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

private class FatalPropagationCounter : Store<FatalPropagationCounter>() {
    val n by state { 0 }
}

private fun Store<*>.fatalPropagationReports(): MutableList<Throwable> =
    mutableListOf<Throwable>().also { seen -> uncaughtObserverHandler = { seen += it } }

/** Nothing an entry installs on its thread (every coroutine's, on wasmJs) is left behind. */
private fun fatalPropagationAssertNoSlotLeaked(where: String) {
    assertNull(SettleScopes.current(), "settle scope leaked $where")
    assertNull(FrameMarkers.current(), "frame marker leaked $where")
    assertNull(FanoutMarkers.current(), "fanout marker leaked $where")
}

/**
 * [thrown] is [expected], or — on the JVM control only, where Gradle runs
 * with assertions on — the copy kotlinx.coroutines' stack-trace recovery
 * made of it on its way out of a coroutine boundary: the same class,
 * [expected] its cause. Never a wrapper of another class.
 */
private fun fatalPropagationAssertSame(
    expected: Throwable,
    thrown: Throwable,
) {
    if (thrown === expected) return
    assertEquals(expected::class, thrown::class, "a recovered copy keeps the class: $thrown")
    assertSame(expected, thrown.cause, "a recovered copy carries the original as its cause: $thrown")
}

/**
 * Fatal errors propagate (issue #61) on wasmJs, with the JVM as the control:
 * each platform's fatal errors ([platformFatalErrors] — `OutOfMemoryError` on
 * wasmJs, where a stack overflow is uncatchable; `StackOverflowError` and
 * `OutOfMemoryError` on the JVM) thrown from user code come out of the entry
 * that ran it as they are — never a `TransactionResult.Error`, never reported
 * to `uncaughtObserverHandler` — after what the entry owes: the rollback, the
 * rest of the fanout, the rest of the settle. The store is usable after.
 */
class FatalPropagationTest {
    @Test
    fun aFatalErrorAnActionBodyThrowsPropagatesAndRollsBack() {
        for (fatal in platformFatalErrors()) {
            val store = FatalPropagationCounter()
            val reported = store.fatalPropagationReports()
            val thrown =
                assertFailsWith<Throwable> {
                    store action {
                        n mutate 5
                        throw fatal
                    }
                }
            assertSame(fatal, thrown, "$fatal")
            assertEquals(0, store.n.value, "rolled back ($fatal)")
            assertNull(store.activeTransaction)
            fatalPropagationAssertNoSlotLeaked("after $fatal")
            assertIs<TransactionResult.Success<*>>(store action { n mutate 1 })
            assertEquals(1, store.n.value)
            assertEquals(emptyList(), reported.toList(), "$fatal")
        }
    }

    /** Non-fatal control: an ordinary body failure is still an Error result, rolled back. */
    @Test
    fun anOrdinaryActionBodyFailureIsStillAnErrorResult() {
        val store = FatalPropagationCounter()
        val result =
            store action {
                n mutate 5
                error("body")
            }
        val error = assertIs<TransactionResult.Error>(result)
        assertEquals("body", error.exception.message)
        assertEquals(TransactionStatus.RolledBack, error.transaction.status)
        assertEquals(0, store.n.value)
    }

    @Test
    fun anObserversFatalErrorPropagatesAfterTheRestOfTheFanoutAndTheCommitStands() {
        for (fatal in platformFatalErrors()) {
            val store = FatalPropagationCounter()
            val reported = store.fatalPropagationReports()
            var armed = false
            val seen = mutableListOf<Int>()
            val first = store.n effect { if (armed) throw fatal }
            val second = store.n effect { seen += this }
            seen.clear()
            armed = true
            val thrown = assertFailsWith<Throwable> { store action { n mutate 1 } }
            armed = false
            assertSame(fatal, thrown, "$fatal")
            assertEquals(1, store.n.value, "the commit stands ($fatal)")
            assertEquals(listOf(1), seen, "the other observer still ran ($fatal)")
            assertNull(store.activeTransaction)
            fatalPropagationAssertNoSlotLeaked("after $fatal")
            assertIs<TransactionResult.Success<*>>(store action { n mutate 2 })
            assertEquals(listOf(1, 2), seen)
            assertEquals(emptyList(), reported.toList(), "$fatal")
            first.dispose()
            second.dispose()
        }
    }

    /** Non-fatal control: an ordinary observer failure is still reported, and the action still succeeds. */
    @Test
    fun anOrdinaryObserverFailureIsStillReportedAndTheActionSucceeds() {
        val store = FatalPropagationCounter()
        val reported = store.fatalPropagationReports()
        var armed = false
        val sub = store.n effect { if (armed) error("observer") }
        armed = true
        assertIs<TransactionResult.Success<*>>(store action { n mutate 1 })
        armed = false
        assertEquals(1, store.n.value)
        assertEquals("observer", reported.single().message)
        sub.dispose()
    }

    @Test
    fun aDerivedStateComputesFatalErrorPropagatesOutOfTheEntryThatSettledIt() {
        for (fatal in platformFatalErrors()) {
            val store = FatalPropagationCounter()
            val reported = store.fatalPropagationReports()
            var armed = false
            val total = store.derivedState(store.n) { if (armed) throw fatal else n.value * 10 }
            assertEquals(0, total.value)
            armed = true
            val thrown = assertFailsWith<Throwable> { store action { n mutate 2 } }
            armed = false
            assertSame(fatal, thrown, "$fatal")
            assertEquals(2, store.n.value, "the source's commit stands ($fatal)")
            assertEquals(0, total.value, "the recompute that threw committed nothing ($fatal)")
            assertNull(store.activeTransaction)
            fatalPropagationAssertNoSlotLeaked("after $fatal")
            assertIs<TransactionResult.Success<*>>(store action { n mutate 3 })
            assertEquals(30, total.value, "the derived state recomputes again ($fatal)")
            assertEquals(emptyList(), reported.toList(), "$fatal")
        }
    }

    @Test
    fun aFatalErrorASuspendActionBodyThrowsPropagatesAndRollsBack() =
        runTest {
            for (fatal in platformFatalErrors()) {
                for (afterSuspending in listOf(false, true)) {
                    val where = "$fatal, after suspending: $afterSuspending"
                    val store = FatalPropagationCounter()
                    val reported = store.fatalPropagationReports()
                    val thrown =
                        assertFailsWith<Throwable> {
                            store.suspendAction {
                                n mutate 5
                                if (afterSuspending) yield()
                                throw fatal
                            }
                        }
                    fatalPropagationAssertSame(fatal, thrown)
                    assertEquals(0, store.n.value, "rolled back ($where)")
                    assertNull(store.activeTransaction, where)
                    assertNull(store.suspendingOwner, "no suspending holder is left installed ($where)")
                    fatalPropagationAssertNoSlotLeaked("after $where")
                    assertIs<TransactionResult.Success<*>>(store.suspendAction { n mutate 1 })
                    assertIs<TransactionResult.Success<*>>(store action { n mutate 2 }, "a blocking action too")
                    assertEquals(2, store.n.value)
                    assertEquals(emptyList(), reported.toList(), where)
                }
            }
        }
}
