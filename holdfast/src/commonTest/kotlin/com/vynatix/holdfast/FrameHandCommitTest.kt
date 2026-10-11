@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class HandCommitSource : Store<HandCommitSource>() {
    val trigger by state { 0 }
}

private class HandCommitTarget : Store<HandCommitTarget>() {
    val copy by state { 0 }
}

/** A bridge that logs what it is asked to publish. */
private class LoggingBridge(
    private val label: String,
    private val log: MutableList<String>,
) : Bridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean {
        log += "$label=$value"
        return true
    }
}

/**
 * A `commit()` by hand on a transaction whose writes have already applied is
 * a no-op (issue #20, R9 and D16; PR #22 review).
 *
 * A frame applies every participant before any fans out, so while `s` fans
 * out, `t`'s root is applied but not yet fanned out — and still installed as
 * `t`'s active transaction, and handed to t's middleware as its context. A
 * `commit()` on it from s's observer used to re-run the apply pass over its
 * emptied buffers, replace the fanout's input with nothing and mark it
 * Committed; when the frame reached `t`, nothing was left to fan out, so t's
 * observers and bridges never fired while its values stood and the frame
 * reported Success. Every case here asserts the hand commit changes nothing:
 * the fanout belongs to the frame (or to the action's own commit), and runs
 * exactly once, in its turn.
 */
class FrameHandCommitTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    /** Subscribe [react] to [state]'s commits only (the initial fire is skipped). */
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

    private fun Store<*>.collectFailures(into: MutableList<Throwable> = mutableListOf()): MutableList<Throwable> {
        uncaughtObserverHandler = { into += it }
        return into
    }

    @Test fun handCommitOfALaterParticipantFromAnObserverIsANoOp() {
        val s = HandCommitSource()
        val t = HandCommitTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = t.collectFailures(s.collectFailures())
        val log = mutableListOf<String>()
        var root: Transaction? = null
        t { copy bridge LoggingBridge("t.bridge", log) }
        onCommit(s.trigger) { value ->
            log += "s.trigger=$value"
            root = t.activeTransaction
            root?.commit()
        }
        onCommit(t.copy) { value -> log += "t.copy=$value" }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(
            listOf("s.trigger=1", "t.copy=2", "t.bridge=2"),
            log,
            "t's observer and bridge fire exactly once, from the frame's own fanout, after s's",
        )
        assertEquals(2, t.copy.value)
        assertEquals(TransactionStatus.Committed, assertNotNull(root, "s's observer saw t's frame root").status)
        assertEquals(emptyList(), failures)
    }

    /** The same root reaches an observer through the middleware context of t's frame transaction. */
    @Test fun handCommitThroughAMiddlewareContextIsANoOp() {
        val s = HandCommitSource()
        val t = HandCommitTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = t.collectFailures(s.collectFailures())
        var captured: Transaction? = null
        t.middlewares(
            object : Middleware<HandCommitTarget>() {
                override fun onTransactionStarted(context: MiddlewareContext<HandCommitTarget>) {
                    captured = context.transaction
                }
            },
        )
        val seen = mutableListOf<Int>()
        onCommit(s.trigger) { captured?.commit() }
        onCommit(t.copy) { value -> seen += value }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(listOf(2), seen, "t's observer fires exactly once")
        assertEquals(2, t.copy.value)
        assertEquals(TransactionStatus.Committed, assertNotNull(captured).status)
        assertEquals(emptyList(), failures)
    }

    /**
     * No frame: a single action's root stays installed while it fans out. A
     * hand commit from its own observer used to re-apply the emptied buffers
     * and turn the root Committed under the running fanout, whose own status
     * transition then failed — the action returned Error for a commit that
     * had applied and fanned out in full.
     */
    @Test fun handCommitFromAnObserverDuringAnActionsOwnFanoutIsANoOp() {
        val s = HandCommitSource()
        val failures = s.collectFailures()
        val seen = mutableListOf<Int>()
        var root: Transaction? = null
        onCommit(s.trigger) { value ->
            seen += value
            root = s.activeTransaction
            root?.commit()
        }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(listOf(1), seen, "the observer fires exactly once")
        assertEquals(1, s.trigger.value)
        assertEquals(TransactionStatus.Committed, assertNotNull(root, "the observer saw the action's root").status)
        assertEquals(emptyList(), failures)
    }
}

