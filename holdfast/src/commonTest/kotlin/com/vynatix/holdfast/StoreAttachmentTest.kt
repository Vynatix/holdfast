@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Plan PR 10 (issue #20; #21 prerequisite): the store attachment slot — one
// attachment per key, in attach order, told of every reset inside the reset's
// transaction and of the dispose once, last.

/** Logs every lifecycle call it gets into [log] as `label:event`, then runs the matching hook. */
private class LifecycleRecorder(
    private val label: String,
    private val log: MutableList<String>,
    private val onReset: () -> Unit = {},
    private val onDisposed: () -> Unit = {},
) : StoreAttachment {
    override fun onStoreReset() {
        log += "$label:reset"
        onReset()
    }

    override fun onStoreDisposed() {
        log += "$label:disposed"
        onDisposed()
    }
}

private val recorderKey = StoreAttachmentKey<LifecycleRecorder>("recorder")
private val otherRecorderKey = StoreAttachmentKey<LifecycleRecorder>("other")
private val thirdRecorderKey = StoreAttachmentKey<LifecycleRecorder>("third")

private class AttachSettings : Store<AttachSettings>() {
    val theme by state { "light" }
    val fontSize by state { 12 }
    val remote by state(tags = setOf(StateTag.Remote)) { "server" }
}

/**
 * An attachment that owns a state of its store — an internal one, which
 * `reset()` never resets itself — the way a hydrator owns its phase: the
 * reset moves it to `Detached` inside the reset's transaction.
 */
private class PhaseAttachment(
    private val store: AttachSettings,
    private val onReset: () -> Unit = {},
) : StoreAttachment {
    val phase: MutableState<String> = store.registerInternalState("__phase", "Hydrated")

    /** What the store looked like from inside [onStoreReset]. */
    val seen = mutableListOf<String>()

    override fun onStoreReset() {
        seen += "txn=${store.activeTransaction?.id}"
        seen += "theme=${store.theme.value}"
        seen += "noWriteRegion=${NoWriteRegion.current()}"
        store { phase mutate "Detached" }
        onReset()
    }
}

private val phaseKey = StoreAttachmentKey<PhaseAttachment>("phase")

/** Logs each transaction's id as middleware sees it start and complete (or fail). */
private class TxnLog(
    private val log: MutableList<String>,
    private val failCompletedOf: String? = null,
) : Middleware<AttachSettings>() {
    override fun onTransactionStarted(context: MiddlewareContext<AttachSettings>) {
        log += "started:${context.transaction.id}"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<AttachSettings>) {
        log += "completed:${context.transaction.id}"
        check(context.transaction.id != failCompletedOf) { "middleware rejects ${context.transaction.id}" }
    }

    override fun onTransactionError(
        context: MiddlewareContext<AttachSettings>,
        error: Throwable,
    ) {
        log += "error:${context.transaction.id}"
    }
}

/** Resolves its store's declarations only when asked: the shape an attachment must have (#21 T3). */
private class DeclarationsView(
    private val store: Store<*>,
) : StoreAttachment {
    val names: List<String> get() = store.declarations().map { it.name }
}

private val declarationsKey = StoreAttachmentKey<DeclarationsView>("declarations")

/** A base class that attaches while it is being constructed, before the subclass declares anything. */
private abstract class AttachingInitBase<S : AttachingInitBase<S>> : Store<S>() {
    val baseState by state { "base" }
    val view: DeclarationsView = internalAttachIfAbsent(declarationsKey) { DeclarationsView(this) }
    val namesDuringBaseInit: List<String> = view.names
}

private class AttachingInitSub : AttachingInitBase<AttachingInitSub>() {
    val first by state { 1 }
    val second by state { 2 }
}

/** `theme` and `fontSize` committed away from their initial values. */
private fun dirtySettings(): AttachSettings =
    AttachSettings().also {
        it action {
            theme mutate "dark"
            fontSize mutate 16
        }
    }

class StoreAttachmentTest {
    @Test fun attachIfAbsentAttachesOnceAndAlwaysReturnsThatInstance() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        assertNull(store.internalAttachment(recorderKey), "nothing attached yet")

        var creates = 0
        val first = store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log).also { creates++ } }
        val second = store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("b", log).also { creates++ } }

        assertSame(first, second, "the second call gets the attached instance")
        assertSame(first, store.internalAttachment(recorderKey))
        assertEquals(1, creates, "create runs only while the key is absent")
        assertEquals(listOf<StoreAttachment>(first), store.internalAttachments())
    }

    @Test fun keysCompareByIdentityNotByName() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        val one = StoreAttachmentKey<LifecycleRecorder>("same")
        val two = StoreAttachmentKey<LifecycleRecorder>("same")

        val a = store.internalAttachIfAbsent(one) { LifecycleRecorder("a", log) }
        assertNull(store.internalAttachment(two), "a key with the same name is another slot")
        val b = store.internalAttachIfAbsent(two) { LifecycleRecorder("b", log) }

        assertNotSame(a, b)
        assertEquals(listOf<StoreAttachment>(a, b), store.internalAttachments())
        assertEquals("StoreAttachmentKey(same)", one.toString())
    }

    @Test fun attachmentsAreListedInAttachOrderAndANestedAttachLandsFirst() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        val a = store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log) }
        // other's create attaches third: third is attached before other is.
        val other =
            store.internalAttachIfAbsent(otherRecorderKey) {
                store.internalAttachIfAbsent(thirdRecorderKey) { LifecycleRecorder("third", log) }
                LifecycleRecorder("other", log)
            }
        val third = assertNotNull(store.internalAttachment(thirdRecorderKey))

        assertEquals(listOf(a, third, other), store.internalAttachments())
    }

    @Test fun aCreateThatAttachesItsOwnKeyFailsAndAttachesNothing() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        val e =
            assertFailsWith<IllegalStateException> {
                store.internalAttachIfAbsent(recorderKey) {
                    store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("inner", log) }
                }
            }
        assertContains(e.message.orEmpty(), "AttachSettings: the create of attachment 'recorder' attaches 'recorder' itself")
        assertNull(store.internalAttachment(recorderKey))
        assertTrue(store.internalAttachments().isEmpty())
    }

    @Test fun aThrowingCreateAttachesNothingAndRunsAgainOnTheNextCall() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        val e =
            assertFailsWith<IllegalArgumentException> {
                store.internalAttachIfAbsent(recorderKey) { throw IllegalArgumentException("create failed") }
            }
        assertEquals("create failed", e.message)
        assertNull(store.internalAttachment(recorderKey))

        val attached = store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("retry", log) }
        assertSame(attached, store.internalAttachment(recorderKey), "the key is not stuck after a failed create")
    }

    @Test fun disposeTellsEveryAttachmentOnceInAttachOrderWithTheStoreTornDown() {
        val store = AttachSettings()
        store.theme.value
        val log = mutableListOf<String>()
        val seen = mutableListOf<String>()
        store.internalAttachIfAbsent(recorderKey) {
            LifecycleRecorder("a", log, onDisposed = {
                seen += "disposed=${store.isDisposed}"
                seen += "attachments=${store.internalAttachments().size}"
                seen += "own=${store.internalAttachment(recorderKey)}"
                seen += "properties=" + runCatching { store.properties }.exceptionOrNull()?.message
            })
        }
        store.internalAttachIfAbsent(otherRecorderKey) { LifecycleRecorder("b", log) }
        assertEquals(2, store.internalAttachments().size)

        store.dispose()
        store.dispose()

        assertEquals(listOf("a:disposed", "b:disposed"), log, "each is told once, in attach order")
        assertEquals(
            listOf("disposed=true", "attachments=0", "own=null", "properties=store disposed"),
            seen,
            "told once the store is torn down and its slot is empty",
        )
        assertNull(store.internalAttachment(recorderKey), "dispose drops the attachments")
        assertTrue(store.internalAttachments().isEmpty())
    }

    @Test fun attachingAfterDisposeFailsWithoutRunningCreate() {
        val store = AttachSettings()
        store.dispose()
        val e =
            assertFailsWith<IllegalStateException> {
                store.internalAttachIfAbsent(recorderKey) { error("create ran on a disposed store") }
            }
        assertEquals("store disposed", e.message)
    }

    @Test fun aCreateThatDisposesItsOwnStoreAttachesNothingAndFails() {
        // Forbidden — dispose() takes transactionLock under the slot's lock,
        // the reverse of an attach from an action on another thread — but
        // defended: on one thread, the create's result never lands in the
        // closed slot, so it is never an attachment the dispose did not tell.
        val store = AttachSettings()
        val log = mutableListOf<String>()
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log) }

        val e =
            assertFailsWith<IllegalStateException> {
                store.internalAttachIfAbsent(otherRecorderKey) {
                    store.dispose()
                    LifecycleRecorder("late", log)
                }
            }

        assertEquals("store disposed", e.message)
        assertTrue(store.isDisposed)
        assertNull(store.internalAttachment(otherRecorderKey), "the create's result is dropped")
        assertNull(store.internalAttachment(recorderKey))
        assertTrue(store.internalAttachments().isEmpty())
        assertEquals(listOf("a:disposed"), log, "only the attachment attached before the dispose is told of it")
    }

    @Test fun anAttachFromOnStoreDisposedFailsLikeAnyAttachAfterDispose() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        store.internalAttachIfAbsent(recorderKey) {
            LifecycleRecorder("a", log, onDisposed = {
                store.internalAttachIfAbsent(otherRecorderKey) { LifecycleRecorder("late", log) }
            })
        }

        store.dispose()

        assertEquals(listOf("a:disposed"), log, "the late attachment was never created")
        assertEquals("store disposed", failures.single().message)
    }

    @Test fun aThrowingOnStoreDisposedIsReportedAndTheOthersAreStillTold() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log, onDisposed = { error("a failed") }) }
        store.internalAttachIfAbsent(otherRecorderKey) { LifecycleRecorder("b", log, onDisposed = { error("b failed") }) }
        store.internalAttachIfAbsent(thirdRecorderKey) { LifecycleRecorder("c", log) }

        store.dispose()

        assertEquals(listOf("a:disposed", "b:disposed", "c:disposed"), log)
        assertEquals(listOf("a failed", "b failed"), failures.map { it.message })
    }

    @Test fun aHandlerThrowingForAFailedOnStoreDisposedDoesNotStopTheOthersOrDispose() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        store.uncaughtObserverHandler = { throw IllegalStateException("handler failed", it) }
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log, onDisposed = { error("a failed") }) }
        store.internalAttachIfAbsent(otherRecorderKey) { LifecycleRecorder("b", log) }

        store.dispose()

        assertEquals(listOf("a:disposed", "b:disposed"), log)
        assertTrue(store.isDisposed)
    }

    @Test fun onStoreDisposedRunsAfterOnDispose() {
        val log = mutableListOf<String>()
        val store = OnDisposeLogger(log)
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log) }

        store.dispose()

        assertEquals(listOf("onDispose", "a:disposed"), log)
    }

    @Test fun resetTellsAttachmentsInsideItsTransactionAndTheirWritesCommitWithIt() {
        val store = dirtySettings()
        val log = mutableListOf<String>()
        store.middlewares(TxnLog(log))
        val phase = store.internalAttachIfAbsent(phaseKey) { PhaseAttachment(store) }
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("after", log) }
        val fires = mutableListOf<String>()
        val sub = phase.phase effect { fires += "$this@${store.activeTransaction?.id}" }
        fires.clear()

        val r = store.reset()
        sub.dispose()

        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(
            listOf("txn=Reset", "theme=light", "noWriteRegion=null"),
            phase.seen,
            "inside the reset's transaction, reading its reset values, in no no-write region",
        )
        assertEquals(listOf("started:Reset", "after:reset", "completed:Reset"), log, "one transaction; attach order")
        assertEquals("Detached", phase.phase.value)
        assertEquals("light", store.theme.value)
        assertEquals(12, store.fontSize.value)
        assertEquals(listOf("Detached@Reset"), fires, "the attachment's write committed with the reset")
    }

    @Test fun anAttachmentsResetWriteRollsBackWhenMiddlewareRejectsTheReset() {
        val store = dirtySettings()
        val log = mutableListOf<String>()
        store.middlewares(TxnLog(log, failCompletedOf = "Reset"))
        val phase = store.internalAttachIfAbsent(phaseKey) { PhaseAttachment(store) }

        val r = store.reset()

        assertIs<TransactionResult.Error>(r)
        assertEquals("middleware rejects Reset", r.exception.message)
        assertEquals(listOf("txn=Reset", "theme=light", "noWriteRegion=null"), phase.seen, "it was told")
        assertEquals("Hydrated", phase.phase.value, "its write rolled back with the reset")
        assertEquals("dark", store.theme.value)
    }

    @Test fun aResetInsideAnActionThatRollsBackTakesTheAttachmentsWriteWithIt() {
        val store = dirtySettings()
        val phase = store.internalAttachIfAbsent(phaseKey) { PhaseAttachment(store) }

        val r =
            store action {
                assertIs<TransactionResult.Success<Unit>>(reset())
                assertEquals("Detached", phase.phase.value, "the savepoint's write is visible to the action")
                error("roll back")
            }

        assertIs<TransactionResult.Error>(r)
        assertEquals("Hydrated", phase.phase.value)
        assertEquals("dark", store.theme.value)
    }

    @Test fun aThrowingOnStoreResetRollsTheWholeResetBack() {
        val store = dirtySettings()
        val log = mutableListOf<String>()
        val phase = store.internalAttachIfAbsent(phaseKey) { PhaseAttachment(store) }
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("failing", log, onReset = { error("reset hook failed") }) }
        store.internalAttachIfAbsent(otherRecorderKey) { LifecycleRecorder("later", log) }

        val r = store.reset()

        assertIs<TransactionResult.Error>(r)
        assertEquals("reset hook failed", r.exception.message)
        assertEquals(listOf("failing:reset"), log, "the attachments after the failing one are not told")
        assertEquals("Hydrated", phase.phase.value, "an earlier attachment's write rolled back")
        assertEquals("dark", store.theme.value, "the states' reset rolled back")
        assertEquals(16, store.fontSize.value)
    }

    @Test fun aResetStagedIntoAFrameTellsTheAttachmentsInsideThatFrame() {
        val store = dirtySettings()
        val other = dirtySettings()
        val phase = store.internalAttachIfAbsent(phaseKey) { PhaseAttachment(store) }
        store.materializeDeclaredStates()

        val aborted =
            atomic(store, other) {
                store.stageResetOfDeclaredStates(assertNotNull(store.activeTransaction))
                error("abort")
            }
        assertIs<TransactionResult.Error>(aborted)
        assertEquals("Hydrated", phase.phase.value, "rolled back with the frame")
        assertEquals("dark", store.theme.value)

        val committed =
            atomic(store, other) {
                store.stageResetOfDeclaredStates(assertNotNull(store.activeTransaction))
            }
        assertIs<TransactionResult.Success<*>>(committed)
        assertEquals("Detached", phase.phase.value, "committed with the frame")
        assertEquals("light", store.theme.value)
        val told = phase.seen.filter { it.startsWith("txn=") }
        assertEquals(2, told.size, "told once per frame")
        assertTrue("txn=null" !in told, "inside the frame root, both times")
    }

    @Test fun aSterileRestoreDoesNotTellAttachments() {
        val store = AttachSettings()
        val log = mutableListOf<String>()
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log) }
        val snap = store.snapshot()
        store action { remote mutate "stale" }

        val r = store.restore(snap, RestorePolicy.BestEffort, sterile = true)

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals("server", store.remote.value, "the Remote state was reset")
        assertEquals(emptyList(), log, "restored, not reset: no attachment is told")
    }

    @Test fun anAttachmentDisposingTheStoreFromOnStoreResetEndsTheResetNotifications() {
        val store = dirtySettings()
        val log = mutableListOf<String>()
        store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", log, onReset = { store.dispose() }) }
        store.internalAttachIfAbsent(otherRecorderKey) { LifecycleRecorder("b", log) }

        store.reset()

        assertEquals(
            listOf("a:reset", "a:disposed", "b:disposed"),
            log,
            "b has heard of the dispose, so it is not told of the reset after it",
        )
    }

    @Test fun anAttachmentFromABaseClassInitSeesEverySubclassDeclarationLater() {
        val store = AttachingInitSub()

        assertEquals(listOf("baseState"), store.namesDuringBaseInit, "the subclass had declared nothing yet")
        assertSame(store.view, store.internalAttachment(declarationsKey), "attached from the base's init")
        assertEquals(listOf("baseState", "first", "second"), store.view.names, "resolved lazily, it sees them all")
        assertEquals(listOf<StoreAttachment>(store.view), store.internalAttachments())
    }

    @Test fun anAttachmentPersistsNothingByDefault() {
        val store = AttachSettings()
        val attached = store.internalAttachIfAbsent(recorderKey) { LifecycleRecorder("a", mutableListOf()) }
        assertEquals(emptySet(), attached.persistenceKeys)
    }
}

/** Logs its `onDispose` into [log], to order it against the attachments' notification. */
private class OnDisposeLogger(
    private val log: MutableList<String>,
) : Store<OnDisposeLogger>() {
    override fun onDispose() {
        log += "onDispose"
        super.onDispose()
    }
}
