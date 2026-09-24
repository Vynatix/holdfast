@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class OrderFirst : Store<OrderFirst>() {
    val x by state { 0 }
}

private class OrderSecond : Store<OrderSecond>() {
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

private class ApplyShared : Store<ApplyShared>() {
    val x by state { 0 }
}

private class ApplyFailing : Store<ApplyFailing>() {
    var failEquals = false
    val y by state(distinct = true) { Touchy(0) { failEquals } }
}

/** Records the hooks a frame fires on one participant. */
private class HookLog<V : Store<V>>(
    private val label: String,
    private val log: MutableList<String>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "$label:started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        log += "$label:completed"
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        log += "$label:error"
    }
}

/** A bridge that only records what it is asked to publish. */
private class RecordingPublish(
    private val label: String,
    private val log: MutableList<String>,
) : Bridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean {
        log += "$label:bridge"
        return true
    }
}

/**
 * A frame applies every participant, inside one write bracket, before any
 * participant fans out; then each fans out in lock order — observers, bridge
 * publishes, then events — and the frame observers hear of the commit last (issue
 * #20, R9; plan decision D17). An observer of the first participant therefore
 * finds the last one already applied.
 */
class FrameCommitOrderTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
        FrameObservers.clear()
    }

    private fun <T : Any> onCommit(
        state: State<T>,
        react: (T) -> Unit,
    ) {
        var initial = true
        disposables += state.effect { if (initial) initial = false else react(this) }
    }

    /**
     * An observer of the first participant reads the last one's committed
     * value from a consistent cut — a snapshot, which reads committed values
     * only, not the frame's pending writes — so it is already applied.
     */
    @Test fun anObserverOfTheFirstParticipantFindsTheLastOneApplied() {
        val first = OrderFirst()
        val second = OrderSecond()
        assertTrue(first.lockOrderKey < second.lockOrderKey)
        var seen: Int? = null
        onCommit(first.x) { seen = second.snapshot()[second.y] }

        atomic(first, second) {
            first { x mutate 1 }
            second { y mutate 2 }
        }.getOrThrow()

        assertEquals(2, seen, "the second participant had applied before the first one fanned out")
    }

    /** Per store, in lock order: observers, then bridge publishes; the frame observer last. */
    @Test fun eachParticipantFansOutInLockOrderAfterAllHaveApplied() {
        val first = OrderFirst()
        val second = OrderSecond()
        val log = mutableListOf<String>()
        first { x bridge RecordingPublish("first", log) }
        second { y bridge RecordingPublish("second", log) }
        onCommit(first.x) { log += "first:observer(second.y=${second.snapshot()[second.y]})" }
        onCommit(second.y) { log += "second:observer(first.x=${first.snapshot()[first.x]})" }
        FrameObservers.register(
            object : FrameObserver {
                override fun onFrameCommitted(frameId: String) {
                    log += "frame:committed"
                }
            },
        )

        atomic(first, second) {
            first { x mutate 1 }
            second { y mutate 2 }
        }.getOrThrow()

        assertEquals(
            listOf(
                "first:observer(second.y=2)",
                "first:bridge",
                "second:observer(first.x=1)",
                "second:bridge",
                "frame:committed",
            ),
            log,
        )
    }

    /**
     * A participant whose fanout fails — its failure handler throws — does not
     * keep the others from fanning out: they have applied too. The frame
     * reports the failure; every participant's values stand, and no
     * participant's middleware hears of an error: all of them committed.
     */
    @Test fun aFailingFanoutOnOneParticipantStillFansOutTheOthers() {
        val first = OrderFirst()
        val second = OrderSecond()
        val hooks = mutableListOf<String>()
        first.middlewares(HookLog("first", hooks))
        second.middlewares(HookLog("second", hooks))
        first.uncaughtObserverHandler = { throw IllegalStateException("handler gave up", it) }
        onCommit(first.x) { error("observer failed") }
        var secondSaw: Int? = null
        onCommit(second.y) { secondSaw = it }
        var secondRoot: Transaction? = null

        val r =
            atomic(first, second) {
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
        assertEquals(2, secondSaw, "the second participant fanned out after the first one's fanout failed")
        assertEquals(TransactionStatus.Committed, assertNotNull(secondRoot).status)
        assertEquals(
            listOf("first:started", "second:started", "first:completed", "second:completed"),
            hooks,
            "no error hook for participants that committed",
        )
    }

    /**
     * A frame nested in an action on one of its stores joins that store as a
     * savepoint. When the apply of another participant fails, that savepoint
     * rolls back with the frame, in either lock order: none of its writes is
     * left in the enclosing action to commit with it.
     */
    @Test fun aSavepointParticipantRollsBackWhenAnotherParticipantsApplyFails() {
        for (sharedFirst in listOf(true, false)) {
            val shared: ApplyShared
            val failing: ApplyFailing
            if (sharedFirst) {
                shared = ApplyShared()
                failing = ApplyFailing()
            } else {
                failing = ApplyFailing()
                shared = ApplyShared()
            }
            assertEquals(sharedFirst, shared.lockOrderKey < failing.lockOrderKey)
            val hooks = mutableListOf<String>()
            shared.middlewares(HookLog("shared", hooks))
            failing.y.value // materialized outside the frame: only the apply pass compares
            failing.failEquals = true
            var r: TransactionResult<*>? = null

            shared
                .action {
                    r =
                        atomic(shared, failing) {
                            shared { x mutate 1 }
                            failing { y mutate Touchy(2) { false } }
                        }
                }.getOrThrow()

            val error = assertIs<TransactionResult.Error>(r, "lock order sharedFirst=$sharedFirst")
            assertTrue("state-apply" in error.exception.message.orEmpty(), error.exception.message)
            assertEquals(0, shared.x.value, "the savepoint's write rolled back with the frame (sharedFirst=$sharedFirst)")
            assertTrue("shared:error" in hooks, "the savepoint participant hears of the error: $hooks")
            failing.failEquals = false
        }
    }

    /**
     * A known limit (#20 plan amendment (d)): a frame nested in an action that
     * holds one of its stores applies only the participants it opened a root
     * for when it exits. The shared store's writes apply when the enclosing
     * action commits, so a consistent cut in between sees the frame's other
     * store new and the shared one old. Enroll every store in the outermost
     * frame to keep the frame whole.
     */
    @Test fun aNestedFrameSharingAStoreWithItsEnclosingActionAppliesInTwoSteps() {
        val first = OrderFirst()
        val second = OrderSecond()
        var between: List<StoreSnapshot>? = null

        first
            .action {
                atomic(first, second) {
                    first { x mutate 1 }
                    second { y mutate 1 }
                }.getOrThrow()
                between = captureConsistent(listOf(first, second))
            }.getOrThrow()

        val cut = assertNotNull(between)
        assertEquals(0, cut[0][first.x], "the shared store applies with the enclosing action")
        assertEquals(1, cut[1][second.y], "the store the frame opened a root for applied at its exit")
        assertEquals(1, first.x.value)
    }

    /** Status transitions: every participant of a frame ends Committed, and each one's writes are closed. */
    @Test fun everyParticipantCommitsAndEndsClosedToWrites() {
        val first = OrderFirst()
        val second = OrderSecond()
        val roots = mutableListOf<Transaction>()
        val appliedDuringFirstFanout = mutableListOf<Boolean>()
        onCommit(first.x) {
            roots += assertNotNull(first.activeTransaction)
            roots += assertNotNull(second.activeTransaction)
            roots.mapTo(appliedDuringFirstFanout) { it.applied && it.closedToWrites }
        }

        atomic(first, second) {
            first { x mutate 1 }
            second { y mutate 2 }
        }.getOrThrow()

        assertEquals(listOf(true, true), appliedDuringFirstFanout, "both roots applied before the first fanout")
        assertTrue(roots.all { it.status == TransactionStatus.Committed })
        assertTrue(roots.all { it.closedToWrites })
    }
}
