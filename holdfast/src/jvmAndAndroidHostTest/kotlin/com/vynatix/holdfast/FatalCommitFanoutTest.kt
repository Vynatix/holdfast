@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, a commit's fanout: once a commit's values are applied, a fatal
// error from an observer, a Transformer.get, a bridge publish or an inbound
// bridge write's observers is held while every other observer, bridge publish
// and event of that commit still runs, and then propagates raw out of the
// committing call — never reported, never wrapped in a TransactionException.
// The values stand. FrameCommit.kt fanOutApplied / fanOutBlocking / failCommit,
// MutableState.kt fanOutToObservers / publishToBridge / notifyObservers /
// applyFromBridge.

private class FatalFanoutStore : EventfulStore<FatalFanoutStore, String>() {
    val a by state { 0 }
    val b by state { 0 }
}

/** A Transformer whose get throws what [getFails] holds while it is set. */
private class FatalArmedGet(
    private val getFails: AtomicReference<Throwable?>,
) : Transformer<Int> {
    override fun set(value: Int): Int = value

    override fun get(value: Int): Int = getFails.get()?.let { throw it } ?: value
}

private class FatalTransformedStore : Store<FatalTransformedStore>() {
    val getFails = AtomicReference<Throwable?>(null)
    val a by state(FatalArmedGet(getFails)) { 0 }
    val b by state { 0 }
}

/** A value whose equals throws what [equalsFails] holds while it is set: a `distinct` state compares it at apply. */
private class FatalTouchy(
    val n: Int,
    private val equalsFails: AtomicReference<Throwable?>,
) {
    override fun equals(other: Any?): Boolean {
        equalsFails.get()?.let { throw it }
        return other is FatalTouchy && other.n == n
    }

    override fun hashCode(): Int = n
}

private class FatalTouchyStore : Store<FatalTouchyStore>() {
    val equalsFails = AtomicReference<Throwable?>(null)
    val t by state { FatalTouchy(0, equalsFails) }

    fun touchy(n: Int) = FatalTouchy(n, equalsFails)
}

private class FatalFollowerHost : Store<FatalFollowerHost>()

/** Records what it is published, keeps the inbound callback, and throws [publishFails] from publish when set. */
private class FatalFanoutBridge(
    private val publishFails: Throwable? = null,
) : Bridge<Int> {
    val published = CopyOnWriteArrayList<Int>()
    var inbound: ((Int) -> Unit)? = null

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable { }
    }

    override fun publish(value: Int): Boolean {
        publishFails?.let { throw it }
        return published.add(value)
    }
}

