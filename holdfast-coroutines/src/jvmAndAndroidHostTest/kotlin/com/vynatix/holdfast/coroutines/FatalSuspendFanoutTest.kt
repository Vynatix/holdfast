@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.Codec
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Issue #61, a suspending commit's fanout (AsyncSerializer.kt
// fanOutThenPublish) and the suspending key-value bridges (SuspendingBridge.kt).
// The values of a commit are applied before its fanout, so a fatal error a
// bridge, an observer or an event collector throws there ends nothing early:
// the commit's other observers, bridge publishes and events still run, then
// the committing suspendAction throws it — never reported, never an Error.
// A key-value bridge never puts a fatal error on its `errors` flow: it
// propagates out of publishAwaited, or out of the save or load coroutine to
// its scope's CoroutineExceptionHandler.

private data class FatalFanEvent(
    val n: Int,
)

private class FatalFanStore : EventfulStore<FatalFanStore, FatalFanEvent>() {
    val x by state { 0 }
    val y by state { 0 }
}

private class FatalKvBridgedStore : Store<FatalKvBridgedStore>() {
    val s by state { 0 }
    val t by state { 0 }
}

/**
 * A scope (a SupervisorJob on Dispatchers.Default) whose
 * CoroutineExceptionHandler records what its failed coroutines handed it.
 */
internal class FatalCatchingScope : AutoCloseable {
    val caught = CopyOnWriteArrayList<Throwable>()
    private val firstCaught = CompletableDeferred<Throwable>()
    val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, e ->
                    caught += e
                    firstCaught.complete(e)
                },
        )

    /** What the first failed coroutine handed the handler — at most 10 s away — as the library threw it. */
    suspend fun first(): Throwable = withTimeout(10.seconds) { firstCaught.await() }.unrecovered()

    override fun close() {
        scope.cancel()
    }
}

/** A plain bridge recording what it was published, running [onPublish] first. */
private class FatalFanBridge(
    private val onPublish: (Int) -> Unit = {},
) : Bridge<Int> {
    val published = CopyOnWriteArrayList<Int>()

    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

    override fun publish(value: Int): Boolean {
        onPublish(value)
        published += value
        return true
    }
}

/** An await-completion bridge whose publish times out while [timesOut] says so. */
private class FatalTimingOutBridge(
    private val timesOut: () -> Boolean,
) : SuspendingBridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

    override fun publish(value: Int): Boolean = true

    override suspend fun publishAwaited(value: Int) {
        if (timesOut()) withTimeout(10.milliseconds) { awaitCancellation() }
    }
}

/** An Int codec whose `encode`/`decode` throw what [encodeFails]/[decodeFails] hold while set. */
private class FatalFailingIntCodec : Codec<Int> {
    @Volatile var encodeFails: Throwable? = null

    @Volatile var decodeFails: Throwable? = null

    override fun encode(value: Int): String {
        encodeFails?.let { throw it }
        return IntCodec.encode(value)
    }

    override fun decode(string: String): Int {
        decodeFails?.let { throw it }
        return IntCodec.decode(string)
    }
}

/** Collect [flow] into a list from now on, in [this] scope; cancel the returned job when done. */
private fun <T> CoroutineScope.collecting(
    flow: SharedFlow<T>,
    into: MutableList<T>,
): Job = launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { into += it } }

/** Which entry commits: see FatalSuspendFanoutTest.fanoutAfterAnObserversStackOverflow. */
private enum class CommitBy { ACTION, SUSPEND_ACTION, SUSPEND_ATOMIC }

/** Let collectors resumed by what just happened run (they share this runBlocking thread). */
private suspend fun letCollectorsRun() = repeat(5) { yield() }

