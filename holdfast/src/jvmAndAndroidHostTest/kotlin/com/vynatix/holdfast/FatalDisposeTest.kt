@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, tearing down (decision 5): dispose() never throws, except a fatal
// error from what it runs — a drained post-commit task, a bridge
// subscription's dispose, onDispose, an attachment's onStoreDisposed. Every
// later teardown step still runs (a second dispose() is a no-op, so a skipped
// step would never run), then the first fatal error is thrown; nothing is
// reported. removeState/clearStates shut every dropped state down and tell
// the store's edges before throwing one. Store.kt dispose(),
// StoreAttachments.kt notifyDisposed, StoreEdges.kt shutDownDematerialized.

private class FatalDisposingStore : Store<FatalDisposingStore>() {
    val a by state { 1 }
    val b by state { 2 }
    var onDisposeFails: Throwable? = null
    var onDisposeRuns = 0

    override fun onDispose() {
        onDisposeRuns++
        onDisposeFails?.let { throw it }
    }
}

private class FatalDisposeHost : Store<FatalDisposeHost>()

/** Logs `label:disposed` into [log] when told, then throws [fails]. */
private class FatalDisposeRecorder(
    private val label: String,
    private val log: MutableList<String>,
    private val fails: Throwable? = null,
) : StoreAttachment {
    override fun onStoreDisposed() {
        log += "$label:disposed"
        fails?.let { throw it }
    }
}

private val firstAttachmentKey = StoreAttachmentKey<FatalDisposeRecorder>("fatal-first")
private val secondAttachmentKey = StoreAttachmentKey<FatalDisposeRecorder>("fatal-second")

/** Counts its subscription's disposes, then throws [disposeFails] from them. */
private class FatalDisposeBridge(
    private val disposeFails: Throwable? = null,
) : Bridge<Int> {
    var disposes = 0

    override fun observe(observer: (Int) -> Unit): Disposable =
        Disposable {
            disposes++
            disposeFails?.let { throw it }
        }

    override fun publish(value: Int): Boolean = true
}

private fun Store<*>.disposeReports(): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

class FatalDisposeTest {
    /** Store.kt:362: onDispose's fatal error is thrown after the attachments have been told. */
    @Test fun anOnDisposeStackOverflowStillTearsDownAndTellsTheAttachments() {
        val store = FatalDisposingStore()
        val reported = store.disposeReports()
        assertEquals(1, store.a.value)
        store.middlewares(object : Middleware<FatalDisposingStore>() {})
        val log = CopyOnWriteArrayList<String>()
        store.internalAttachIfAbsent(firstAttachmentKey) { FatalDisposeRecorder("attachment", log) }
        val fatal = StackOverflowError()
        store.onDisposeFails = fatal

        val thrown = assertFailsWith<StackOverflowError> { store.dispose() }

        assertSame(fatal, thrown)
        assertTrue(store.isDisposed)
        assertEquals(emptyList(), store.snapshotMiddleware(), "the middleware was dropped")
        assertEquals(listOf("attachment:disposed"), log.toList(), "the attachment was told, after onDispose")
        assertEquals(emptyList(), reported.toList())
        store.dispose()
        assertEquals(1, store.onDisposeRuns, "a second dispose() is a no-op")
        assertEquals(listOf("attachment:disposed"), log.toList())
    }

    /** Store.kt:362: a bridge subscription's fatal error is held while onDispose and the attachments still run. */
    @Test fun aBridgeSubscriptionsStackOverflowStillRunsEveryLaterTeardownStep() {
        val store = FatalDisposingStore()
        val reported = store.disposeReports()
        val fatal = StackOverflowError()
        val aBridge = FatalDisposeBridge(disposeFails = fatal)
        val bBridge = FatalDisposeBridge()
        store { a bridge aBridge }
        store { b bridge bBridge }
        val log = CopyOnWriteArrayList<String>()
        store.internalAttachIfAbsent(firstAttachmentKey) { FatalDisposeRecorder("attachment", log) }

        val thrown = assertFailsWith<StackOverflowError> { store.dispose() }

        assertSame(fatal, thrown)
        assertTrue(store.isDisposed)
        assertEquals(1 to 1, aBridge.disposes to bBridge.disposes, "every state was shut down")
        assertEquals(1, store.onDisposeRuns)
        assertEquals(listOf("attachment:disposed"), log.toList())
        assertEquals(emptyList(), reported.toList())
    }

