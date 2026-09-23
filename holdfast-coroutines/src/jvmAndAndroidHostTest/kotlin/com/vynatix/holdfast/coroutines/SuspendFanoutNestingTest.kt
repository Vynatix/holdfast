package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameObserver
import com.vynatix.holdfast.FrameObservers
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class NestStore : Store<NestStore>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

private class NestPeer : Store<NestPeer>() {
    val copy by state { 0 }
}

private sealed class NestEvent {
    data object Triggered : NestEvent()
}

private class NestEventStore : EventfulStore<NestEventStore, NestEvent>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

/**
 * Blocking calls that nest into a suspending commit from inside it.
 *
 * A blocking `action` or `atomic` from code running inside a `suspendAction`'s
 * (or `suspendAtomic`'s) commit — an observer, a bridge publish, an event
 * collector, a frame observer, or a later participant's observer targeting an
 * earlier, already-applied participant — is not recognised as nested by thread
 * ownership (`suspendingOwner` is set), so it acquired the store's serializer,
 * which that very commit holds: it spun forever. The commit now runs under a
 * fanout marker that follows its coroutine across thread hops, and the call
 * returns an `Error` at once. Every case is watchdogged, so a regression fails
 * instead of hanging the test task.
 */
class SuspendFanoutNestingTest {
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

