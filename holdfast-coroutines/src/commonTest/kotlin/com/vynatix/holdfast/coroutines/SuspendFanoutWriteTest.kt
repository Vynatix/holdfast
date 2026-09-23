package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class EchoStore : Store<EchoStore>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

private class CopyStore : Store<CopyStore>() {
    val copy by state { 0 }
}

private sealed class EchoEvent {
    data object Reacted : EchoEvent()
}

private class EchoEventStore : EventfulStore<EchoEventStore, EchoEvent>() {
    val trigger by state { 0 }
}

/**
 * The suspending commit paths refuse writes into an already-applied
 * transaction exactly like the blocking one (issue #20, D16; the blocking cases
 * are in `:holdfast`'s `FanoutWriteTest`).
 *
 * A `suspendAction` or `suspendAtomic` root stays installed while
 * `suspendingCommit` fans out, and `suspendingOwner` relaxes `mutate`'s owner
 * check to any thread — so an observer's write back into the committing store
 * staged into the applied root and was silently lost.
 */
class SuspendFanoutWriteTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun <T : Any> onCommit(
        state: State<T>,
        react: (T) -> Unit,
    ) {
        var initial = true
        disposables +=
            state.effect {
                if (initial) initial = false else react(this)
            }
    }

    private fun Store<*>.collectFailures(): MutableList<Throwable> {
        val failures = mutableListOf<Throwable>()
        uncaughtObserverHandler = { failures += it }
        return failures
    }

    /**
     * The observer-flavoured refusal: code running inside the commit must be
     * recognised as such, not told its write came from another thread (whose
     * advice, `store action { }`, is itself refused inside the commit).
     */
    private fun assertAppliedTransactionError(
        error: Throwable,
        vararg fragments: String,
    ) {
        assertIs<IllegalStateException>(error)
        val message = assertNotNull(error.message)
        assertTrue("has already applied its writes" in message, "not the applied-transaction error: $message")
        assertTrue(
            "a suspendAction or suspendAtomic holds" !in message,
            "in-commit write got the foreign-thread text: $message",
        )
        fragments.forEach { assertTrue(it in message, "message lacks '$it': $message") }
    }

    @Test fun observerMutateDuringASuspendActionCommitIsSurfaced() =
        runBlocking {
            val s = EchoStore()
            val failures = s.collectFailures()
            onCommit(s.trigger) { value -> s { echo mutate value } }

            val r = s.suspendAction { trigger mutate 1 }

            assertIs<TransactionResult.Success<*>>(r, "the commit that triggered the observer stands")
            assertEquals(1, s.trigger.value)
            assertAppliedTransactionError(failures.single(), "Cannot write EchoStore.echo", "computed { }")
            assertEquals(0, s.echo.value, "the refused write never lands")
        }

    @Test fun observerEmitDuringASuspendActionCommitIsSurfaced() =
        runBlocking {
            val s = EchoEventStore()
            val failures = s.collectFailures()
            onCommit(s.trigger) { s.emit(EchoEvent.Reacted) }

            val r = s.suspendAction { trigger mutate 1 }

            assertIs<TransactionResult.Success<*>>(r)
            assertAppliedTransactionError(failures.single(), "emit an event on EchoEventStore", "computed { }")
        }

    @OptIn(StoreInternalApi::class)
    @Test
    fun observerWriteIntoAnAppliedSuspendAtomicParticipantIsSurfaced() =
        runBlocking {
            // Participants commit in lock order, each fanning out before the next
            // applies: the later participant's observer meets the earlier one's
            // applied root.
            val s = EchoStore()
            val t = CopyStore()
            assertTrue(s.lockOrderKey < t.lockOrderKey)
            val failures = t.collectFailures()
            onCommit(t.copy) { value -> s { echo mutate value } }

            val r =
                suspendAtomic(s, t) {
                    s { trigger mutate 1 }
                    t { copy mutate 2 }
                }

            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(1, s.trigger.value)
            assertEquals(2, t.copy.value)
            // s's fanoutThreadId is already cleared here: only the frame's
            // FanoutMarkers classify this write as part of the commit.
            assertAppliedTransactionError(failures.single(), "Cannot write EchoStore.echo", "computed { }")
            assertEquals(0, s.echo.value)
        }

    /**
     * A nested `suspendAtomic` gives `s`, held by the enclosing frame, a
     * savepoint entry: it commits into the enclosing root (which has not
     * applied) and stays installed while `t`, a fresh root, fans out. A write
     * into it then would be lost; it is refused as part of the commit (the
     * frame's fanout marker names the savepoint), not as another thread's.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun observerWriteIntoANestedSuspendAtomicSavepointParticipantIsSurfaced() =
        runBlocking {
            val s = EchoStore()
            val t = CopyStore()
            assertTrue(s.lockOrderKey < t.lockOrderKey)
            val failures = t.collectFailures()
            onCommit(t.copy) { value -> s { echo mutate value } }

            val r =
                suspendAtomic(s) {
                    suspendAtomic(s, t) {
                        s { trigger mutate 1 }
                        t { copy mutate 2 }
                    }
                }

            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(1, s.trigger.value)
            assertEquals(2, t.copy.value)
            assertAppliedTransactionError(
                failures.single(),
                "Cannot write EchoStore.echo",
                "status: Committed",
                "computed { }",
            )
            assertEquals(0, s.echo.value)
        }

    @OptIn(StoreInternalApi::class)
    @Test
    fun observerWriteIntoAPendingSuspendAtomicParticipantCommitsWithTheFrame() =
        runBlocking {
            val s = EchoStore()
            val t = CopyStore()
            assertTrue(s.lockOrderKey < t.lockOrderKey)
            val failures = s.collectFailures()
            onCommit(s.trigger) { value -> t { copy mutate value * 100 } }

            val r = suspendAtomic(s, t) { s { trigger mutate 3 } }

            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(300, t.copy.value, "t's root has not applied yet, so the write commits with it")
            assertEquals(emptyList(), failures)
        }

    /**
     * `publishAwaited` runs after `commitDispatching` has returned, so only
     * `suspendingCommit`'s own FanoutMarkers classify a write from it as part
     * of the commit rather than as another thread's.
     */
    @Test fun publishAwaitedWriteBackDuringASuspendActionCommitIsSurfaced() =
        runBlocking {
            val s = EchoStore()
            val failures = s.collectFailures()
            val awaited =
                object : SuspendingBridge<Int> {
                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

                    override fun publish(value: Int): Boolean = true

                    override suspend fun publishAwaited(value: Int) {
                        s { echo mutate value }
                    }
                }
            s { trigger bridge awaited }

            val r = s.suspendAction { trigger mutate 1 }

            assertIs<TransactionResult.Success<*>>(r, "a failed publish never undoes the commit")
            assertEquals(1, s.trigger.value)
            assertAppliedTransactionError(failures.single(), "Cannot write EchoStore.echo", "computed { }")
            assertEquals(0, s.echo.value)
        }

    @Test fun crossStoreObserverWriteDuringASuspendActionCommitStillCommits() =
        runBlocking {
            val s = EchoStore()
            val t = CopyStore()
            val failures = s.collectFailures()
            onCommit(s.trigger) { value -> t { copy mutate value } }

            s.suspendAction { trigger mutate 5 }

            assertEquals(5, t.copy.value)
            assertEquals(emptyList(), failures)
        }
}
