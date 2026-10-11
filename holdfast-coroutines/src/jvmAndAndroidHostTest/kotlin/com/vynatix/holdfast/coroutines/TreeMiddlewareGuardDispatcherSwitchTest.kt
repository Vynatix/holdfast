@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class GdsLeafStore : Store<GdsLeafStore>() {
    val n by state { 0 }
}

private class GdsParent : Store<GdsParent>() {
    val a by store { GdsLeafStore() }
    val b by store { GdsLeafStore() }
}

private class GdsTrace : TreeMiddleware() {
    val events = mutableListOf<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
    }
}

/**
 * `tree.middlewares` after a dispatcher switch inside a `suspendAction` body
 * is refused on JVM/Android only: there the entry's settle scope — the
 * guard's probe — rides a `ThreadContextElement` that survives the nested
 * `withContext(Dispatchers.Default)` (`SettleAmbientContext.jvm.kt`). On iOS
 * (and wasmJs) it rides `SlotBracketingInterceptor`, which that nested
 * `withContext` replaces: the section sees no scope, so the guard does not
 * refuse there (`SettleScopeInterceptedTest` asserts that null for the same
 * carrier). Hence this test lives in `jvmAndAndroidHostTest`, beside
 * `SuspendSettleTest`, not in the common `TreeMiddlewareGuardSuspendTest`.
 */
class TreeMiddlewareGuardDispatcherSwitchTest {
    @Test
    fun middlewaresAfterADispatcherSwitchInsideTheBodyIsRefused() =
        runBlocking {
            val parent = GdsParent()
            val tree = parent.tree
            val a = parent.a
            val trace = GdsTrace()
            var refused: Throwable? = null
            a
                .suspendAction {
                    withContext(Dispatchers.Default) {
                        refused = runCatching { tree.middlewares(trace) }.exceptionOrNull()
                    }
                }.getOrThrow()
            val failure = assertIs<IllegalStateException>(refused, "the body's entry is carried across dispatch")
            assertContains(failure.message!!, "member 'a' is held by a suspendAction or suspendAtomic body")
            a action { }
            assertEquals(emptyList<String>(), trace.events, "nothing was installed")
        }
}