    /** Store.kt:362 with PostCommitQueue.kt:150: the drain's fatal error is held while the teardown goes on. */
    @Test fun aDrainedTasksStackOverflowStillLetsDisposeFinish() {
        val store = FatalDisposingStore()
        val reported = store.disposeReports()
        val bridge = FatalDisposeBridge()
        store { a bridge bridge }
        val log = CopyOnWriteArrayList<String>()
        store.internalAttachIfAbsent(firstAttachmentKey) { FatalDisposeRecorder("attachment", log) }
        val fatal = StackOverflowError()
        val ran = CopyOnWriteArrayList<String>()
        store.handOffPostCommit {
            ran += "t1"
            throw fatal
        }
        store.handOffPostCommit { ran += "t2" }

        val thrown = assertFailsWith<StackOverflowError> { store.dispose() }

        assertSame(fatal, thrown)
        assertEquals(listOf("t1", "t2"), ran.toList(), "the drained batch ran to its end")
        assertEquals(1, bridge.disposes)
        assertEquals(1, store.onDisposeRuns)
        assertEquals(listOf("attachment:disposed"), log.toList())
        assertTrue(store.isDisposed)
        assertEquals(emptyList(), reported.toList())
    }

    /** StoreAttachments.kt:282: every attachment is told before the fatal error one threw. */
    @Test fun anAttachmentsStackOverflowStillTellsTheLaterAttachments() {
        val store = FatalDisposingStore()
        val reported = store.disposeReports()
        val log = CopyOnWriteArrayList<String>()
        val fatal = StackOverflowError()
        store.internalAttachIfAbsent(firstAttachmentKey) { FatalDisposeRecorder("A", log, fails = fatal) }
        store.internalAttachIfAbsent(secondAttachmentKey) { FatalDisposeRecorder("B", log) }

        val thrown = assertFailsWith<StackOverflowError> { store.dispose() }

        assertSame(fatal, thrown)
        assertEquals(listOf("A:disposed", "B:disposed"), log.toList())
        assertTrue(store.isDisposed)
        assertEquals(emptyList(), reported.toList())
        store.dispose()
        assertEquals(listOf("A:disposed", "B:disposed"), log.toList(), "told once")
    }

    /** StoreAttachments.kt:282: a handler overflowing while reporting an attachment's failure is held too. */
    @Test fun aHandlerThatOverflowsReportingAnAttachmentFailureStillTellsTheOthers() {
        val store = FatalDisposingStore()
        val fatal = StackOverflowError()
        val handed = CopyOnWriteArrayList<Throwable>()
        store.uncaughtObserverHandler = {
            handed += it
            throw fatal
        }
        val log = CopyOnWriteArrayList<String>()
        val failure = IllegalStateException("A failed")
        store.internalAttachIfAbsent(firstAttachmentKey) { FatalDisposeRecorder("A", log, fails = failure) }
        store.internalAttachIfAbsent(secondAttachmentKey) { FatalDisposeRecorder("B", log) }

        val thrown = assertFailsWith<StackOverflowError> { store.dispose() }

        assertSame(fatal, thrown)
        assertEquals(listOf<Throwable>(failure), handed.toList())
        assertEquals(listOf("A:disposed", "B:disposed"), log.toList())
        assertTrue(store.isDisposed)
    }

