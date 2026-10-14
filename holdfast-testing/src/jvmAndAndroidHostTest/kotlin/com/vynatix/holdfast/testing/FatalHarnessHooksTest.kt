@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Transformer
import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.concurrency.awaiting
import com.vynatix.holdfast.testing.concurrency.eventually
import com.vynatix.holdfast.testing.internal.Recorder
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.tree.keyed
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Issue #61, the harness's own catch-alls around user code: the recorder's
// completed hook (Recorder.kt), a lost track race's bridge hand-over
// (StoreHandle.yieldToInternal, RecordingBridgeWrapper.unwrap), track(tree)'s
// unwind (TreeHandle), `eventually` and `awaiting`. A fatal error is never
// recorded, retried or degraded: it propagates, after whatever unwind the
// site owes.

private class FhhCounter : Store<FhhCounter>() {
    val count by state { 0 }
}

/** A transformer whose `get` throws [failure] once it is set: what the recorder's committed-value read runs. */
private class FhhGetTransformer : Transformer<Int> {
    @Volatile var failure: Throwable? = null

    override fun set(value: Int): Int = value

    override fun get(value: Int): Int {
        failure?.let { throw it }
        return value
    }
}

private class FhhTransformed(
    transformer: FhhGetTransformer,
) : Store<FhhTransformed>() {
    val x by state(transformer = transformer) { 0 }
}

/** A bridge whose `observe` throws [failure] once it is set: what a re-attach (wrap or unwrap) runs. */
private class FhhObserveBridge : Bridge<Int> {
    @Volatile var failure: Throwable? = null
    val observes = AtomicInteger()

    override fun observe(observer: (Int) -> Unit): Disposable {
        observes.incrementAndGet()
        failure?.let { throw it }
        return Disposable { }
    }

    override fun publish(value: Int): Boolean = true
}

private class FhhBridged : Store<FhhBridged>() {
    val x by state { 0 }
    val y by state { 0 }
}

private class FhhLeaf : Store<FhhLeaf>() {
    val n by state { 0 }
}

private class FhhParent : Store<FhhParent>() {
    val own by state { 0 }

    @Volatile var childFailure: Throwable? = null
    val a by store {
        childFailure?.let { throw it }
        FhhLeaf()
    }
    val threads by keyed<String, FhhLeaf> { FhhLeaf() }
}

private fun Store<*>.fhhReports(): MutableList<Throwable> =
    CopyOnWriteArrayList<Throwable>().also { seen -> uncaughtObserverHandler = { seen += it } }

/** How many tree middlewares (`StoreTree.middlewares`) sit in this store's outer ring. */
private fun Store<*>.treeRingSize(): Int = snapshotMiddleware().count { it::class.simpleName == "TreeMiddlewareAdapter" }

/**
 * [thrown] is [expected], or the copy kotlinx.coroutines' stack-trace
 * recovery made of it where it crossed a coroutine boundary (a
 * `withTimeoutOrNull` resuming its caller, with assertions on, as under
 * Gradle): the same class, [expected] its cause. Never a wrapper of another
 * class, and never an AssertionError.
 */
private fun assertSameFatal(
    expected: Throwable,
    thrown: Throwable,
) {
    if (thrown === expected) return
    assertSame(expected.javaClass, thrown.javaClass, "a recovered copy keeps the class: $thrown")
    assertSame(expected, thrown.cause, "a recovered copy carries the original as its cause: $thrown")
}

/** The live `awaiting` subscribers of [this] recorder (private state: read reflectively, JVM only). */
private fun Recorder<*>.subscriberCount(): Int {
    val field = Recorder::class.java.getDeclaredField("subscribers").apply { isAccessible = true }
    return (field.get(this) as List<*>).size
}

