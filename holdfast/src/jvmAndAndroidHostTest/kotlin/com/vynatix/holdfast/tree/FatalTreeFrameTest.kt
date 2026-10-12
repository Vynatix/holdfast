@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.bridge.IntCodec
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, the store tree's codec and frame paths: a fatal error from a
// keyed branch's key codec, or from an initializer a tree reset or restore
// runs while it prepares, propagates out of the create, decode, reset or
// restore — before any transaction opens — and is never a refusal, a format
// error or an Error result, and never reported.

/** A keyed store with an encodable state, so a tree envelope carries a body for it. */
private class FtfCodecLeaf : Store<FtfCodecLeaf>() {
    val n by state(codec = IntCodec) { 0 }
}

/** A String key codec that throws [encodeThrows]/[decodeThrows] while set. */
private class FtfKeyCodec : StateCodec<String> {
    @Volatile var encodeThrows: Throwable? = null

    @Volatile var decodeThrows: Throwable? = null

    override fun encode(value: String): String {
        encodeThrows?.let { throw it }
        return value
    }

    override fun decode(string: String): String {
        decodeThrows?.let { throw it }
        return string
    }
}

private class FtfCodecParent : Store<FtfCodecParent>() {
    var runs = 0
    val keyCodec = FtfKeyCodec()
    val n by state { 0 }
    val docs by keyed<String, FtfCodecLeaf>(keyCodec = keyCodec) {
        runs++
        FtfCodecLeaf()
    }
}

private class FtfResetChild(
    private val initThrows: Throwable,
) : Store<FtfResetChild>() {
    val n by state { 0 }
    val lazy by state<Int> { throw initThrows }
}

private class FtfResetParent(
    initThrows: Throwable,
) : Store<FtfResetParent>() {
    val n by state { 0 }
    val child by store { FtfResetChild(initThrows) }
}

/** [lazy]'s initializer throws [initThrows] when given; nothing reads it before a restore. */
private class FtfPlanChild(
    private val initThrows: Throwable?,
) : Store<FtfPlanChild>() {
    val n by state(codec = IntCodec) { 0 }
    val lazy by state(codec = IntCodec) {
        initThrows?.let { throw it }
        1
    }
}

private class FtfPlanParent(
    initThrows: Throwable?,
) : Store<FtfPlanParent>() {
    val n by state(codec = IntCodec) { 0 }
    val child by store { FtfPlanChild(initThrows) }
}

private class FtfStarted<S : Store<S>> : Middleware<S>() {
    val ids = CopyOnWriteArrayList<String>()

    override fun onTransactionStarted(context: MiddlewareContext<S>) {
        ids += context.transaction.id
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

class FatalTreeFrameTest {
    @Test
    fun aKeyCodecsFatalErrorPropagatesFromCreateBeforeTheFactoryRuns() {
        val parent = FtfCodecParent()
        val seen = parent.recordUncaught()
        val fatal = StackOverflowError()
        parent.keyCodec.encodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.docs.create("a") })
        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.docs.getOrCreate("a") })
        assertEquals(0, parent.runs)

        // Control: a non-fatal naming failure is still the branch's own refusal.
        parent.keyCodec.encodeThrows = IllegalArgumentException("no name for it")
        val refused = assertFailsWith<IllegalStateException> { parent.docs.create("a") }
        assertTrue("could not name the key" in refused.message.orEmpty(), refused.message)
        assertEquals(0, parent.runs)
        parent.keyCodec.encodeThrows = null
        parent.docs.create("a")
        assertEquals(1, parent.runs)
        assertEquals(emptyList(), seen)
        assertThreadClean()
    }

    @Test
    fun aKeyCodecsFatalErrorPropagatesFromTreeDecodeNotAsAFormatError() {
        val parent = FtfCodecParent()
        val seen = parent.recordUncaught()
        parent.docs.create("a") action { n mutate 3 }
        val text = parent.tree.snapshot().encode()
        val fatal = StackOverflowError()
        parent.keyCodec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.tree.decode(text) })

        parent.keyCodec.decodeThrows = IllegalArgumentException("unreadable key")
        assertFailsWith<SnapshotFormatException> { parent.tree.decode(text) }
        parent.keyCodec.decodeThrows = null
        assertEquals(3, parent.tree.decode(text)[checkNotNull(parent.docs["a"]).n])
        assertEquals(emptyList(), seen)
        assertThreadClean()
    }

    @Test
    fun aTreeResetsInitializerFatalErrorIsThrownBeforeAnyTransactionOpens() {
        val fatal = StackOverflowError()
        val parent = FtfResetParent(fatal)
        val child = parent.child
        val parentSeen = parent.recordUncaught()
        val childSeen = child.recordUncaught()
        val parentStarted = FtfStarted<FtfResetParent>()
        val childStarted = FtfStarted<FtfResetChild>()
        parent.middlewares(parentStarted)
        child.middlewares(childStarted)
        parent action { n mutate 1 }
        child action { n mutate 2 }
        parentStarted.ids.clear()
        childStarted.ids.clear()

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.tree.reset() })

        assertEquals(emptyList(), parentStarted.ids.toList(), "no tree-reset transaction started")
        assertEquals(emptyList(), childStarted.ids.toList())
        assertEquals(1, parent.n.value)
        assertEquals(2, child.n.value)
        assertEquals(emptyList(), parentSeen)
        assertEquals(emptyList(), childSeen)
        assertNull(parent.activeTransaction)
        assertNull(child.activeTransaction)
        assertThreadClean()
        child action { n mutate 3 }
        assertFalse(child.isDisposed)
        assertEquals(3, child.n.value)
    }

    @Test
    fun aTreeRestoresPlanFatalErrorIsThrownBeforeAnyTransactionOpens() {
        val source = FtfPlanParent(initThrows = null)
        source action { n mutate 4 }
        source.child action { n mutate 5 }
        val text = source.tree.snapshot().encode()
        val fatal = StackOverflowError()
        val parent = FtfPlanParent(initThrows = fatal)
        val child = parent.child
        val seen = listOf(parent, child).map { it.recordUncaught() }
        val parentStarted = FtfStarted<FtfPlanParent>()
        val childStarted = FtfStarted<FtfPlanChild>()
        parent.middlewares(parentStarted)
        child.middlewares(childStarted)
        val decoded = parent.tree.decode(text)

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.tree.restore(decoded) })

        assertEquals(emptyList(), parentStarted.ids.toList(), "no tree-restore transaction started")
        assertEquals(emptyList(), childStarted.ids.toList())
        assertEquals(0, parent.n.value)
        assertEquals(0, child.n.value)
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        assertNull(parent.activeTransaction)
        assertNull(child.activeTransaction)
        assertThreadClean()
        parent action { n mutate 1 }
        assertEquals(1, parent.n.value)
    }
}
