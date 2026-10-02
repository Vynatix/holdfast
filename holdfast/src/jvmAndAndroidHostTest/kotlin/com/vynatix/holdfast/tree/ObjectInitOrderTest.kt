@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ObjSettingsStore : Store<ObjSettingsStore>() {
    var initializerRuns = 0
    val theme by state {
        initializerRuns++
        "light"
    }
}

/** The documented shape: an `object` root with per-object leaves. Built on first access. */
private object ObjApp : Root("app") {
    val settings by branch(ObjSettingsStore())
    val session by branch(ObjSettingsStore()).named("session")
}

// A forward `under` reference — `val inner by branch(x, under = outer)` above
// `val outer by branch(y)` — does not compile ("Variable 'outer' must be
// initialized"), on the JVM and on Kotlin/Native alike, so no runtime test
// pins it: declare `under` targets textually above their users.

private val doubleListed = ObjSettingsStore()

private object ObjDoubleListing : Root() {
    val a by branch(doubleListed)
    val b by branch(doubleListed)
}

/** A leaf `object` that reads its root's delegate while its own class initializes. */
private object ObjRootOfSelfTouching : Root("self") {
    val leaf by branch(ObjSelfTouching)
}

private object ObjSelfTouching : Store<ObjSelfTouching>() {
    val n by state { 0 }
    val parentBranch: Branch = ObjRootOfSelfTouching.leaf
}

private val slowRootGate = CountDownLatch(1)

/** Parks inside its initializer until the test releases it. */
private object ObjSlowRoot : Root("slow") {
    val leaf by branch(ObjSettingsStore())

    init {
        slowRootGate.await()
    }
}

/** A leaf object whose initializer needs the root: initialized on another thread, it waits for the root's initializer. */
private object ObjLateLeaf : Store<ObjLateLeaf>() {
    val n by state { 0 }
    val rootName: String = ObjSlowRoot.name
}

/** Object roots follow JVM class-initialization rules; the tree adds nothing to them, so these pin what a consumer sees. */
class ObjectInitOrderTest {
    @Test
    fun anObjectRootBuildsItsTreeOnFirstAccessAndRunsNoLeafInitializer() {
        val settingsStore = ObjApp.settings.stores.single() as ObjSettingsStore
        assertEquals(0, settingsStore.initializerRuns)
        assertEquals(listOf("settings", "session"), ObjApp.nodes.filter { it is Branch }.map { it.name })
        assertEquals("app", ObjApp.name)
    }

    @Test
    fun aDoubleListingPoisonsTheObjectForTheProcess() {
        val first = assertFailsWith<ExceptionInInitializerError> { ObjDoubleListing.a }
        assertIs<IllegalStateException>(first.cause)
        assertFailsWith<NoClassDefFoundError> { ObjDoubleListing.b }
    }

    @Test
    fun aLeafObjectTouchingItsRootsDelegateDuringItsOwnInitializationFails() {
        // Root first: its delegate lists the leaf object, whose initializer reads
        // the root's delegate back — a class-init cycle the JVM resolves by
        // handing the leaf a half-initialized root, whose delegate is null.
        val error = assertFailsWith<ExceptionInInitializerError> { ObjRootOfSelfTouching.leaf }
        assertIs<NullPointerException>(error.cause)
    }

    @Test
    fun aLeafObjectInitializedOnAnotherThreadWhileTheRootInitializesCompletesOnlyWhenTheRootDoes() =
        completesWithin(20, "a leaf object racing its root's initialization") {
            val rootStarted = CountDownLatch(1)
            val rootThread =
                daemon("root-init") {
                    rootStarted.countDown()
                    ObjSlowRoot.leaf
                }
            rootStarted.await()
            Thread.sleep(100)
            val leafDone = CountDownLatch(1)
            val leafThread =
                daemon("leaf-init") {
                    ObjLateLeaf.rootName
                    leafDone.countDown()
                }
            // The JVM holds the leaf thread on the root's class-init lock.
            assertTrue(!leafDone.await(300, TimeUnit.MILLISECONDS), "the leaf's initializer must wait for the root's")
            assertTrue(rootThread.isAlive, "the root is still parked in its initializer")
            slowRootGate.countDown()
            rootThread.join()
            assertTrue(leafDone.await(5, TimeUnit.SECONDS), "the leaf completes once the root has")
            leafThread.join()
            assertEquals("slow", ObjLateLeaf.rootName)
        }
}