    /** Fail — rather than hang — if [body] does not finish within [seconds]. */
    private fun completesWithin(
        seconds: Long,
        what: String,
        body: () -> Unit,
    ) {
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val worker =
            Thread {
                try {
                    body()
                } catch (e: Throwable) {
                    thrown.set(e)
                } finally {
                    done.countDown()
                }
            }
        worker.isDaemon = true
        worker.name = "fanout-nesting-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — the observer is spinning on its own commit's serializer")
        }
        thrown.get()?.let { throw it }
    }

    private fun assertRefused(
        result: TransactionResult<*>?,
        fragment: String,
    ) {
        val error = assertIs<TransactionResult.Error>(result, "the nested call must be refused, not run or wait")
        assertIs<IllegalStateException>(error.exception)
        val message = error.exception.message.orEmpty()
        assertTrue("has already applied its writes" in message, "not the applied-transaction error: $message")
        assertTrue(fragment in message, "message lacks '$fragment': $message")
    }

    @Test fun blockingActionFromASuspendActionObserverIsRefused() {
        val s = NestStore()
        var nested: TransactionResult<*>? = null
        onCommit(s.trigger) { value -> nested = s action { echo mutate value } }
        var outer: TransactionResult<*>? = null

        completesWithin(10, "a nested blocking action from a suspendAction observer") {
            outer = runBlocking { s.suspendAction { trigger mutate 1 } }
        }

        assertIs<TransactionResult.Success<*>>(outer)
        assertRefused(nested, "open a nested action on NestStore")
        assertEquals(0, s.echo.value)
    }

    @Test fun blockingAtomicFromASuspendActionObserverIsRefused() {
        val s = NestStore()
        val peer = NestPeer()
        var frame: TransactionResult<*>? = null
        onCommit(s.trigger) { value ->
            frame =
                atomic(s, peer) {
                    s { echo mutate value }
                    peer { copy mutate value }
                }
        }

        completesWithin(10, "a nested atomic from a suspendAction observer") {
            runBlocking { s.suspendAction { trigger mutate 2 } }
        }

        assertRefused(frame, "open an atomic(...) frame on NestStore")
        assertEquals(0, s.echo.value)
        assertEquals(0, peer.copy.value, "a refused frame commits no participant")
    }

    @Test fun blockingActionFromASuspendAtomicObserverIsRefused() {
        val s = NestStore()
        val peer = NestPeer()
        var nested: TransactionResult<*>? = null
        onCommit(s.trigger) { value -> nested = s action { echo mutate value } }
        var outer: TransactionResult<*>? = null

        completesWithin(10, "a nested blocking action from a suspendAtomic observer") {
            outer =
                runBlocking {
                    suspendAtomic(s, peer) {
                        s { trigger mutate 1 }
                        peer { copy mutate 1 }
                    }
                }
        }

        assertIs<TransactionResult.Success<*>>(outer)
        assertRefused(nested, "open a nested action on NestStore")
        assertEquals(1, peer.copy.value)
        assertEquals(0, s.echo.value)
    }

    /**
     * The refusal keys on the thread running the fanout, not on the store
     * being busy: another thread's blocking action while a `suspendAction`
     * fans out is not nested, so it waits for the serializer and commits.
     */
    @Test fun anotherThreadsActionDuringASuspendActionFanoutWaitsAndCommits() {
        val s = NestStore()
        val inFanout = CountDownLatch(1)
        val otherCalling = CountDownLatch(1)
        onCommit(s.trigger) {
            inFanout.countDown()
            otherCalling.await(5, TimeUnit.SECONDS)
            // Hold the fanout open while the other thread's action is in flight.
            Thread.sleep(150)
        }
        val otherResult = AtomicReference<TransactionResult<*>?>(null)
        val other =
            Thread {
                inFanout.await(5, TimeUnit.SECONDS)
                otherCalling.countDown()
                otherResult.set(s action { echo mutate 5 })
            }.apply {
                isDaemon = true
                start()
            }

        completesWithin(10, "a suspendAction whose fanout overlaps another thread's action") {
            runBlocking { s.suspendAction { trigger mutate 1 } }
            other.join(5_000)
        }

        assertIs<TransactionResult.Success<*>>(otherResult.get(), "another thread must wait its turn, not be refused")
        assertEquals(5, s.echo.value)
        assertEquals(1, s.trigger.value)
    }

    /**
     * The later participant `peer` fans out while the earlier participant
     * `s` is applied, still installed and its serializer still held by the
     * frame — so this is nested too, though no thread owns s's root and s's
     * own fanout is over.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun blockingActionOnAnEarlierSuspendAtomicParticipantFromALaterOnesObserverIsRefused() {
        val s = NestStore()
        val peer = NestPeer()
        assertTrue(s.lockOrderKey < peer.lockOrderKey, "s must commit first")
        var nested: TransactionResult<*>? = null
        onCommit(peer.copy) { value -> nested = s action { echo mutate value } }
        var outer: TransactionResult<*>? = null

        completesWithin(10, "a blocking action on an earlier suspendAtomic participant") {
            outer =
                runBlocking {
                    suspendAtomic(s, peer) {
                        s { trigger mutate 1 }
                        peer { copy mutate 1 }
                    }
                }
        }

        assertIs<TransactionResult.Success<*>>(outer)
        assertRefused(nested, "open a nested action on NestStore")
        assertEquals(1, s.trigger.value)
        assertEquals(1, peer.copy.value)
        assertEquals(0, s.echo.value)
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun blockingAtomicOverAnEarlierSuspendAtomicParticipantFromALaterOnesObserverIsRefused() {
        val s = NestStore()
        val peer = NestPeer()
        assertTrue(s.lockOrderKey < peer.lockOrderKey, "s must commit first")
        val other = NestPeer()
        var frame: TransactionResult<*>? = null
        onCommit(peer.copy) { value ->
            frame =
                atomic(s, other) {
                    s { echo mutate value }
                    other { copy mutate value }
                }
        }
        var outer: TransactionResult<*>? = null

        completesWithin(10, "a blocking atomic over an earlier suspendAtomic participant") {
            outer =
                runBlocking {
                    suspendAtomic(s, peer) {
                        s { trigger mutate 1 }
                        peer { copy mutate 2 }
                    }
                }
        }

        assertIs<TransactionResult.Success<*>>(outer)
        assertRefused(frame, "open an atomic(...) frame on NestStore")
        assertEquals(0, s.echo.value)
        assertEquals(0, other.copy.value, "a refused frame commits no participant")
    }

    @OptIn(ExperimentalStoreApi::class)
    @Test
    fun blockingActionFromASuspendAtomicFrameObserverIsRefused() {
        val s = NestStore()
        val peer = NestPeer()
        var nested: TransactionResult<*>? = null
        val frameObserver =
            object : FrameObserver {
                override fun onFrameCommitted(frameId: String) {
                    nested = s action { echo mutate 9 }
                }
            }
        FrameObservers.register(frameObserver)
        try {
            completesWithin(10, "a blocking action from onFrameCommitted of a suspendAtomic") {
                runBlocking { suspendAtomic(s, peer) { s { trigger mutate 1 } } }
            }
        } finally {
            FrameObservers.unregister(frameObserver)
        }

        assertRefused(nested, "open a nested action on NestStore")
        assertEquals(0, s.echo.value)
    }

    /** A sync bridge's publish runs after `commitDispatching` has returned, in the suspending commit. */
    @Test fun blockingActionFromABridgePublishUnderASuspendActionIsRefused() {
        val s = NestStore()
        var nested: TransactionResult<*>? = null
        s {
            trigger bridge
                object : Bridge<Int> {
                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

                    override fun publish(value: Int): Boolean {
                        nested = s action { echo mutate value }
                        return true
                    }
                }
        }

        completesWithin(10, "a blocking action from a bridge publish under suspendAction") {
            runBlocking { s.suspendAction { trigger mutate 1 } }
        }

        assertRefused(nested, "open a nested action on NestStore")
        assertEquals(0, s.echo.value)
    }

    /** The marker follows the committing coroutine onto another dispatcher's thread. */
    @Test fun blockingActionFromAPublishAwaitedAfterADispatcherHopIsRefused() {
        val s = NestStore()
        val nested = AtomicReference<TransactionResult<*>?>(null)
        s {
            trigger bridge
                object : SuspendingBridge<Int> {
                    override suspend fun publishAwaited(value: Int) {
                        withContext(Dispatchers.IO) { nested.set(s action { echo mutate value }) }
                    }

                    override fun publish(value: Int): Boolean = true

                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}
                }
        }

        completesWithin(10, "a blocking action from publishAwaited on another dispatcher") {
            runBlocking { s.suspendAction { trigger mutate 1 } }
        }

        assertRefused(nested.get(), "open a nested action on NestStore")
        assertEquals(0, s.echo.value)
    }

    /** An Unconfined collector is resumed inline by the commit's suspending emit. */
    @Test fun blockingActionFromAnInlineEventCollectorIsRefused() {
        val s = NestEventStore()
        var nested: TransactionResult<*>? = null

        completesWithin(10, "a blocking action from an event collector the emit resumes inline") {
            runBlocking {
                val collector =
                    launch(Dispatchers.Unconfined) {
                        s.events.collect { nested = s action { echo mutate 3 } }
                    }
                s.suspendAction {
                    trigger mutate 1
                    emit(NestEvent.Triggered)
                }
                collector.cancel()
            }
        }

        assertRefused(nested, "open a nested action on NestEventStore")
        assertEquals(0, s.echo.value)
    }

    /**
     * Another thread's blocking action while a `suspendAtomic` participant
     * fans out is not nested: it waits for the frame and commits.
     */
    @Test fun anotherThreadsActionDuringASuspendAtomicFanoutWaitsAndCommits() {
        val s = NestStore()
        val peer = NestPeer()
        val inFanout = CountDownLatch(1)
        val otherCalling = CountDownLatch(1)
        onCommit(peer.copy) {
            inFanout.countDown()
            otherCalling.await(5, TimeUnit.SECONDS)
            Thread.sleep(150)
        }
        val otherResult = AtomicReference<TransactionResult<*>?>(null)
        val other =
            Thread {
                inFanout.await(5, TimeUnit.SECONDS)
                otherCalling.countDown()
                otherResult.set(s action { echo mutate 5 })
            }.apply {
                isDaemon = true
                start()
            }

        completesWithin(10, "a suspendAtomic whose fanout overlaps another thread's action") {
            runBlocking {
                suspendAtomic(s, peer) {
                    s { trigger mutate 1 }
                    peer { copy mutate 1 }
                }
            }
            other.join(5_000)
        }

        assertIs<TransactionResult.Success<*>>(otherResult.get(), "another thread must wait its turn, not be refused")
        assertEquals(5, s.echo.value)
    }

    /**
     * Pins a known limitation, not a goal: while a suspending commit holds
     * the store, `suspendingOwner` makes a bare `mutate` from ANY thread stage
     * into its transaction (nothing identifies the body's thread yet), so
     * after the apply pass another thread's bare write is refused rather than
     * waiting. `action { }` from that thread waits and commits. The planned
     * "body is running here" marker (ROADMAP: fail fast on a blocking action
     * inside suspendAction) would let the bare write wait too; flip this then.
     */
    @Test fun anotherThreadsBareMutateDuringASuspendActionFanoutIsRefusedButItsActionCommits() {
        val s = NestStore()
        val inFanout = CountDownLatch(1)
        val otherCalling = CountDownLatch(1)
        onCommit(s.trigger) {
            inFanout.countDown()
            otherCalling.await(5, TimeUnit.SECONDS)
            Thread.sleep(150)
        }
        val bare = AtomicReference<Throwable?>(null)
        val followUp = AtomicReference<TransactionResult<*>?>(null)
        val echoAfterBare = AtomicReference<Int?>(null)
        val other =
            Thread {
                inFanout.await(5, TimeUnit.SECONDS)
                bare.set(runCatching { s { echo mutate 5 } }.exceptionOrNull())
                echoAfterBare.set(s.echo.value)
                otherCalling.countDown()
                followUp.set(s action { echo mutate 6 })
            }.apply {
                isDaemon = true
                start()
            }

        completesWithin(10, "a suspendAction fanout overlapping another thread's bare mutate") {
            runBlocking { s.suspendAction { trigger mutate 1 } }
            other.join(5_000)
        }

        assertForeignBareWriteRefused(bare.get())
        assertEquals(0, echoAfterBare.get(), "the refused write never lands")
        assertIs<TransactionResult.Success<*>>(followUp.get(), "the same write as an action waits and commits")
        assertEquals(6, s.echo.value)
    }

    /** The same, in the long window after the fanout: a `publishAwaited` still in flight. */
    @Test fun anotherThreadsBareMutateDuringAPublishAwaitedIsRefusedButItsActionCommits() {
        val s = NestStore()
        val inPublish = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        s {
            trigger bridge
                object : SuspendingBridge<Int> {
                    override suspend fun publishAwaited(value: Int) {
                        inPublish.countDown()
                        release.await()
                    }

                    override fun publish(value: Int): Boolean = true

                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}
                }
        }
        val bare = AtomicReference<Throwable?>(null)
        val followUp = AtomicReference<TransactionResult<*>?>(null)
        val other =
            Thread {
                inPublish.await(5, TimeUnit.SECONDS)
                bare.set(runCatching { s { echo mutate 5 } }.exceptionOrNull())
                release.complete(Unit)
                followUp.set(s action { echo mutate 6 })
            }.apply {
                isDaemon = true
                start()
            }

        completesWithin(10, "a publishAwaited overlapping another thread's bare mutate") {
            runBlocking { s.suspendAction { trigger mutate 1 } }
            other.join(5_000)
        }

        assertForeignBareWriteRefused(bare.get())
        assertIs<TransactionResult.Success<*>>(followUp.get())
        assertEquals(6, s.echo.value)
    }

    private fun assertForeignBareWriteRefused(error: Throwable?) {
        val refusal = assertIs<IllegalStateException>(assertNotNull(error, "the bare write must be refused"))
        val message = refusal.message.orEmpty()
        assertTrue("Cannot write NestStore.echo" in message, "unexpected: $message")
        assertTrue("a suspendAction or suspendAtomic holds NestStore" in message, "not the foreign-thread text: $message")
        assertTrue("store action { … }" in message, "the fix must name action: $message")
    }

    @Test fun throwingSuspendingBridgePublishIsLoggedWithoutAHandler() {
        val s = NestStore()
        s {
            trigger bridge
                object : SuspendingBridge<Int> {
                    override suspend fun publishAwaited(value: Int): Unit = error("remote write failed at $value")

                    override fun publish(value: Int): Boolean = true

                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}
                }
        }
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
        val r =
            try {
                runBlocking { s.suspendAction { trigger mutate 4 } }
            } finally {
                System.setErr(original)
            }
        val err = buffer.toString(Charsets.UTF_8)

        assertIs<TransactionResult.Success<*>>(r, "a failed publish cannot undo the commit")
        assertEquals(4, s.trigger.value)
        assertTrue("Holdfast: a post-commit side effect of NestStore failed" in err, "no default log line: $err")
        assertTrue("remote write failed at 4" in err, "the publish failure is missing: $err")
    }
}
