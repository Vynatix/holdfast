@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.lifecycle

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.tree.keyed
import com.vynatix.holdfast.tree.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FatalTeardownChild : Store<FatalTeardownChild>()

/** A root whose never-read child lambda overflows when the teardown lists the subtree. */
private class FatalTeardownScreen(
    private val fatal: Throwable,
) : Store<FatalTeardownScreen>() {
    val bad by store<FatalTeardownChild> { throw fatal }
    val entries by keyed<String, FatalTeardownChild> { FatalTeardownChild() }
}

/** Issue #61: a fatal error during `onCleared`'s subtree teardown is thrown, never wrapped or reported. */
class FatalTreeTeardownTest {
    @Test
    fun aChildLambdasStackOverflowIsThrownOnceEveryStoreIsDisposed() {
        val fatal = StackOverflowError()
        val vm = treeViewModel { FatalTeardownScreen(fatal) }
        val root = vm.store
        val entry = root.entries.getOrCreate("a")
        val reported = mutableListOf<Throwable>()
        root.uncaughtObserverHandler = { reported += it }

        val thrown = assertFailsWith<StackOverflowError> { vm.clearThroughViewModelStore() }

        assertSame(fatal, thrown, "never wrapped in StoreTreeTeardownException")
        assertEquals(emptyList(), reported.toList(), "never reported")
        assertTrue(root.isDisposed, "the root is still disposed")
        assertTrue(entry.isDisposed, "its keyed store too")
    }
}
