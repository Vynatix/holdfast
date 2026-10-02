@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.bridge.IntCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CmLeafStore : Store<CmLeafStore>() {
    var initializerRuns = 0
    val n by state(codec = IntCodec) {
        initializerRuns++
        0
    }
}

/** A child whose constructor writes: legal anywhere but inside a no-write region (an initializer, a migrate, a compute). */
private class CmCtorActionStore : Store<CmCtorActionStore>() {
    val n by state { 0 }

    init {
        action { n mutate 1 }
    }
}

/** A child with a child of its own, so a materialization has a subtree to recurse into. */
private class CmMiddleStore : Store<CmMiddleStore>() {
    var grandchildRuns = 0
    val grandchild by store {
        grandchildRuns++
        CmLeafStore()
    }
}

private class CmLazyParent : Store<CmLazyParent>() {
    var leafRuns = 0
    var middleRuns = 0
    val leaf by store {
        leafRuns++
        CmLeafStore()
    }
    val middle by store {
        middleRuns++
        CmMiddleStore()
    }
}

/** A declaration whose lambda reads the declaration itself. */
private class CmLoop : Store<CmLoop>() {
    val c: CmLeafStore by store { c }
}

/** A store declared as its own child. */
private class CmSelfish : Store<CmSelfish>() {
    val me by store { this }
}

/** [CmAncestorB] declares the store it hangs under as its own child. */
private class CmAncestorA : Store<CmAncestorA>() {
    val b by store { CmAncestorB(this) }
}

private class CmAncestorB(
    above: CmAncestorA,
) : Store<CmAncestorB>() {
    val a by store { above }
}

/** `x`'s initializer materializes `settings`, whose constructor reads `x`: an initializer → child → initializer cycle. */
private class CmCycParent : Store<CmCycParent>() {
    val x: State<Int> by state { settings.n.value }
    val settings by store { CmCycChild(this) }
}

private class CmCycChild(
    parent: CmCycParent,
) : Store<CmCycChild>() {
    val n by state { 1 }

    init {
        parent.x.value
    }
}

private class CmFlaky : Store<CmFlaky>() {
    var runs = 0
    var failNext = true
    val child by store {
        runs++
        check(!failNext.also { failNext = false }) { "the first construction fails" }
        CmLeafStore()
    }
}

private class CmHolder(
    shared: CmLeafStore,
) : Store<CmHolder>() {
    val c by store { shared }
}

private class CmQuietParent : Store<CmQuietParent>() {
    val leaf by store { CmLeafStore() }
    val inAction by store { CmLeafStore() }
    val inFrame by store { CmLeafStore() }
}

private class CmStartedCounter : Middleware<CmQuietParent>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<CmQuietParent>) {
        started++
    }
}

/** `x`'s initializer materializes a child whose constructor writes. */
private class CmInitParent : Store<CmInitParent>() {
    var writerRuns = 0
    val x: State<Int> by state { writer.n.value }
    val writer by store {
        writerRuns++
        CmCtorActionStore()
    }
}

private class CmListened : Store<CmListened>() {
    val child by store { CmLeafStore() }
}

private class CmRecording : LeafMembershipListener() {
    val events = ArrayList<String>()

    override fun onAttached(leaf: LeafNode) {
        events += "attached ${leaf.name}"
    }

    override fun onDetached(leaf: LeafNode) {
        events += "detached ${leaf.name}"
    }
}

/**
 * Children are declared eagerly and materialized lazily, behind the same
 * latch as state initializers: once, outside every lock of the tree, and
 * with cycles through child declarations and initializers reported instead
 * of deadlocking. The cross-thread cases are in
 * `ChildMaterializationThreadTest` (JVM host).
 */
class ChildMaterializationTest {
    @Test
    fun aDeclarationRunsNothingAndTheFirstReadMaterializesTheChildOnce() {
        val parent = CmLazyParent()
        assertEquals(0, parent.leafRuns)
        val registry = parent.treeAttachment().registry
        assertTrue(registry.liveChildNodes().isEmpty(), "nothing is listed before it runs")
        val first = parent.leaf
        assertEquals(1, parent.leafRuns)
        assertSame(first, parent.leaf)
        assertSame(first, parent.leaf)
        assertEquals(1, parent.leafRuns, "the lambda runs once")
        assertEquals(0, first.initializerRuns, "materializing a child never runs its state initializers")
        assertEquals(0, parent.middleRuns, "reading one delegate materializes that child only")
    }

    @Test
    fun childrenMaterializesEveryDeclaredChildRecursivelyAndNoStateInitializer() {
        val parent = CmLazyParent()
        assertEquals(listOf("leaf", "middle"), parent.tree.children().map { it.name })
        assertEquals(1, parent.leafRuns)
        assertEquals(1, parent.middleRuns)
        assertEquals(1, parent.middle.grandchildRuns, "a child's own declarations materialize too")
        assertEquals(0, parent.middle.grandchild.initializerRuns)
        assertEquals(0, parent.leaf.initializerRuns)
        parent.tree.children()
        parent.tree.stores()
        assertEquals(1, parent.leafRuns, "a materialized child is never run again")
        assertEquals(1, parent.middle.grandchildRuns)
    }

    @Test
    fun aDeclarationReadingItselfIsASameThreadCycleAndStaysRetryable() {
        val loop = CmLoop()
        val error = assertFailsWith<IllegalStateException> { loop.c }
        val message = assertNotNull(error.message)
        assertTrue(message.startsWith("Materialization cycle: child declaration 'CmLoop.c'"), message)
        assertTrue("child declaration 'CmLoop.c' → child declaration 'CmLoop.c'" in message, message)
        // Nothing was published: the next read runs the lambda (and meets the cycle) again.
        assertFailsWith<IllegalStateException> { loop.c }
        assertFailsWith<IllegalStateException> { loop.tree.children() }
    }

