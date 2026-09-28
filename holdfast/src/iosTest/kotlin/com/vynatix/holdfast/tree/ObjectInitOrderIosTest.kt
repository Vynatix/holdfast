@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

private class IosSettingsStore : Store<IosSettingsStore>() {
    var initializerRuns = 0
    val theme by state {
        initializerRuns++
        "light"
    }
}

private object IosApp : Root("app") {
    val settings by branch(IosSettingsStore())
    val session by branch(IosSettingsStore()).named("session")
}

private val iosDoubleListed = IosSettingsStore()

private object IosDoubleListing : Root() {
    val a by branch(iosDoubleListed)
    val b by branch(iosDoubleListed)
}

/**
 * Kotlin/Native's object initialization differs from the JVM's (no
 * `ExceptionInInitializerError`, no poisoning), so only platform-neutral
 * facts are asserted here; `ObjectInitOrderTest` pins the JVM-specific ones.
 * A forward `under` reference does not compile on any target, so no test
 * pins it.
 */
class ObjectInitOrderIosTest {
    @Test
    fun anObjectRootBuildsItsTreeOnFirstAccessAndRunsNoLeafInitializer() {
        val settingsStore = IosApp.settings.stores.single() as IosSettingsStore
        assertEquals(0, settingsStore.initializerRuns)
        assertEquals(listOf("settings", "session"), IosApp.nodes.filter { it is Branch }.map { it.name })
        assertEquals("app", IosApp.name)
    }

    @Test
    fun aDoubleListingFailsTheObjectsInitialization() {
        assertFails { IosDoubleListing.a }
    }
}
