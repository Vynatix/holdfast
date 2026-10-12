@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.Codec
import com.vynatix.holdfast.bridge.InMemoryKvStore
import com.vynatix.holdfast.bridge.KvBridge
import com.vynatix.holdfast.middleware.ProfilingMiddleware
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, user callbacks the core calls around a commit: an effect, a
// FrameObserver, a ProfilingMiddleware sample callback, a KvBridge's codec.
// A fatal error from one propagates out of the call that ran it, after what
// had to happen still happened (the commit's other observers and bridges,
// the other frame observers, the rollback), and is never reported.

private class FcsStore : Store<FcsStore>() {
    val x by state { 0 }
    val n by state { 0 }
}

private class FcsBridge : Bridge<Int> {
    val published = CopyOnWriteArrayList<Int>()

    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

    override fun publish(value: Int): Boolean {
        published += value
        return true
    }
}

/** A Long codec whose decode throws [decodeThrows] while set. */
private class FcsLongCodec : Codec<Long> {
    @Volatile var decodeThrows: Throwable? = null

    override fun encode(value: Long): String = value.toString()

    override fun decode(string: String): Long {
        decodeThrows?.let { throw it }
        return string.toLong()
    }
}

private class FcsCountStore : Store<FcsCountStore>() {
    val count by state { 0L }
}

/**
 * A FrameObserver that records the frames [ours] take part in, and throws
 * [startedThrows]/[committedThrows] from them while set.
 */
private class FcsFrameObserver(
    private val ours: Set<Store<*>>,
) : FrameObserver {
    private val frames = HashSet<String>()
    val events = CopyOnWriteArrayList<String>()
    var startedThrows: Throwable? = null
    var committedThrows: Throwable? = null

    override fun onFrameStarted(
        frameId: String,
        participants: List<Store<*>>,
    ) {
        if (participants.none { it in ours }) return
        frames += frameId
        events += "started"
        startedThrows?.let { throw it }
    }

    override fun onFrameCommitted(frameId: String) {
        if (frameId !in frames) return
        events += "committed"
        committedThrows?.let { throw it }
    }

    override fun onFrameRolledBack(
        frameId: String,
        cause: Throwable,
    ) {
        if (frameId !in frames) return
        events += "rolledBack"
    }
}

private fun Store<*>.recordUncaught(): List<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

private fun assertThreadClean() {
    assertNull(SettleScopes.current(), "no settle scope left open on this thread")
    assertNull(FrameMarkers.current(), "no frame marker left on this thread")
}

private fun FcsStore.assertNotWedged() {
    assertNull(activeTransaction)
    assertThreadClean()
    val before = n.value
    action { n mutate before + 1 }.getOrThrow()
    assertEquals(before + 1, n.value)
}

class FatalCallbackSitesTest {
    private val observers = mutableListOf<FrameObserver>()

    @AfterTest
    fun unregisterObservers() {
        observers.forEach { FrameObservers.unregister(it) }
    }

    private fun register(observer: FcsFrameObserver): FcsFrameObserver {
        observers += observer
        FrameObservers.register(observer)
        return observer
    }

    @Test
    fun anEffectsFatalErrorIsThrownAfterTheRestOfTheCommitsFanoutWithTheValueApplied() {
        val store = FcsStore()
        val seen = store.recordUncaught()
        val fatal = StackOverflowError()
        val publisher = FcsBridge()
        store { x bridge publisher }
        store.x effect { if (this == 1) throw fatal }
        val heard = CopyOnWriteArrayList<Int>()
        store.x effect { heard += this }

        assertSame(fatal, assertFailsWith<StackOverflowError> { store action { x mutate 1 } })

        assertEquals(1, store.x.value, "the commit stands")
        assertEquals(listOf(0, 1), heard.toList(), "the other effect still ran")
        assertEquals(listOf(1), publisher.published.toList(), "the bridge still published")
        assertEquals(emptyList(), seen)
        store.assertNotWedged()
        store action { x mutate 2 }
        assertEquals(listOf(0, 1, 2), heard.toList())
    }