class FatalHarnessHooksTest {
    /**
     * Recorder.kt:242 (the hook path; `modifiedStates` itself runs no user
     * code): the recorder's completed hook reads committed values through
     * the state's `Transformer.get`, which overflows. Core's middleware
     * contract rolls the action back and throws it — the timeline ends
     * errored and rolled back, no Error is pending on the handle.
     */
    @Test
    fun aFatalErrorInTheRecordersCompletedHookRollsTheActionBackAndPropagates() =
        storeTest {
            val transformer = FhhGetTransformer()
            val store = FhhTransformed(transformer)
            val reported = store.fhhReports()
            val h = track(store)
            val boom = StackOverflowError()
            val thrown =
                assertFailsWith<StackOverflowError> {
                    h.action {
                        x mutate 1
                        transformer.failure = boom // from here on, the recorder's reads overflow
                    }
                }
            transformer.failure = null
            assertSame(boom, thrown)
            assertEquals(0, store.x.value, "rolled back")
            assertNull(store.activeTransaction)
            assertIs<TransactionRolledBack>(h.timeline.last())
            val errored = h.timeline.filterIsInstance<TransactionErrored>()
            assertSame(boom, errored.single().cause)
            assertTrue(h.timeline.none { it is TransactionCommitted })
            assertTrue(h.pendingErrors.isEmpty(), "a fatal error is never a pending Error")
            h.action { x mutate 2 }.shouldBeSuccess()
            assertEquals(2, store.x.value)
            assertEquals(emptyList(), reported.toList())
        }

    /**
     * StoreHandle.kt:423 with RecordingBridgeWrapper.kt:137: a handle that
     * lost the registration race unwraps its bridge wrappers for a
     * `Capture.None` winner; re-attaching `x`'s own bridge overflows in its
     * `observe`. Every later wrapper is still unwrapped and the loser's
     * recorder still comes off; then the error is thrown out of `register`.
     */
    @Test
    fun aLostTrackRaceHoldsAnUnwrapsFatalErrorWhileEveryWrapperIsHandedOver() =
        storeTest {
            val store = FhhBridged()
            val reported = store.fhhReports()
            val xBridge = FhhObserveBridge()
            val yBridge = RecordingBridge(0)
            store {
                x bridge xBridge
                y bridge yBridge
            }
            assertEquals(listOf("x", "y"), store.properties.keys.toList(), "x's wrapper is handed over first")
            val loser = StoreHandle(store, Capture.All) // built first: wraps x and y
            val winner = StoreHandle(store, Capture.None) // records nothing, wraps nothing
            assertSame(winner, registry.register(winner))
            val boom = StackOverflowError()
            xBridge.failure = boom
            val thrown = assertFailsWith<StackOverflowError> { registry.register(loser) }
            xBridge.failure = null
            assertSame(boom, thrown)

            @Suppress("UNCHECKED_CAST")
            val yAttached = (store.y as MutableState<Int>).bridge
            assertSame(yBridge, yAttached, "the later wrapper was still unwrapped")

            @Suppress("UNCHECKED_CAST")
            val xAttached = (store.x as MutableState<Int>).bridge
            assertSame(xBridge, xAttached, "x's own bridge is back, though its observe overflowed")
            assertTrue(store.snapshotMiddleware().none { it === loser.recorder }, "the loser's recorder came off")
            assertSame(winner, registry.find(store))
            assertSame(winner, track(store))
            store action {
                x mutate 1
                y mutate 1
            }
            assertTrue(loser.timeline.isEmpty(), "a later action records nothing on the loser")
            assertEquals(listOf(1), yBridge.published)
            assertEquals(emptyList(), reported.toList())
        }

    /**
     * TreeHandle.kt:82 (the bridge variant: a member's bridge overflows in
     * `observe` while its handle wraps it during adoption): both unwind
     * steps run — the membership listener is disposed and the tree recorder
     * comes off every member's ring — and the same error propagates out of
     * `track(tree)`. A fresh `track(tree)` afterwards installs one recorder.
     */
    @Test
    fun trackTreeUnwindsEveryStepWhenAdoptingAMemberThrowsAFatalError() =
        storeTest {
            val parent = FhhParent()
            val a = parent.a
            val bridge = FhhObserveBridge()
            a { n bridge bridge }
            val reported = listOf(parent, a).associateWith { it.fhhReports() }
            val boom = StackOverflowError()
            bridge.failure = boom
            val thrown = assertFailsWith<StackOverflowError> { track(parent.tree) }
            bridge.failure = null
            assertSame(boom, thrown)
            assertEquals(0, parent.treeRingSize(), "the tree recorder came off the receiver")
            assertEquals(0, a.treeRingSize(), "and off the member")
            val late = parent.threads.create("late")
            assertNull(registry.find(late), "the membership listener was disposed: a later member is not tracked")
            assertEquals(0, late.treeRingSize())
            assertIs<TransactionResult.Success<*>>(parent action { own mutate 1 })

            val tree = track(parent.tree)
            assertEquals(listOf(1, 1, 1), listOf(parent, a, late).map { it.treeRingSize() }, "one recorder each")
            parent action { own mutate 2 }
            assertTrue(tree.timeline.isNotEmpty())
            reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
        }

