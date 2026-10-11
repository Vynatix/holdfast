@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

private class IosSettingsStore : Store<IosSettingsStore>() {
    var initializerRuns = 0
    val theme by state {
        initializerRuns++
        "light"
    }
}

private class IosThreadStore(
    val id: String,
) : Store<IosThreadStore>()

/** How many child stores [IosApp]'s declarations constructed. */
private var iosChildConstructions = 0

private object IosApp : Store<IosApp>() {
    val settings by store {
        iosChildConstructions++
        IosSettingsStore()
    }
    val session by store(named = "session-v2") {
        iosChildConstructions++
        IosSettingsStore()
    }
    val threads by keyed<String, IosThreadStore> { IosThreadStore(it) }
}

private object IosRefParent : Store<IosRefParent>() {
    val leaf by store { IosRefLeaf }
}

private object IosRefLeaf : Store<IosRefLeaf>() {
    val parentName: String = IosRefParent.tree.node.name
}

private object IosSelfApp : Store<IosSelfApp>() {
    val settings by store { IosSelfLeaf }
}

private object IosSelfLeaf : Store<IosSelfLeaf>() {
    val back: Store<*> = IosSelfApp.settings
}

/**
 * Kotlin/Native's object initialization differs from the JVM's (no
 * `ExceptionInInitializerError`, no poisoning), so only platform-neutral
 * facts are asserted here; `ObjectInitOrderTest` pins the JVM-specific ones.
 */
class ObjectInitOrderIosTest {
    @Test
    fun anObjectParentDeclaresItsChildrenOnFirstAccessAndRunsNoChildCode() {
        assertEquals(listOf<StoreNode>(IosApp.threads), IosApp.treeAttachment().registry.liveChildNodes())
        assertEquals(0, iosChildConstructions)
        val settings = IosApp.settings
        assertEquals(1, iosChildConstructions)
        assertEquals(0, settings.initializerRuns)
        assertEquals(listOf("settings", "session-v2", "threads"), IosApp.tree.children().map { it.name })
        assertEquals(2, iosChildConstructions)
    }

    @Test
    fun aLeafObjectReferencingItsParentDuringInitIsFine() {
        assertSame(IosRefLeaf, IosRefParent.leaf)
        assertEquals("IosRefParent", IosRefLeaf.parentName)
        assertSame(IosRefParent.tree.node, IosRefLeaf.tree.parent)
    }

    @Test
    fun aLeafObjectReadingItsOwnDelegateOnTheParentFails() {
        assertFails { IosSelfApp.settings }
    }
}
