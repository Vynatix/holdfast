@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.InitializerGraph
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class SharedLeft : Store<SharedLeft>() {
    lateinit var right: SharedRight
    val x by state { right.y.value + 1 }
}

private class SharedRight : Store<SharedRight>() {
    val y by state { 10 }
}

private class SharedCycle : Store<SharedCycle>() {
    val a: State<Int> by state<Int> { b.value + 1 }
    val b: State<Int> by state<Int> { a.value + 1 }
}

private class SharedRoot : Root() {
    val left = SharedLeft()
    val right = SharedRight()
    val pair by branch(left, right)
}

/**
 * wasmJs reports thread id `0` for every caller. Modelled here as every
 * leaf sharing one initializer graph whose thread is always `0`: a tree
 * capture over such leaves neither invents a cycle across stores nor hangs
 * on a genuine one.
 */
class TreeSharedThreadModelTest {
    @Test
    fun aRootValueSettlesUnderTheSharedThreadIdModel() {
        val graph = InitializerGraph { 0L }
        val root = SharedRoot()
        root.left.right = root.right
        root.left.initializerGraph = graph
        root.right.initializerGraph = graph
        assertEquals(11, root.value.value[root.left.x])
        root.right action { y mutate 20 }
        assertEquals(20, root.value.value[root.right.y])
        assertEquals(2, root.internalSettleCount)
        assertEquals(0, graph.waitingCount)
    }

    @Test
    fun aCaptureUnderTheSharedThreadIdModelMaterializesAcrossStoresWithoutAFalseCycle() {
        val graph = InitializerGraph { 0L }
        val root = SharedRoot()
        root.left.right = root.right
        root.left.initializerGraph = graph
        root.right.initializerGraph = graph
        val tree = root.snapshot()
        assertEquals(11, tree[root.left.x])
        assertEquals(10, tree[root.right.y])
        assertEquals(0, graph.waitingCount)
    }

    @Test
    fun aGenuineCycleStillThrowsInsteadOfHangingTheCapture() {
        val graph = InitializerGraph { 0L }

        class CycleRoot : Root() {
            val cycle = SharedCycle()
            val leaf by branch(cycle)
        }
        val root = CycleRoot()
        root.cycle.initializerGraph = graph
        assertFailsWith<IllegalStateException> { root.snapshot() }
        assertEquals(0, graph.waitingCount)
    }
}
