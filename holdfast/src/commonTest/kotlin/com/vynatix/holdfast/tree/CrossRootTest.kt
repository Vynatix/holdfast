@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class CrossStore : Store<CrossStore>() {
    val n by state { 0 }
}

private class CrossKeyedStore(
    id: String,
    root: CrossFirstRoot,
) : Store<CrossKeyedStore>(root.keyed.at(id)) {
    val n by state { 0 }
}

private class CrossFirstRoot : Root("first") {
    val base by branch(CrossStore())
    val keyed by keyed<String, CrossKeyedStore>()
}

/** T7.3: a root never accepts another root's node, at delegate time or in a subtree operation. */
class CrossRootTest {
    @Test
    fun aBranchOnOneRootRejectsANodeFromAnotherRootAtDelegateTime() {
        val first = CrossFirstRoot()
        val error =
            assertFailsWith<IllegalArgumentException> {
                object : Root("second") {
                    val nested by branch(CrossStore(), under = first.base)
                }
            }
        assertTrue("'base'" in error.message!! && "'first'" in error.message!!, error.message)
        assertTrue("second" in error.message!!, error.message)
    }

    @Test
    fun aKeyedDeclarationRejectsAForeignUnder() {
        val first = CrossFirstRoot()
        assertFailsWith<IllegalArgumentException> {
            object : Root("second") {
                val nested by keyed<String, CrossKeyedStore>(under = first.base)
            }
        }
    }

    @Test
    fun subtreeOperationsRejectAForeignNode() {
        val first = CrossFirstRoot()
        val second = CrossFirstRoot()
        assertFailsWith<IllegalArgumentException> { first.children(second.base) }
        assertFailsWith<IllegalArgumentException> { first.entries(second.keyed) }
        assertFailsWith<IllegalArgumentException> { first[second.keyed, "k"] }
    }
}
