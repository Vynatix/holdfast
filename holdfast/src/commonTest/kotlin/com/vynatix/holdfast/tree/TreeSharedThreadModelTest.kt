@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.InitializerGraph
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
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