/**
 * A `rollback()` by hand on a transaction whose writes have already applied
 * is a no-op as well (PR #22 review): there is nothing left to discard — the
 * values stand — and the fanout belongs to the frame (or to the action's own
 * commit). It used to turn such a root RolledBack, so the frame's later fanout
 * ran every observer and then failed its own status transition: the frame
 * reported Error, and `onFrameRolledBack` fired, for a commit that had applied
 * and fanned out in full.
 */
class FrameHandRollbackTest {
    private val disposables = mutableListOf<Disposable>()
    private val frameEvents = mutableListOf<String>()
    private val frameObserver =
        object : FrameObserver {
            override fun onFrameCommitted(frameId: String) {
                frameEvents += "committed"
            }

            override fun onFrameRolledBack(
                frameId: String,
                cause: Throwable,
            ) {
                frameEvents += "rolledBack: $cause"
            }
        }

    @AfterTest fun cleanup() {
        FrameObservers.unregister(frameObserver)
        disposables.forEach { it.dispose() }
    }

    /** Subscribe [react] to [state]'s commits only (the initial fire is skipped). */
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

    @Test fun handRollbackOfALaterParticipantFromAnObserverIsANoOp() {
        val s = HandCommitSource()
        val t = HandCommitTarget()
        assertTrue(s.lockOrderKey < t.lockOrderKey)
        val failures = mutableListOf<Throwable>()
        s.uncaughtObserverHandler = { failures += it }
        t.uncaughtObserverHandler = { failures += it }
        FrameObservers.register(frameObserver)
        val seen = mutableListOf<Int>()
        var root: Transaction? = null
        onCommit(s.trigger) {
            root = t.activeTransaction
            root?.rollback()
        }
        onCommit(t.copy) { value -> seen += value }

        val r =
            atomic(s, t) {
                s { trigger mutate 1 }
                t { copy mutate 2 }
            }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(listOf(2), seen, "t's observer fires exactly once, from the frame's fanout")
        assertEquals(2, t.copy.value)
        assertEquals(TransactionStatus.Committed, assertNotNull(root, "s's observer saw t's frame root").status)
        assertEquals(listOf("committed"), frameEvents, "the frame committed, and reported no rollback")
        assertEquals(emptyList(), failures)
    }

    @Test fun handRollbackFromAnObserverDuringAnActionsOwnFanoutIsANoOp() {
        val s = HandCommitSource()
        val failures = mutableListOf<Throwable>()
        s.uncaughtObserverHandler = { failures += it }
        val seen = mutableListOf<Int>()
        var root: Transaction? = null
        onCommit(s.trigger) { value ->
            seen += value
            root = s.activeTransaction
            root?.rollback()
        }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(listOf(1), seen, "the observer fires exactly once")
        assertEquals(1, s.trigger.value)
        assertEquals(TransactionStatus.Committed, assertNotNull(root, "the observer saw the action's root").status)
        assertEquals(emptyList(), failures)
    }

    /** The no-op is for consumed buffers only: before its apply pass a transaction rolls back as ever. */
    @Test fun handRollbackBeforeTheApplyPassStillDiscards() {
        val s = HandCommitSource()
        val seen = mutableListOf<Int>()
        onCommit(s.trigger) { value -> seen += value }
        var root: Transaction? = null

        val r =
            s action {
                trigger mutate 1
                root = s.activeTransaction
                root?.rollback()
            }

        assertIs<TransactionResult.Success<*>>(r, "the rolled-back transaction's own commit is a no-op")
        assertEquals(emptyList(), seen, "nothing applied, nothing fanned out")
        assertEquals(0, s.trigger.value)
        assertEquals(TransactionStatus.RolledBack, assertNotNull(root).status)
    }
}