    /** Non-fatal control: an ordinary attachment failure is reported, and dispose() still returns. */
    @Test fun anOrdinaryAttachmentFailureIsStillReportedAndDisposeReturns() {
        val store = FatalDisposingStore()
        val reported = store.disposeReports()
        val log = CopyOnWriteArrayList<String>()
        val failure = IllegalStateException("A failed")
        store.internalAttachIfAbsent(firstAttachmentKey) { FatalDisposeRecorder("A", log, fails = failure) }
        store.internalAttachIfAbsent(secondAttachmentKey) { FatalDisposeRecorder("B", log) }
        store.onDisposeFails = IllegalArgumentException("onDispose failed")

        store.dispose()

        assertEquals(listOf<Throwable>(failure), reported.toList())
        assertEquals(listOf("A:disposed", "B:disposed"), log.toList())
        assertTrue(store.isDisposed)
    }

    /** StoreEdges.kt:304: every dropped state is shut down and the edges told before clearStates throws. */
    @Test fun clearStatesShutsDownEveryStateAndTellsTheEdgesBeforeAStackOverflow() {
        val store = FatalDisposingStore()
        val host = FatalDisposeHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        listOf(store, host).forEach { it.uncaughtObserverHandler = { failure -> reported += failure } }
        val fatal = StackOverflowError()
        val aBridge = FatalDisposeBridge(disposeFails = fatal)
        val bBridge = FatalDisposeBridge()
        store { a bridge aBridge }
        store { b bridge bBridge }
        var computes = 0
        val follower = host.derivedStateOverStores("follower", sourceStores = listOf(store)) { ++computes }
        val before = computes

        val thrown = assertFailsWith<StackOverflowError> { store.clearStates() }

        assertSame(fatal, thrown)
        assertEquals(1 to 1, aBridge.disposes to bBridge.disposes, "every dropped state was shut down")
        assertTrue(computes > before, "the store's whole-store follower recomputed")
        assertFalse(store.hasState("a"))
        assertFalse(store.hasState("b"))
        assertEquals(emptyList(), reported.toList())
        assertEquals(1, store.a.value, "a read materializes the state again from its initializer")
        follower.dispose()
    }

    @Test fun removeStateTellsTheEdgesBeforeAStackOverflow() {
        val store = FatalDisposingStore()
        val host = FatalDisposeHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        listOf(store, host).forEach { it.uncaughtObserverHandler = { failure -> reported += failure } }
        val fatal = StackOverflowError()
        val aBridge = FatalDisposeBridge(disposeFails = fatal)
        store { a bridge aBridge }
        assertEquals(2, store.b.value)
        var computes = 0
        val follower = host.derivedStateOverStores("follower", sourceStores = listOf(store)) { ++computes }
        val before = computes

        val thrown = assertFailsWith<StackOverflowError> { store.removeState("a") }

        assertSame(fatal, thrown)
        assertEquals(1, aBridge.disposes)
        assertTrue(computes > before, "the store's whole-store follower recomputed")
        assertFalse(store.hasState("a"))
        assertTrue(store.hasState("b"))
        assertEquals(emptyList(), reported.toList())
        assertEquals(1, store.a.value)
        follower.dispose()
    }

    /** Non-fatal control: an ordinary shutdown failure still propagates out of clearStates, edges told. */
    @Test fun anOrdinaryShutdownFailureStillPropagatesOutOfClearStates() {
        val store = FatalDisposingStore()
        val host = FatalDisposeHost()
        val failure = IllegalStateException("dispose failed")
        store { a bridge FatalDisposeBridge(disposeFails = failure) }
        var computes = 0
        val follower = host.derivedStateOverStores("follower", sourceStores = listOf(store)) { ++computes }
        val before = computes

        assertSame(failure, assertFailsWith<IllegalStateException> { store.clearStates() })

        assertTrue(computes > before, "the edges were still told")
        follower.dispose()
    }
}