    @Test
    fun aNonFatalEffectFailureIsStillReportedAndTheActionSucceeds() {
        val store = FcsStore()
        val seen = store.recordUncaught()
        val failure = IllegalStateException("effect refused")
        store.x effect { if (this == 1) throw failure }

        assertIs<TransactionResult.Success<*>>(store action { x mutate 1 })

        assertEquals(listOf<Throwable>(failure), seen.toList())
        assertEquals(1, store.x.value)
    }

    @Test
    fun anOnFrameCommittedFatalErrorIsThrownWithTheFrameCommittedAndEveryObserverTold() {
        val a = FcsStore()
        val b = FcsStore()
        val seen = listOf(a, b).map { it.recordUncaught() }
        val fatal = StackOverflowError()
        val first = register(FcsFrameObserver(setOf(a, b)).apply { committedThrows = fatal })
        val second = register(FcsFrameObserver(setOf(a, b)))

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(a, b) {
                    a { x mutate 1 }
                    b { x mutate 2 }
                }
            }

        assertSame(fatal, thrown)
        assertEquals(1, a.x.value)
        assertEquals(2, b.x.value)
        assertEquals(listOf("started", "committed"), first.events.toList())
        assertEquals(listOf("started", "committed"), second.events.toList(), "told, and no rollback")
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        a.assertNotWedged()
        b.assertNotWedged()
    }

    @Test
    fun anOnFrameStartedFatalErrorRollsTheFrameBackBeforeTheBodyAndTellsEveryObserver() {
        val a = FcsStore()
        val b = FcsStore()
        val seen = listOf(a, b).map { it.recordUncaught() }
        val fatal = StackOverflowError()
        val first = register(FcsFrameObserver(setOf(a, b)).apply { startedThrows = fatal })
        val second = register(FcsFrameObserver(setOf(a, b)))
        var bodyRan = false

        val thrown =
            assertFailsWith<StackOverflowError> {
                atomic(a, b) {
                    bodyRan = true
                    a { x mutate 1 }
                }
            }

        assertSame(fatal, thrown)
        assertEquals(false, bodyRan)
        assertEquals(0, a.x.value)
        assertEquals(0, b.x.value)
        assertEquals(listOf("started", "rolledBack"), first.events.toList())
        assertEquals(listOf("started", "rolledBack"), second.events.toList())
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        a.assertNotWedged()
        b.assertNotWedged()
    }

    @Test
    fun aProfilingSampleCallbacksFatalErrorRollsTheActionBackAndPropagates() {
        val store = FcsStore()
        val seen = store.recordUncaught()
        val fatal = StackOverflowError()
        var sampleThrows: Throwable? = fatal
        val profiler = ProfilingMiddleware<FcsStore> { sampleThrows?.let { throw it } }
        store.middlewares(profiler)

        assertSame(fatal, assertFailsWith<StackOverflowError> { store action { x mutate 1 } })

        assertEquals(0, store.x.value, "rolled back")
        val profile = profiler.profile()
        assertEquals(1, profile.committedCount, "recorded once, by the completed hook")
        assertEquals(0, profile.rolledBackCount, "the re-fired error hook records nothing")
        assertEquals(emptyList(), seen)
        sampleThrows = null
        store.assertNotWedged()
        assertEquals(2, profiler.profile().committedCount)
    }

    @Test
    fun aKvBridgeCodecsFatalErrorPropagatesOutOfTheBridgeSetter() {
        val store = FcsCountStore()
        val seen = store.recordUncaught()
        val kv = InMemoryKvStore().apply { put("k", "41") }
        val codec = FcsLongCodec()
        val fatal = StackOverflowError()
        codec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { store { count bridge KvBridge(kv, "k", codec) } })

        assertEquals(0L, store.count.value)
        assertEquals(emptyList(), seen)
        assertNull(store.activeTransaction)
        assertThreadClean()

        // Control: a value the codec cannot read is still skipped silently.
        codec.decodeThrows = IllegalArgumentException("unreadable")
        store { count bridge KvBridge(kv, "k", codec) }
        assertEquals(0L, store.count.value)
        codec.decodeThrows = null
        store { count bridge KvBridge(kv, "k", codec) }
        assertEquals(41L, store.count.value)
        assertEquals(emptyList(), seen)
    }
}