    /**
     * TreeHandle's first step: `tree.middlewares` materializes the subtree,
     * so a child lambda that overflows fails `track(tree)` before any
     * unwind is owed (the adopt-step listing never meets it). The same
     * error propagates and nothing is installed.
     */
    @Test
    fun trackTreeThrowsAChildLambdasFatalErrorWithNothingInstalled() =
        storeTest {
            val parent = FhhParent()
            val boom = StackOverflowError()
            parent.childFailure = boom
            val thrown = assertFailsWith<StackOverflowError> { track(parent.tree) }
            parent.childFailure = null
            assertSame(boom, thrown)
            assertEquals(0, parent.treeRingSize())
            assertNull(registry.find(parent), "nothing was adopted")
            val late = parent.threads.create("late")
            assertNull(registry.find(late), "no membership listener was left behind")

            val tree = track(parent.tree)
            parent.a action { n mutate 1 }
            assertTrue(tree.timeline.isNotEmpty(), "the child is retryable and the tree trackable")
        }

    /** Eventually.kt:51: a fatal error is never retried nor wrapped in the give-up AssertionError. */
    @Test
    fun eventuallyNeverRetriesAFatalError() =
        storeTest {
            val calls = AtomicInteger()
            val boom = StackOverflowError()
            val thrown =
                assertFailsWith<StackOverflowError> {
                    eventually(within = 1.seconds) {
                        calls.incrementAndGet()
                        throw boom
                    }
                }
            assertSameFatal(boom, thrown)
            assertEquals(1, calls.get(), "not retried")
        }

    /** Non-fatal control for Eventually.kt:51: an ordinary failure is still retried until the budget ends. */
    @Test
    fun eventuallyStillRetriesAnOrdinaryFailure() =
        storeTest {
            val calls = AtomicInteger()
            val failure =
                assertFailsWith<AssertionError> {
                    eventually(within = 100.milliseconds, every = 10.milliseconds) {
                        calls.incrementAndGet()
                        throw AssertionError("not yet")
                    }
                }
            assertTrue(calls.get() > 1, "retried: ${calls.get()} calls")
            assertContains(failure.message!!, "gave up")
            assertEquals("not yet", failure.cause?.message)
        }

    /**
     * Awaiting.kt:105 (unchanged, a typed catch): a predicate that overflows
     * on a replayed event propagates the error, and the subscription is
     * gone afterwards.
     */
    @Test
    fun awaitingPropagatesAReplayedPredicatesFatalErrorAndUnsubscribes() =
        storeTest {
            val h = track(FhhCounter())
            h.action { count mutate 1 }.shouldBeSuccess()
            val boom = StackOverflowError()
            val thrown = assertFailsWith<StackOverflowError> { awaiting { throw boom } }
            assertSame(boom, thrown)
            assertEquals(0, h.recorder!!.subscriberCount(), "no subscriber channel is left registered")
            assertIs<TransactionCommitted>(awaiting { it is TransactionCommitted }, "awaiting still works")
        }

    /** Awaiting.kt:105: the same for a live event, delivered once `awaiting` has suspended. */
    @Test
    fun awaitingPropagatesALivePredicatesFatalErrorAndUnsubscribes() =
        storeTest {
            val h = track(FhhCounter())
            val boom = StackOverflowError()
            val writer = launch { h.action { count mutate 2 }.shouldBeSuccess() }
            val thrown = assertFailsWith<StackOverflowError> { awaiting { throw boom } }
            writer.join()
            assertSameFatal(boom, thrown)
            assertEquals(0, h.recorder!!.subscriberCount(), "no subscriber channel is left registered")
            assertEquals(2, h.read { count.value })
        }
}