    @Test
    fun aStoreDeclaredAsItsOwnChildIsRefused() {
        val selfish = CmSelfish()
        val error = assertFailsWith<IllegalStateException> { selfish.me }
        assertTrue("CmSelfish.me would make CmSelfish its own ancestor" in error.message!!, error.message)
        assertNull(selfish.tree.parent)
    }

    @Test
    fun aChildDeclaringAStoreAboveItIsRefusedNamingThePath() {
        val a = CmAncestorA()
        val b = a.b
        val error = assertFailsWith<IllegalStateException> { b.a }
        val message = assertNotNull(error.message)
        assertTrue("CmAncestorB.a would make CmAncestorA its own ancestor: CmAncestorA/b/CmAncestorA" in message, message)
        assertNull(a.tree.parent, "nothing was linked")
        assertSame(a.tree.node, b.tree.parent)
    }

    @Test
    fun anInterleavedInitializerChildInitializerCyclePrintsBothKindsInOrder() {
        val parent = CmCycParent()
        val error = assertFailsWith<IllegalStateException> { parent.x.value }
        val message = assertNotNull(error.message)
        assertTrue(message.startsWith("Materialization cycle"), message)
        assertTrue(
            "state initializer 'CmCycParent.x' → child declaration 'CmCycParent.settings' → state initializer 'CmCycParent.x'" in
                message,
            message,
        )
    }

    @Test
    fun aThrowingLambdaPublishesNothingAndTheNextReadRunsItAgain() {
        val parent = CmFlaky()
        val error = assertFailsWith<IllegalStateException> { parent.child }
        assertEquals("the first construction fails", error.message)
        val entry = assertNotNull(parent.treeAttachment().registry.declaredEntry("child"))
        assertEquals(ChildEntry.Phase.Declared, entry.phase)
        assertNull(entry.produced)
        val child = parent.child
        assertEquals(2, parent.runs)
        assertSame(parent.tree.node, child.tree.parent)
        assertEquals(listOf<Store<*>>(parent, child), parent.tree.stores())
    }

    @Test
    fun aRefusedAttachLeavesTheEntryDeclaredUnlistedAndUndecodableUntilItSucceeds() {
        val shared = CmLeafStore()
        val owner = CmHolder(shared)
        owner.c
        shared action { n mutate 5 }
        val refused = CmHolder(shared)
        assertFailsWith<IllegalStateException> { refused.c }

        val registry = refused.treeAttachment().registry
        val entry = assertNotNull(registry.declaredEntry("c"))
        assertEquals(ChildEntry.Phase.Declared, entry.phase, "the refusal (attach phase 3) left the entry retryable")
        assertNull(entry.node)
        assertNull(entry.produced)
        assertTrue(registry.liveChildNodes().isEmpty(), "a refused child is never listed")
        // Decoding (here without the materialization the public decode runs first) cannot resolve it.
        val text = owner.tree.snapshot().encode()
        assertEquals(listOf(listOf("c")), decodeTree(refused.tree.node, text).unresolvedPaths)
        assertFailsWith<IllegalStateException>("every tree operation materializes, and meets the refusal") {
            refused.tree.decode(text)
        }

        // Once the conflict is gone, the same declaration attaches and decodes.
        owner.dispose()
        assertSame(shared, refused.c)
        val decoded = refused.tree.decode(text)
        assertTrue(decoded.unresolvedPaths.isEmpty(), "${decoded.unresolvedPaths}")
        assertEquals(5, decoded[shared.n])
    }

    @Test
    fun materializationInsideAnotherStoresActionOrFrameOpensNoTransactionOnTheParent() {
        val parent = CmQuietParent()
        val leaf = parent.leaf
        val counter = CmStartedCounter()
        parent.middlewares(counter)

        val inAction = leaf action { parent.inAction }
        assertIs<TransactionResult.Success<*>>(inAction)
        val inFrame = atomic(leaf) { parent.inFrame }
        assertIs<TransactionResult.Success<*>>(inFrame)

        assertEquals(0, counter.started, "attaching a child is not a write on its parent")
        assertSame(parent.tree.node, parent.inAction.tree.parent)
        assertSame(parent.tree.node, parent.inFrame.tree.parent)
    }

    @Test
    fun aChildFirstReadInsideAnInitializerInheritsItsNoWriteRegion() {
        val parent = CmInitParent()
        val error = assertFailsWith<IllegalStateException> { parent.x.value }
        val message = assertNotNull(error.message)
        assertTrue("Cannot open an action on CmCtorActionStore" in message, message)
        assertTrue("the initializer of CmInitParent.x is running" in message, message)
        assertEquals(1, parent.writerRuns)
        // Read outside the initializer, the same constructor writes freely, and the initializer then reads it.
        assertEquals(1, parent.writer.n.value)
        assertEquals(2, parent.writerRuns)
        assertEquals(1, parent.x.value)
    }

    @Test
    fun aThrowingOnAttachedListenerIsReportedTheChildAttachesAndItsDisposeIsAnnounced() {
        val parent = CmListened()
        val reported = ArrayList<Throwable>()
        parent.uncaughtObserverHandler = { reported += it }
        parent.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onAttached(leaf: LeafNode) = error("listener refused")
            },
        )
        val recording = CmRecording()
        parent.internalAddMembershipListener(recording)

        val child = parent.child

        assertEquals(listOf("listener refused"), reported.map { it.message })
        assertEquals(listOf("attached child"), recording.events, "the other listeners are still told")
        assertSame(parent.tree.node, child.tree.parent)
        child.dispose()
        assertEquals(listOf("attached child", "detached child"), recording.events)
    }
}
