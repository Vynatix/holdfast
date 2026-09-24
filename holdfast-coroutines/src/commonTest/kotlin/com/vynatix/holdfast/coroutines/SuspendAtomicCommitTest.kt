@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionException
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private sealed interface CommitNote {
    data object Saved : CommitNote
}

private class CommitFirst : Store<CommitFirst>() {
    val x by state { 0 }
}

private class CommitSecond : EventfulStore<CommitSecond, CommitNote>() {
    val y by state { 0 }
}

/** A value whose `equals` throws while [failing] says so: a `distinct` state compares with it as its commit applies. */
private class Touchy(
    val n: Int,
    private val failing: () -> Boolean,
) {
    override fun equals(other: Any?): Boolean {
        check(!failing()) { "equals failed" }
        return other is Touchy && other.n == n
    }

    override fun hashCode(): Int = n
}

/** A participant whose apply fails while [failEquals] is set. */
private class CommitFailing : Store<CommitFailing>() {
    val plain by state { 0 }
    var failEquals = false
    val touchy by state(distinct = true) { Touchy(0) { failEquals } }
}

private class CommitThird : Store<CommitThird>() {
    val z by state { 0 }
}

/** Counts the errors a participant's middleware hears of. */
private class ErrorCount<V : Store<V>> : Middleware<V>() {
    var errors = 0

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        errors++
    }
}

/** A suspending bridge whose publish times out: a `TimeoutCancellationException` from user code. */
private class TimingOutBridge : SuspendingBridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean = true

    override suspend fun publishAwaited(value: Int) {
        withTimeout(1) { awaitCancellation() }
    }
}

/** A bridge that records what it is asked to publish. */
private class RecordingBridge(
    private val published: MutableList<Int>,
) : Bridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean {
        published += value
        return true
    }
}

/** Records the hooks a frame fires on one participant. */
private class CommitHooks<V : Store<V>>(
    private val log: MutableList<String>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        log += "completed"
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        log += "error"
    }
}

/**
 * A `suspendAtomic` applies every participant before any fans out (issue
 * #20, R9), so a participant's failing fanout — a throwing failure handler,
 * or a `CancellationException` from its `SuspendingBridge.publishAwaited` —
 * must not keep the later participants from fanning out: their values are
 * already applied. They commit, their observers, bridges and events run, and
 * the frame reports the failure. A failing APPLY commits the participants
 * applied before it and rolls back the rest — every savepoint participant
 * too.
 */
class SuspendAtomicCommitTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun <T : Any> changesOf(state: State<T>): List<T> {
        val seen = mutableListOf<T>()
        var initial = true
        disposables += state effect { if (initial) initial = false else seen += this }
        return seen
    }

    @Test fun aCancellationFromOneParticipantsPublishStillFansOutTheOthers() =
        runBlocking {
            val first = CommitFirst()
            val second = CommitSecond()
            assertTrue(first.lockOrderKey < second.lockOrderKey)
            first { x bridge TimingOutBridge() }
            val published = mutableListOf<Int>()
            second { y bridge RecordingBridge(published) }
            val secondSaw = changesOf(second.y)
            val hooks = mutableListOf<String>()
            second.middlewares(CommitHooks(hooks))
            val events = mutableListOf<CommitNote>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { second.events.collect { events += it } }
            var secondRoot: Transaction? = null

            val r =
                suspendAtomic(first, second) {
                    first { x mutate 1 }
                    second {
                        y mutate 2
                        emit(CommitNote.Saved)
                        secondRoot = activeTransaction
                    }
                }
            repeat(10) { if (events.isEmpty()) yield() }
            collector.cancel()

            val error = assertIs<TransactionResult.Error>(r)
            assertIs<CancellationException>(error.exception, "the publish's own cancellation, thrown last")
            assertEquals(1, first.x.value)
            assertEquals(2, second.y.value)
            assertEquals(listOf(2), secondSaw, "the later participant's observers fired")
            assertEquals(listOf(2), published, "and its bridge published")
            assertEquals(listOf<CommitNote>(CommitNote.Saved), events, "and its event was emitted")
            assertEquals(TransactionStatus.Committed, assertNotNull(secondRoot).status)
            assertEquals(listOf("started", "completed"), hooks, "no error hook for a participant that committed")
        }

    @Test fun aFailingFanoutOnOneSuspendAtomicParticipantStillFansOutTheOthers() =
        runBlocking {
            val first = CommitFirst()
            val second = CommitSecond()
            assertTrue(first.lockOrderKey < second.lockOrderKey)
            first.uncaughtObserverHandler = { throw IllegalStateException("handler gave up", it) }
            var initial = true
            disposables += first.x effect { if (initial) initial = false else error("observer failed") }
            val secondSaw = changesOf(second.y)
            var secondRoot: Transaction? = null

            val r =
                suspendAtomic(first, second) {
                    first { x mutate 1 }
                    second {
                        y mutate 2
                        secondRoot = activeTransaction
                    }
                }

            val error = assertIs<TransactionResult.Error>(r)
            val failure = assertIs<TransactionException>(error.exception)
            assertTrue("observer/bridge fanout" in failure.message.orEmpty(), failure.message)
            assertEquals(1, first.x.value)
            assertEquals(2, second.y.value)
            assertEquals(listOf(2), secondSaw, "the second participant fanned out after the first one's fanout failed")
            assertEquals(TransactionStatus.Committed, assertNotNull(secondRoot).status)
        }

    /**
     * An apply that fails on the middle one of three participants (a
     * `distinct` state's `equals`): the one before it applied, so it fans out
     * and commits; the failing one ends Failed; the one after it never applies
     * and rolls back. Only that last one's middleware hears of the error.
     */
    @Test fun aFailedApplyCommitsTheEarlierParticipantsAndRollsBackTheLaterOnes() =
        runBlocking {
            val first = CommitFirst()
            val second = CommitFailing()
            val third = CommitThird()
            assertTrue(first.lockOrderKey < second.lockOrderKey && second.lockOrderKey < third.lockOrderKey)
            second.touchy.value // materialized outside the frame: only the apply pass compares
            second.failEquals = true
            val firstHook = ErrorCount<CommitFirst>().also { first.middlewares(it) }
            val secondHook = ErrorCount<CommitFailing>().also { second.middlewares(it) }
            val thirdHook = ErrorCount<CommitThird>().also { third.middlewares(it) }
            val firstSaw = changesOf(first.x)
            val thirdSaw = changesOf(third.z)
            val roots = arrayOfNulls<Transaction>(3)

            val r =
                suspendAtomic(first, second, third) {
                    first {
                        x mutate 1
                        roots[0] = activeTransaction
                    }
                    second {
                        plain mutate 2
                        touchy mutate Touchy(2) { false }
                        roots[1] = activeTransaction
                    }
                    third {
                        z mutate 3
                        roots[2] = activeTransaction
                    }
                }
            second.failEquals = false

            val error = assertIs<TransactionResult.Error>(r)
            assertTrue("state-apply" in error.exception.message.orEmpty(), error.exception.message)
            assertEquals(1, first.x.value)
            assertEquals(listOf(1), firstSaw, "the participant applied before the failure fanned out")
            assertEquals(TransactionStatus.Committed, assertNotNull(roots[0]).status)
            assertEquals(TransactionStatus.Failed, assertNotNull(roots[1]).status)
            assertEquals(0, third.z.value)
            assertEquals(emptyList(), thirdSaw)
            assertEquals(TransactionStatus.RolledBack, assertNotNull(roots[2]).status)
            assertEquals(
                listOf(0, 0, 1),
                listOf(firstHook.errors, secondHook.errors, thirdHook.errors),
                "only the participant that rolled back hears of it",
            )
        }

    /**
     * A nested frame's participant that the enclosing frame holds joins as a
     * savepoint; when another participant's apply fails, the savepoint rolls
     * back instead of merging into the enclosing frame, so none of its writes
     * commits with that frame. The enclosing frame tolerates the inner error,
     * so it commits and the check is not vacuous. (Only this lock order: a
     * nested frame may not introduce a store sorting below one already held.)
     */
    @Test fun aSavepointParticipantRollsBackWhenAnotherParticipantsApplyFails() =
        runBlocking {
            val shared = CommitFirst()
            val failing = CommitFailing()
            assertTrue(shared.lockOrderKey < failing.lockOrderKey)
            val sharedHook = ErrorCount<CommitFirst>().also { shared.middlewares(it) }
            failing.touchy.value
            failing.failEquals = true
            var inner: TransactionResult<*>? = null

            suspendAtomic(shared, policy = FramePolicy.TolerateInnerErrors) {
                inner =
                    suspendAtomic(shared, failing) {
                        shared { x mutate 1 }
                        failing { touchy mutate Touchy(2) { false } }
                    }
            }.getOrThrow()
            failing.failEquals = false

            val error = assertIs<TransactionResult.Error>(inner)
            assertTrue("state-apply" in error.exception.message.orEmpty(), error.exception.message)
            assertEquals(0, shared.x.value, "the savepoint's write rolled back with the nested frame")
            assertEquals(1, sharedHook.errors, "the savepoint participant hears of the error")
        }
}