class FatalSuspendFanoutTest {
    @Test fun aBridgeStackOverflowLetsTheOtherPublishesAndTheEventsRunThenPropagates() =
        runBlocking<Unit> {
            val store = FatalFanStore()
            val reported = store.recordedReports()
            val fatal = StackOverflowError()
            var armed = true
            val bridgeX = FatalFanBridge { if (armed) throw fatal }
            val bridgeY = FatalFanBridge()
            store {
                x bridge bridgeX
                y bridge bridgeY
            }
            val events = CopyOnWriteArrayList<FatalFanEvent>()
            val collector = collecting(store.events, events)

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        y mutate 2
                        emit(FatalFanEvent(3))
                    }
                }
            armed = false
            letCollectorsRun()
            collector.cancel()

            assertSame(fatal, thrown, "never a TransactionResult.Error")
            assertEquals(listOf(2), bridgeY.published.toList(), "the other state still published")
            assertEquals(listOf(FatalFanEvent(3)), events.toList(), "the event still went out")
            assertEquals(1, store.x.value, "the commit stands")
            assertEquals(2, store.y.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertNothingInstalledOn(store)
            assertNothingLeftOnThisThread()
            assertIs<TransactionResult.Success<*>>(store.suspendAction { x mutate 5 })
        }

    @Test fun aNonFatalBridgeFailureIsStillReportedAndTheCommitSucceeds() =
        runBlocking<Unit> {
            val store = FatalFanStore()
            val reported = store.recordedReports()
            val boom = IllegalStateException("publish")
            val bridgeY = FatalFanBridge()
            store {
                x bridge FatalFanBridge { throw boom }
                y bridge bridgeY
            }

            val result =
                store.suspendAction {
                    x mutate 1
                    y mutate 2
                }

            assertIs<TransactionResult.Success<*>>(result)
            assertEquals(listOf(2), bridgeY.published.toList())
            assertEquals(listOf<Throwable>(boom), reported.toList(), "reported, as before")
        }

    @Test fun aBridgeStackOverflowOutranksALaterPublishTimeout() =
        runBlocking<Unit> {
            val store = FatalFanStore()
            val reported = store.recordedReports()
            val fatal = StackOverflowError()
            var armed = true
            store {
                x bridge FatalFanBridge { if (armed) throw fatal }
                y bridge FatalTimingOutBridge { armed }
            }

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        y mutate 2
                    }
                }
            armed = false

            assertSame(fatal, thrown, "the fatal error outranks the cancellation")
            assertTrue(
                thrown.suppressedExceptions.any { it is TimeoutCancellationException },
                "the timed-out publish rides along as suppressed: ${thrown.suppressedExceptions}",
            )
            assertEquals(1, store.x.value)
            assertEquals(2, store.y.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertNothingInstalledOn(store)
            assertIs<TransactionResult.Success<*>>(store.suspendAction { x mutate 5 })
        }

    /**
     * The blocking commit's fanout (core fanOutBlocking) holds an observer's
     * fatal error until the rest of the commit's fanout ran (effect's KDoc:
     * "the other observers, bridges and events of that commit still run,
     * then the committing call throws it"). The control for the next test.
     */
    @Test fun anObserverStackOverflowInABlockingActionLetsTheRestOfItsFanoutRun() =
        runBlocking<Unit> {
            assertEquals(emptyList(), fanoutAfterAnObserversStackOverflow(CommitBy.ACTION))
        }

    /**
     * suspendAction must agree with the blocking commit: an observer's fatal
     * error ends nothing early — the other states' observers, every bridge
     * publish and the events of the commit still run, then it propagates.
     */
    @Test fun anObserverStackOverflowInASuspendActionLetsTheRestOfItsFanoutRun() =
        runBlocking<Unit> {
            assertEquals(emptyList(), fanoutAfterAnObserversStackOverflow(CommitBy.SUSPEND_ACTION))
        }

    /** The same for one participant of a suspendAtomic frame (its per-root suspendingFanOut). */
    @Test fun anObserverStackOverflowInASuspendAtomicParticipantLetsTheRestOfItsFanoutRun() =
        runBlocking<Unit> {
            assertEquals(emptyList(), fanoutAfterAnObserversStackOverflow(CommitBy.SUSPEND_ATOMIC))
        }

    /**
     * Commit x = 1, y = 2 and an event [by] an action, a suspendAction or a
     * suspendAtomic frame, while x's observer throws a StackOverflowError for
     * 1; answer what of the rest of the commit's fanout did not happen (empty
     * when all of it did).
     */
    private suspend fun CoroutineScope.fanoutAfterAnObserversStackOverflow(by: CommitBy): List<String> {
        val store = FatalFanStore()
        val reported = store.recordedReports()
        val fatal = StackOverflowError()
        store.x effect { if (this == 1) throw fatal }
        val seenY = CopyOnWriteArrayList<Int>()
        store.y effect { seenY += this }
        val bridgeX = FatalFanBridge()
        val bridgeY = FatalFanBridge()
        store {
            x bridge bridgeX
            y bridge bridgeY
        }
        val events = CopyOnWriteArrayList<FatalFanEvent>()
        val collector = collecting(store.events, events)

        val thrown =
            assertPropagates<StackOverflowError> {
                when (by) {
                    CommitBy.ACTION ->
                        store action {
                            x mutate 1
                            y mutate 2
                            emit(FatalFanEvent(3))
                        }
                    CommitBy.SUSPEND_ACTION ->
                        store.suspendAction {
                            x mutate 1
                            y mutate 2
                            emit(FatalFanEvent(3))
                        }
                    CommitBy.SUSPEND_ATOMIC ->
                        suspendAtomic(store) {
                            store {
                                x mutate 1
                                y mutate 2
                                emit(FatalFanEvent(3))
                            }
                        }
                }
            }
        letCollectorsRun()
        collector.cancel()

        assertSame(fatal, thrown, "never a TransactionResult.Error")
        assertEquals(1, store.x.value, "the commit stands")
        assertEquals(2, store.y.value, "the commit stands")
        assertTrue(reported.isEmpty(), "never reported: $reported")
        assertNothingInstalledOn(store)
        return buildList {
            if (seenY.toList() != listOf(0, 2)) add("y's observer saw ${seenY.toList()}, not [0, 2]")
            if (bridgeX.published.toList() != listOf(1)) add("x's bridge published ${bridgeX.published}, not [1]")
            if (bridgeY.published.toList() != listOf(2)) add("y's bridge published ${bridgeY.published}, not [2]")
            if (events.toList() != listOf(FatalFanEvent(3))) add("the collector got ${events.toList()}, not the event")
        }
    }

    @Test fun anAwaitedKvBridgeEncodeStackOverflowPropagatesAfterTheOtherPublishesAndNeverReachesErrors() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = InMemorySuspendingKvStore()
                val fatal = StackOverflowError()
                val codec = FatalFailingIntCodec()
                val bridgeS = kv.suspendingBridge("s", codec, handled.scope)
                val bridgeT = kv.suspendingBridge("t", IntCodec, handled.scope)
                val store = FatalKvBridgedStore()
                val reported = store.recordedReports()
                store {
                    s bridge bridgeS
                    t bridge bridgeT
                }
                val errors = CopyOnWriteArrayList<Throwable>()
                val collectors = listOf(collecting(bridgeS.errors, errors), collecting(bridgeT.errors, errors))
                codec.encodeFails = fatal

                val thrown =
                    assertPropagates<StackOverflowError> {
                        store.suspendAction {
                            s mutate 1
                            t mutate 2
                        }
                    }
                codec.encodeFails = null
                letCollectorsRun()
                collectors.forEach { it.cancel() }

                assertSame(fatal, thrown)
                assertEquals(1, store.s.value, "the commit stands")
                assertEquals("2", kv.get("t"), "the other state's publish still ran")
                assertNull(kv.get("s"))
                assertTrue(errors.isEmpty(), "never on the errors flow: $errors")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                assertTrue(handled.caught.isEmpty(), "nothing failed on the bridge scope: ${handled.caught}")
                assertNothingInstalledOn(store)
            }
        }

    @Test fun aNonFatalKvBridgeEncodeFailureStillGoesToErrors() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = InMemorySuspendingKvStore()
                val codec = FatalFailingIntCodec()
                val bridgeS = kv.suspendingBridge("s", codec, handled.scope)
                val store = FatalKvBridgedStore()
                store { s bridge bridgeS }
                val errors = CopyOnWriteArrayList<Throwable>()
                val collector = collecting(bridgeS.errors, errors)
                val boom = IllegalStateException("encode")
                codec.encodeFails = boom

                val result = store.suspendAction { s mutate 1 }
                codec.encodeFails = null
                letCollectorsRun()
                collector.cancel()

                assertIs<TransactionResult.Success<*>>(result)
                assertEquals(listOf<Throwable>(boom), errors.toList())
            }
        }

    @Test fun aFireAndForgetKvBridgeEncodeStackOverflowGoesToTheScopesHandlerNotErrors() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = InMemorySuspendingKvStore()
                val fatal = StackOverflowError()
                val codec = FatalFailingIntCodec()
                val bridgeS = kv.bridge("s", codec, handled.scope)
                val store = FatalKvBridgedStore()
                val reported = store.recordedReports()
                store { s bridge bridgeS }
                val errors = CopyOnWriteArrayList<Throwable>()
                val collector = collecting(bridgeS.errors, errors)
                codec.encodeFails = fatal

                val result = store action { s mutate 1 }

                assertIs<TransactionResult.Success<*>>(result, "the save is fire-and-forget")
                assertSame(fatal, handled.first(), "the drainer's failure reached its scope's handler")
                letCollectorsRun()
                collector.cancel()
                assertTrue(errors.isEmpty(), "never on the errors flow: $errors")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                assertNull(kv.get("s"))
            }
        }

    @Test fun aFireAndForgetKvBridgeKeepsSavingAfterAStackOverflow() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = InMemorySuspendingKvStore()
                val fatal = StackOverflowError()
                val codec = FatalFailingIntCodec()
                val bridgeS = kv.bridge("s", codec, handled.scope)
                val store = FatalKvBridgedStore()
                store { s bridge bridgeS }
                codec.encodeFails = fatal
                store action { s mutate 1 }
                assertSame(fatal, handled.first())
                codec.encodeFails = null

                store action { s mutate 2 }

                withTimeout(10.seconds) { while (kv.get("s") == null) yield() }
                assertEquals(IntCodec.encode(2), kv.get("s"), "a new drainer took over from the one the error ended")
            }
        }

    @Test fun aRethrowingHandlerCannotDropAHeldBridgeStackOverflow() =
        runBlocking<Unit> {
            val store = FatalFanStore()
            store.uncaughtObserverHandler = { throw it }
            val fatal = StackOverflowError()
            val boom = IllegalStateException("publish")
            var armed = true
            store {
                x bridge FatalFanBridge { if (armed) throw fatal }
                y bridge FatalFanBridge { if (armed) throw boom }
            }

            val thrown =
                assertPropagates<StackOverflowError> {
                    store.suspendAction {
                        x mutate 1
                        y mutate 2
                    }
                }
            armed = false

            assertSame(fatal, thrown, "never the handler's rethrow, never a TransactionResult.Error")
            assertTrue(thrown.suppressedExceptions.any { it === boom }, "${thrown.suppressedExceptions}")
            assertEquals(1, store.x.value)
            assertEquals(2, store.y.value)
            assertNothingInstalledOn(store)
            store.uncaughtObserverHandler = null
            assertIs<TransactionResult.Success<*>>(store.suspendAction { x mutate 5 })
        }

    @Test fun aKvBridgeLoadGetStackOverflowGoesToTheScopesHandlerNotErrors() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val fatal = StackOverflowError()
                val kv = RecordingKv(mapOf("s" to "7"))
                kv.failGet = fatal
                val bridgeS = kv.bridge("s", IntCodec, handled.scope)
                val errors = CopyOnWriteArrayList<Throwable>()
                val collector = collecting(bridgeS.errors, errors)
                val store = FatalKvBridgedStore()
                val reported = store.recordedReports()

                store { s bridge bridgeS }

                assertSame(fatal, handled.first(), "the load's failure reached its scope's handler")
                letCollectorsRun()
                collector.cancel()
                assertTrue(errors.isEmpty(), "never on the errors flow: $errors")
                assertEquals(0, store.s.value, "the state keeps its initializer's value")
                assertTrue(reported.isEmpty(), "never reported: $reported")
            }
        }

    @Test fun aKvBridgeLoadDecodeStackOverflowGoesToTheScopesHandlerNotErrors() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val fatal = StackOverflowError()
                val kv = RecordingKv(mapOf("s" to "7"))
                val codec = FatalFailingIntCodec()
                codec.decodeFails = fatal
                val bridgeS = kv.bridge("s", codec, handled.scope)
                val errors = CopyOnWriteArrayList<Throwable>()
                val collector = collecting(bridgeS.errors, errors)
                val store = FatalKvBridgedStore()
                val reported = store.recordedReports()

                store { s bridge bridgeS }

                assertSame(fatal, handled.first(), "the load's failure reached its scope's handler")
                letCollectorsRun()
                collector.cancel()
                assertTrue(errors.isEmpty(), "never on the errors flow: $errors")
                assertEquals(0, store.s.value, "the state keeps its initializer's value")
                assertTrue(reported.isEmpty(), "never reported: $reported")
            }
        }

    @Test fun aNonFatalKvBridgeLoadFailureStillGoesToErrors() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val boom = IllegalStateException("get")
                val kv = RecordingKv(mapOf("s" to "7"))
                kv.failGet = boom
                val bridgeS = kv.bridge("s", IntCodec, handled.scope)
                val firstError = CompletableDeferred<Throwable>()
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) { firstError.complete(bridgeS.errors.first()) }
                val store = FatalKvBridgedStore()

                store { s bridge bridgeS }

                assertSame(boom, withTimeout(10.seconds) { firstError.await() })
                collector.cancel()
                assertTrue(handled.caught.isEmpty(), "a non-fatal load failure fails no coroutine: ${handled.caught}")
            }
        }
}
