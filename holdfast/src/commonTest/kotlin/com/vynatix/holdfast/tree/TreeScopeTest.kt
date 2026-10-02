@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertSame

private class TreeScopeLeafStore : Store<TreeScopeLeafStore>() {
    val n by state { 0 }
}

private class TreeScopeParent : Store<TreeScopeParent>() {
    val leaf by store { TreeScopeLeafStore() }
}

private class TreeScopeOverridingParent(
    private val own: CoroutineScope,
) : Store<TreeScopeOverridingParent>() {
    val leaf by store { TreeScopeLeafStore() }
    override val scope: CoroutineScope get() = own
}

/**
 * A `tree` handle's value is hosted on a store whose `scope` is its
 * receiver's, resolved like any store's: a getter override, else
 * `bindToScope`, else the process default.
 */
class TreeScopeTest {
    @Test
    fun theHostAnswersTheReceiversScopeAndFollowsItsBinding() {
        val parent = TreeScopeParent()
        val host = parent.tree.internalHost()
        assertSame(Store.defaultScope, parent.scope)
        assertSame(parent.scope, host.scope, "the host answers the receiver's scope")
        val bound = CoroutineScope(Dispatchers.Unconfined + Job())
        parent.bindToScope(bound)
        assertSame(bound, parent.scope)
        assertSame(bound, host.scope)
    }

    @Test
    fun aSubclassGetterOverrideBeatsTheBinding() {
        val own = CoroutineScope(Dispatchers.Unconfined + Job())
        val parent = TreeScopeOverridingParent(own)
        parent.bindToScope(CoroutineScope(Dispatchers.Unconfined + Job()))
        assertSame(own, parent.scope)
        assertSame(own, parent.tree.internalHost().scope)
    }
}
