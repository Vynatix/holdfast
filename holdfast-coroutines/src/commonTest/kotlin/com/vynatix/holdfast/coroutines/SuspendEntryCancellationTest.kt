package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private class EntryCounter : Store<EntryCounter>() {
    val n by state { 0 }
}

private class EntryOther : Store<EntryOther>() {
    val m by state { 0 }
}

/** Records the middleware hooks that fired. */
private class StartedHooks<V : Store<V>>(
    private val log: MutableList<String>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "started"
    }
}

/** A suspending bridge whose publish waits for [gate], after telling [publishing] it has started. */
private class GatedBridge(
    private val publishing: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : SuspendingBridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean = true

    override suspend fun publishAwaited(value: Int) {
        publishing.complete(Unit)
        gate.await()
    }
}

/**
 * Cancellation at the edges of an outermost `suspendAction`/`suspendAtomic`
 * (issue #20, R9): each runs as an entry inside a settle-scope child of the
 * caller (SettleAmbientContext.kt).
 *
 * - Called from an already-cancelled coroutine, it throws that
 *   [CancellationException] before taking the store, on every platform (the
 *   iOS/wasmJs carrier starts its child undispatched, which would otherwise
 *   run the body).
 * - Once the body has returned, the commit runs under `NonCancellable`, and
 *   the call returns its [TransactionResult] even when the caller was
 *   cancelled meanwhile: the settle-scope child does not turn a committed
 *   entry into a [CancellationException].
 */
class SuspendEntryCancellationTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun changesOf(counter: EntryCounter): List<Int> {
        val seen = mutableListOf<Int>()
        var initial = true
        disposables += counter.n effect { if (initial) initial = false else seen += this }
        return seen
    }

    @Test fun aSuspendActionFromAnAlreadyCancelledCoroutineThrowsBeforeTakingTheStore() =
        runBlocking {
            val counter = EntryCounter()
            val seen = changesOf(counter)
            var ran = false
            var thrown: Throwable? = null

            launch(start = CoroutineStart.UNDISPATCHED) {
                cancel()
                thrown =
                    runCatching {
                        counter.suspendAction {
                            ran = true
                            n mutate 1
                        }
                    }.exceptionOrNull()
            }.join()

            assertIs<CancellationException>(thrown)
            assertFalse(ran, "the body never ran")
            assertEquals(0, counter.n.value)
            assertEquals(emptyList(), seen)
            assertIs<TransactionResult.Success<*>>(counter.suspendAction { n mutate 2 }, "the store was never taken")
            assertEquals(2, counter.n.value)
        }

    @Test fun aSuspendAtomicFromAnAlreadyCancelledCoroutineThrowsBeforeTakingTheStore() =
        runBlocking {
            val counter = EntryCounter()
            val hooks = mutableListOf<String>()
            counter.middlewares(StartedHooks(hooks))
            var ran = false
            var thrown: Throwable? = null

            launch(start = CoroutineStart.UNDISPATCHED) {
                cancel()
                thrown =
                    runCatching {
                        suspendAtomic(counter) {
                            ran = true
                            counter { n mutate 1 }
                        }
                    }.exceptionOrNull()
            }.join()

            assertIs<CancellationException>(thrown)
            assertFalse(ran, "the body never ran")
            assertEquals(emptyList(), hooks, "no middleware hook fired")
            assertEquals(0, counter.n.value)
            assertIs<TransactionResult.Success<*>>(suspendAtomic(counter) { counter { n mutate 2 } })
            assertEquals(2, counter.n.value)
        }

    @Test fun aSuspendActionWhoseCallerIsCancelledOnceItsBodyRanStillReturnsTheCommit() =
        runBlocking {
            val counter = EntryCounter()
            var outcome: TransactionResult<*>? = null
            lateinit var caller: Job
            caller =
                launch {
                    outcome =
                        counter.suspendAction {
                            caller.cancel()
                            n mutate 1
                        }
                }
            caller.join()

            assertIs<TransactionResult.Success<*>>(outcome)
            assertEquals(1, counter.n.value)
        }

    /**
     * A frame's body runs in a context of its own, so a cancellation that
     * reaches it before it returns rolls the frame back. Once the body has
     * returned — here an observer cancels the caller from inside the commit —
     * the frame commits, and the call returns that.
     */
    @Test fun aSuspendAtomicWhoseCallerIsCancelledDuringItsCommitStillReturnsTheCommit() =
        runBlocking {
            val counter = EntryCounter()
            val other = EntryOther()
            var outcome: TransactionResult<*>? = null
            lateinit var caller: Job
            disposables += counter.n effect { if (this == 1) caller.cancel() }
            caller =
                launch {
                    outcome =
                        suspendAtomic(counter, other) {
                            counter { n mutate 1 }
                            other { m mutate 1 }
                        }
                }
            caller.join()

            assertIs<TransactionResult.Success<*>>(outcome)
            assertEquals(1, counter.n.value)
            assertEquals(1, other.m.value)
        }

    /** Cancelled while the commit awaits a `SuspendingBridge` publish: the commit completes, and the call returns it. */
    @Test fun aSuspendActionCancelledWhileItsPublishIsAwaitedReturnsTheCommit() =
        runBlocking {
            val counter = EntryCounter()
            val publishing = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            counter { n bridge GatedBridge(publishing, gate) }
            var outcome: TransactionResult<*>? = null
            val caller = launch { outcome = counter.suspendAction { n mutate 1 } }

            publishing.await()
            caller.cancel()
            gate.complete(Unit)
            caller.join()

            assertIs<TransactionResult.Success<*>>(outcome)
            assertEquals(1, counter.n.value)
        }

    @Test fun aSuspendAtomicCancelledWhileAParticipantsPublishIsAwaitedReturnsTheCommit() =
        runBlocking {
            val counter = EntryCounter()
            val other = EntryOther()
            val publishing = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            counter { n bridge GatedBridge(publishing, gate) }
            var outcome: TransactionResult<*>? = null
            val caller =
                launch {
                    outcome =
                        suspendAtomic(counter, other) {
                            counter { n mutate 1 }
                            other { m mutate 1 }
                        }
                }

            publishing.await()
            caller.cancel()
            gate.complete(Unit)
            caller.join()

            assertIs<TransactionResult.Success<*>>(outcome)
            assertEquals(1, counter.n.value)
            assertEquals(1, other.m.value)
        }

    /** A body that the caller's cancellation reaches still rolls back and rethrows it. */
    @Test fun aBodyCancelledAtASuspensionPointStillRollsBack() =
        runBlocking {
            val counter = EntryCounter()
            val entered = CompletableDeferred<Unit>()
            var thrown: Throwable? = null
            val caller =
                launch {
                    thrown =
                        runCatching {
                            counter.suspendAction {
                                n mutate 1
                                entered.complete(Unit)
                                CompletableDeferred<Unit>().await()
                            }
                        }.exceptionOrNull()
                }

            entered.await()
            caller.cancel()
            caller.join()

            assertIs<CancellationException>(thrown)
            assertEquals(0, counter.n.value)
        }
}