/** Logs the hooks it gets and keeps the transaction it saw start. */
private class FatalTransactionSpy<V : Store<V>> : Middleware<V>() {
    val log = mutableListOf<String>()
    var transaction: Transaction? = null

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        transaction = context.transaction
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

private fun Store<*>.reports(): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

/** Collect [store]'s events into [sink] synchronously: subscribed before this returns, delivered on the emitter. */
private fun collectEvents(
    store: FatalFanoutStore,
    sink: MutableList<String>,
): Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { store.events.collect { sink += it } }

/** Record every commit fire of [state] (skipping the initial fire) into [sink]. */
private fun <T : Any> commitsOf(
    state: State<T>,
    sink: MutableList<T>,
): Disposable {
    var initial = true
    return state effect { if (initial) initial = false else sink += this }
}

class FatalCommitFanoutTest {
    /** FrameCommit.kt:424, MutableState.kt:411: every observer and bridge of the commit runs first. */
    @Test fun anObserversStackOverflowStillNotifiesEveryOtherObserverAndBridgeThenPropagates() {
        val store = FatalFanoutStore()
        val reported = store.reports()
        val fatal = StackOverflowError()
        var armed = false
        val throwing = store.a effect { if (armed) throw fatal }
        val aSeen = mutableListOf<Int>()
        val bSeen = mutableListOf<Int>()
        val disposables = listOf(throwing, commitsOf(store.a, aSeen), commitsOf(store.b, bSeen))
        val aBridge = FatalFanoutBridge()
        store { a bridge aBridge }
        // Hosted elsewhere, so the spy below sees the action's transaction alone.
        val derived = FatalFollowerHost().derivedState(store.a) { store.a.value * 10 }
        val spy = FatalTransactionSpy<FatalFanoutStore>()
        store.middlewares(spy)
        armed = true

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    a mutate 1
                    b mutate 2
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf(1), aSeen, "the state's later observer still ran")
        assertEquals(listOf(2), bSeen, "the other state's observer still ran")
        assertEquals(listOf(1), aBridge.published.toList(), "the bridge still published")
        assertEquals(10, derived.value, "the derived state's follower queued its recompute, which settled")
        assertEquals(1, store.a.value)
        assertEquals(2, store.b.value)
        assertEquals(listOf("started", "completed"), spy.log, "the commit's failure is no error of the body")
        assertEquals(TransactionStatus.Failed, spy.transaction?.status)
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList(), "a fatal error is never reported")
        assertNull(SettleScopes.current())
        armed = false
        assertIs<TransactionResult.Success<*>>(store action { a mutate 3 })
        assertEquals(30, derived.value)
        disposables.forEach { it.dispose() }
        derived.dispose()
    }

    /** MutableState.kt:411: a fatal error the handler throws while reporting an ordinary failure is held too. */
    @Test fun aHandlerThatOverflowsWhileReportingStillLetsTheOtherObserversRun() {
        val store = FatalFanoutStore()
        val fatal = StackOverflowError()
        val handed = CopyOnWriteArrayList<Throwable>()
        store.uncaughtObserverHandler = {
            handed += it
            throw fatal
        }
        var armed = false
        val failure = IllegalStateException("observer failed")
        val throwing = store.a effect { if (armed) throw failure }
        val aSeen = mutableListOf<Int>()
        val later = commitsOf(store.a, aSeen)
        armed = true

        val thrown = assertFailsWith<StackOverflowError> { store action { a mutate 5 } }

        assertSame(fatal, thrown)
        assertEquals(listOf<Throwable>(failure), handed.toList())
        assertEquals(listOf(5), aSeen, "the later observer still ran")
        assertNull(store.activeTransaction)
        throwing.dispose()
        later.dispose()
    }

    /** Non-fatal control: an ordinary observer failure is still reported and the action still succeeds. */
    @Test fun anOrdinaryObserverFailureIsStillReportedAndTheActionSucceeds() {
        val store = FatalFanoutStore()
        val reported = store.reports()
        var armed = false
        val failure = IllegalStateException("observer failed")
        val throwing = store.a effect { if (armed) throw failure }
        val aSeen = mutableListOf<Int>()
        val later = commitsOf(store.a, aSeen)
        armed = true

        val result = store action { a mutate 5 }

        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(listOf<Throwable>(failure), reported.toList())
        assertEquals(listOf(5), aSeen)
        throwing.dispose()
        later.dispose()
    }

    /** MutableState.kt:301: a Transformer.get fatal error skips only its own state's observers. */
    @Test fun aTransformerGetStackOverflowSkipsOnlyItsOwnStatesObservers() {
        val store = FatalTransformedStore()
        val reported = store.reports()
        val aSeen = mutableListOf<Int>()
        val bSeen = mutableListOf<Int>()
        val disposables = listOf(commitsOf(store.a, aSeen), commitsOf(store.b, bSeen))
        val bBridge = FatalFanoutBridge()
        store { b bridge bBridge }
        val fatal = StackOverflowError()
        store.getFails.set(fatal)

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    a mutate 1
                    b mutate 2
                }
            }

        assertSame(fatal, thrown)
        assertEquals(emptyList(), aSeen, "the failing get skipped this state's observers")
        assertEquals(listOf(2), bSeen, "the fanout went on to the other state")
        assertEquals(listOf(2), bBridge.published.toList(), "and to the bridge publishes")
        assertEquals(emptyList(), reported.toList())
        store.getFails.set(null)
        assertEquals(1, store.a.value, "a's raw value committed")
        assertNull(store.activeTransaction)
        assertIs<TransactionResult.Success<*>>(store action { a mutate 3 })
        assertEquals(listOf(3), aSeen)
        disposables.forEach { it.dispose() }
    }

    /** MutableState.kt:323, FrameCommit.kt:373: the other publishes and the event drain still run. */
    @Test fun aBridgePublishStackOverflowStillPublishesTheOthersAndDrainsTheEvents() {
        val store = FatalFanoutStore()
        val reported = store.reports()
        val fatal = StackOverflowError()
        val aBridge = FatalFanoutBridge(publishFails = fatal)
        val bBridge = FatalFanoutBridge()
        store { a bridge aBridge }
        store { b bridge bBridge }
        val aSeen = mutableListOf<Int>()
        val bSeen = mutableListOf<Int>()
        val disposables = listOf(commitsOf(store.a, aSeen), commitsOf(store.b, bSeen))
        val events = CopyOnWriteArrayList<String>()
        val collector = collectEvents(store, events)
        val spy = FatalTransactionSpy<FatalFanoutStore>()
        store.middlewares(spy)
        try {
            val thrown =
                assertFailsWith<StackOverflowError> {
                    store action {
                        a mutate 1
                        b mutate 2
                        emit("done")
                    }
                }

            assertSame(fatal, thrown, "raw, not a TransactionException")
            assertEquals(listOf(2), bBridge.published.toList(), "the other bridge still published")
            assertEquals(listOf(1), aSeen)
            assertEquals(listOf(2), bSeen)
            assertEquals(listOf("done"), events.toList(), "the event drain still ran")
            assertEquals(TransactionStatus.Failed, spy.transaction?.status)
            assertEquals(1, store.a.value)
            assertEquals(2, store.b.value)
            assertEquals(emptyList(), reported.toList())
            assertNull(store.activeTransaction)
            // The bridge lock was released: another thread can detach the bridge.
            val detacher = daemon("fatal-fanout-detach") { store { a bridge null } }
            detacher.join(10_000)
            assertFalse(detacher.isAlive, "the bridge's lock was released")
            assertIs<TransactionResult.Success<*>>(store action { a mutate 3 })
        } finally {
            collector.cancel()
            disposables.forEach { it.dispose() }
        }
    }

    /** FrameCommit.kt:424: a handler overflowing on an ordinary publish failure no longer ends the fanout early. */
    @Test fun aHandlerThatOverflowsReportingAPublishFailureStillPublishesTheOthers() {
        val store = FatalFanoutStore()
        val fatal = StackOverflowError()
        val handed = CopyOnWriteArrayList<Throwable>()
        store.uncaughtObserverHandler = {
            handed += it
            throw fatal
        }
        val failure = IllegalStateException("publish failed")
        val bBridge = FatalFanoutBridge()
        store { a bridge FatalFanoutBridge(publishFails = failure) }
        store { b bridge bBridge }
        val events = CopyOnWriteArrayList<String>()
        val collector = collectEvents(store, events)
        try {
            val thrown =
                assertFailsWith<StackOverflowError> {
                    store action {
                        a mutate 1
                        b mutate 2
                        emit("done")
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(listOf<Throwable>(failure), handed.toList())
            assertEquals(listOf(2), bBridge.published.toList(), "the other bridge still published")
            assertEquals(listOf("done"), events.toList(), "the events still drained")
            assertNull(store.activeTransaction)
        } finally {
            collector.cancel()
        }
    }

    /** MutableState.kt:370: the inbound value is written and the store's followers told, then it propagates. */
    @Test fun anInboundBridgeValueIsWrittenAndFollowersToldWhenAnObserverOverflows() {
        val store = FatalFanoutStore()
        val host = FatalFollowerHost()
        val reported = store.reports()
        val hostReported = host.reports()
        val inbound = FatalFanoutBridge()
        store { a bridge inbound }
        val fatal = StackOverflowError()
        val throwing = store.a effect { if (this == 7) throw fatal }
        var computes = 0
        val follower =
            host.derivedStateOverStores("follower", sourceStores = listOf(store)) {
                computes++
                store.a.value
            }
        val before = computes

        val thrown = assertFailsWith<StackOverflowError> { checkNotNull(inbound.inbound)(7) }

        assertSame(fatal, thrown)
        assertEquals(7, store.a.value, "the inbound value was written")
        assertTrue(computes > before, "the whole-store follower recomputed")
        assertEquals(7, follower.value)
        assertEquals(emptyList(), reported.toList())
        assertEquals(emptyList(), hostReported.toList())
        assertNothingLeft(store)
        throwing.dispose()
        follower.dispose()
    }

    /** FrameCommit.kt:454/249/211: an apply-pass fatal error is returned raw by failCommit; no bracket stays open. */
    @Test fun aDistinctEqualsStackOverflowFailsTheCommitRawAndLeavesTheStoreUsable() {
        val store = FatalTouchyStore()
        val reported = store.reports()
        val spy = FatalTransactionSpy<FatalTouchyStore>()
        store.middlewares(spy)
        assertEquals(0, store.t.value.n)
        val fatal = StackOverflowError()
        store.equalsFails.set(fatal)

        val thrown = assertFailsWith<StackOverflowError> { store action { t mutate touchy(1) } }

        assertSame(fatal, thrown, "the apply's fatal error is not wrapped in a TransactionException")
        assertEquals(listOf("started", "completed"), spy.log, "it struck in the commit")
        assertEquals(TransactionStatus.Failed, spy.transaction?.status)
        assertNull(store.activeTransaction)
        assertEquals(emptyList(), reported.toList())
        store.equalsFails.set(null)
        assertEquals(0, store.t.value.n, "the failed apply assigned nothing")
        assertEquals(0, store.snapshot()[store.t]?.n, "the write bracket closed: a consistent cut is taken")
        assertIs<TransactionResult.Success<*>>(store action { t mutate touchy(2) })
        assertEquals(2, store.t.value.n)
    }

    private fun assertNothingLeft(store: Store<*>) {
        assertNull(store.activeTransaction)
        assertNull(SettleScopes.current())
        assertNull(NoWriteRegion.current())
    }
}
