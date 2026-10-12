@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.lifecycle

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.tree.keyed
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private open class ChildStore : Store<ChildStore>() {
    val marker by state { 0 }
    var disposeCalls = 0
        private set

    override fun onDispose() {
        disposeCalls++
    }
}

private class ScreenStore(
    private val built: MutableList<ChildStore> = mutableListOf(),
) : Store<ScreenStore>() {
    val plain by store { ChildStore().also(built::add) }
    val keyedBranch by keyed<String, ChildStore> { ChildStore().also(built::add) }
}

/** A root whose `plain`-child lambda throws whenever it materializes. */
private class ThrowingScreenStore : Store<ThrowingScreenStore>() {
    var disposeCalls = 0
        private set

    val bad by store<ChildStore> { error("child factory refused") }

    override fun onDispose() {
        disposeCalls++
    }
}

class TreeViewModelTest {
    @Test
    fun storeViewModelDisposesTheRootAndItsKeyedStoresOnly() {
        val screen = ScreenStore()
        val vm = storeViewModel { screen }
        val root = vm.store

        val plain = root.plain
        val keyedChild = root.keyedBranch.getOrCreate("a")

        vm.clearThroughViewModelStore()

        // Root gone, the keyed branch disposed with it (GUIDE §17.3) …
        assertTrue(root.isDisposed)
        assertEquals(1, keyedChild.disposeCalls)
        // … and the plain child only RELEASED — alive and usable, its own root.
        assertFalse(plain.isDisposed, "a plain store { } child is released, not disposed")
        assertIs<TransactionResult.Success<*>>(plain.action { })
        assertNull(plain.tree.parent)
    }

    @Test
    fun treeViewModelDisposesEveryMaterializedStoreOfTheSubtree() {
        val screen = ScreenStore()
        val vm = treeViewModel { screen }
        val root = vm.store

        val plain = root.plain
        val keyedChild = root.keyedBranch.getOrCreate("a")

        vm.clearThroughViewModelStore()

        assertTrue(root.isDisposed)
        assertEquals(1, plain.disposeCalls, "the plain child is disposed, not released")
        assertEquals(1, keyedChild.disposeCalls)
    }

    @Test
    fun treeViewModelMaterializesNeverReadChildrenAtClearThenDisposesThem() {
        val built = mutableListOf<ChildStore>()
        val vm = treeViewModel { ScreenStore(built) }
        val root = vm.store

        assertTrue(built.isEmpty()) // nothing materialized during the ViewModel's life

        vm.clearThroughViewModelStore()

        // The teardown listing materializes never-read `store { }` children
        // (a keyed factory runs only on create/getOrCreate) so it can dispose
        // them — the documented behavior.
        assertEquals(1, built.size)
        assertTrue(built.all { it.isDisposed })
        assertTrue(built.all { it.disposeCalls == 1 })
        assertTrue(root.isDisposed)
    }

    @Test
    fun treeViewModelReportsAThrowingChildFactoryAndDisposesTheRootRegardless() {
        val vm = treeViewModel { ThrowingScreenStore() }
        val root = vm.store

        var reported: Throwable? = null
        root.uncaughtObserverHandler = { reported = it }

        vm.clearThroughViewModelStore()

        // The child lambda's throw is reported through the root's failure
        // channel, wrapped in the teardown exception — never silent — and the
        // root's dispose still ran.
        val teardown = assertIs<StoreTreeTeardownException>(reported)
        assertEquals("child factory refused", teardown.cause?.message)
        assertTrue(root.isDisposed)
        assertEquals(1, root.disposeCalls)
    }

    @Test
    fun treeViewModelOnAChildlessRootDisposesJustTheRoot() {
        val root = ChildStore()
        val vm = treeViewModel { root }
        assertSame(root, vm.store) // ownership starts at the first read
        vm.clearThroughViewModelStore()
        assertTrue(root.isDisposed)
    }
}
