@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private class ScLeafStore : Store<ScLeafStore>() {
    val n by state { 0 }
}

private class ScRoot : Root("sc") {
    val leaf by branch(ScLeafStore())
}

private class ScOverridingRoot(
    private val own: CoroutineScope,
) : Root("sco") {
    val leaf by branch(ScLeafStore())
    override val scope: CoroutineScope get() = own
}

/** `Root.scope` resolves like a store's: a getter override, else `bindToScope`, else the process default. */
class RootScopeTest {
    @Test
    fun theDefaultIsTheProcessScopeAndBindToScopeReplacesIt() {
        val root = ScRoot()
        assertSame(Store.defaultScope, root.scope)
        assertSame(root.scope, root.internalHost().scope, "the host answers the root's scope")
        val bound = CoroutineScope(Dispatchers.Unconfined + Job())
        root.bindToScope(bound)
        assertSame(bound, root.scope)
        assertSame(bound, root.internalHost().scope)
        root.dispose()
        assertFailsWith<IllegalStateException> { root.bindToScope(bound) }
    }

    @Test
    fun aSubclassGetterOverrideBeatsTheBinding() {
        val own = CoroutineScope(Dispatchers.Unconfined + Job())
        val root = ScOverridingRoot(own)
        root.bindToScope(CoroutineScope(Dispatchers.Unconfined + Job()))
        assertSame(own, root.scope)
        assertSame(own, root.internalHost().scope)
    }
}
