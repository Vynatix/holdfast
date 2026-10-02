@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class ObjSettingsStore : Store<ObjSettingsStore>() {
    var initializerRuns = 0
    val theme by state {
        initializerRuns++
        "light"
    }
}

private class ObjThreadStore(
    val id: String,
) : Store<ObjThreadStore>()

/** How many child stores [ObjApp]'s declarations constructed. */
private val objChildConstructions = AtomicInteger()

/** The documented shape: an `object` parent whose children are built on first need. */
private object ObjApp : Store<ObjApp>() {
    val settings by store {
        objChildConstructions.incrementAndGet()
        ObjSettingsStore()
    }
    val session by store(named = "session-v2") {
        objChildConstructions.incrementAndGet()
        ObjSettingsStore()
    }
    val threads by stores<String, ObjThreadStore> { ObjThreadStore(it) }
}

/** A child object that reads its PARENT (never the parent's delegate for itself) while it initializes. */
private object ObjRefParent : Store<ObjRefParent>() {
    val leaf by store { ObjRefLeaf }
}

private object ObjRefLeaf : Store<ObjRefLeaf>() {
    val n by state { 0 }
    val parentName: String = ObjRefParent.tree.node.name
}

/** A child object that reads the parent's delegate FOR ITSELF while it initializes: a materialization cycle. */
private object ObjSelfApp : Store<ObjSelfApp>() {
    val settings by store { ObjSelfLeaf }
}

private object ObjSelfLeaf : Store<ObjSelfLeaf>() {
    val back: Store<*> = ObjSelfApp.settings
}

private val doubleListed = ObjSettingsStore()

private object ObjDoubleListing : Store<ObjDoubleListing>() {
    val a by store { doubleListed }
    val b by store { doubleListed }
}

private val slowParentGate = CountDownLatch(1)

/** Parks inside its initializer until the test releases it. */
private object ObjSlowParent : Store<ObjSlowParent>() {
    val leaf by store { ObjSettingsStore() }

    init {
        slowParentGate.await()
    }
}

/** A child object whose initializer needs the parent: initialized on another thread, it waits for the parent's initializer. */
private object ObjLateLeaf : Store<ObjLateLeaf>() {
    val n by state { 0 }
    val parentName: String = ObjSlowParent.tree.node.name
}

/**
 * Object parents follow JVM class-initialization rules; the tree adds
 * nothing to them but its lazy child declarations, so these pin what a
 * consumer sees. Each object is touched by one test only (an object's
 * initialization happens once per process).
 */
class ObjectInitOrderTest {
    @Test
    fun anObjectParentDeclaresItsChildrenOnFirstAccessAndRunsNoChildCode() {
        // Declared, not materialized: the registry lists only the keyed branch, and no child was constructed.
        val registry = ObjApp.treeAttachment().registry
        assertEquals(listOf<StoreNode>(ObjApp.threads), registry.liveChildNodes())
        assertEquals(0, objChildConstructions.get())

        val settings = ObjApp.settings
        assertEquals(1, objChildConstructions.get(), "reading a delegate materializes that child only")
        assertEquals(0, settings.initializerRuns, "materializing a child runs none of its state initializers")
        assertEquals(listOf("settings", "threads"), registry.liveChildNodes().map { it.name })

        // `children` materializes every declared child that has not run yet.
        assertEquals(listOf("settings", "session-v2", "threads"), ObjApp.tree.children.map { it.name })
        assertEquals(2, objChildConstructions.get())
        assertEquals("ObjApp", ObjApp.tree.node.name)
    }

    @Test
    fun aLeafObjectReferencingItsParentDuringInitIsFine() {
        val leaf = ObjRefParent.leaf
        assertSame(ObjRefLeaf, leaf)
        assertEquals("ObjRefParent", ObjRefLeaf.parentName)
        assertSame(ObjRefParent.tree.node, ObjRefLeaf.tree.parent)
        assertEquals("leaf", ObjRefLeaf.tree.node.name)
    }

    @Test
    fun aLeafObjectReadingItsOwnDelegateOnTheParentFailsWhenTheParentIsTouchedFirst() {
        // The parent first: its delegate's lambda initializes the leaf object, whose
        // initializer reads that same delegate back — the latch the lambda runs under.
        val error = assertFailsWith<ExceptionInInitializerError> { ObjSelfApp.settings }
        val cause = assertIs<IllegalStateException>(error.cause)
        assertTrue("child declaration 'ObjSelfApp.settings'" in cause.message!!, cause.message)
        // The JVM poisons the leaf object for the process.
        assertFailsWith<NoClassDefFoundError> { ObjSelfLeaf }
    }

    @Test
    fun aDoubleListingFailsTheSecondReadWithoutPoisoningTheObject() {
        assertSame(doubleListed, ObjDoubleListing.a)
        val error = assertFailsWith<IllegalStateException> { ObjDoubleListing.b }
        assertTrue("already belongs to ObjDoubleListing/a" in error.message!!, error.message)
        assertTrue("cannot also be declared under ObjDoubleListing/b" in error.message!!, error.message)
        // A refusal at materialization, not at class initialization: the object keeps working.
        assertSame(doubleListed, ObjDoubleListing.a)
        assertFailsWith<IllegalStateException> { ObjDoubleListing.b }
    }

    @Test
    fun aLeafObjectInitializedOnAnotherThreadWhileTheParentInitializesCompletesOnlyWhenTheParentDoes() =
        completesWithin(20, "a leaf object racing its parent's initialization") {
            val parentStarted = CountDownLatch(1)
            val parentThread =
                daemon("parent-init") {
                    parentStarted.countDown()
                    ObjSlowParent.leaf
                }
            parentStarted.await()
            Thread.sleep(100)
            val leafDone = CountDownLatch(1)
            val leafThread =
                daemon("leaf-init") {
                    ObjLateLeaf.parentName
                    leafDone.countDown()
                }
            // The JVM holds the leaf thread on the parent's class-init lock.
            assertTrue(!leafDone.await(300, TimeUnit.MILLISECONDS), "the leaf's initializer must wait for the parent's")
            assertTrue(parentThread.isAlive, "the parent is still parked in its initializer")
            slowParentGate.countDown()
            parentThread.join()
            assertTrue(leafDone.await(5, TimeUnit.SECONDS), "the leaf completes once the parent has")
            leafThread.join()
            assertEquals("ObjSlowParent", ObjLateLeaf.parentName)
        }
}
