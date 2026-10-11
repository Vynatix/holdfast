@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.SettleScope
import com.vynatix.holdfast.SettleScopes
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * JVM/Android: a [ThreadContextElement] installs the scope on every resumption
 * and survives nested `withContext(otherDispatcher)` sections. The dispatcher
 * does not change, so `withContext` starts [block] undispatched on this
 * thread. See [withSettleScope].
 */
internal actual suspend fun <T> withSettleScope(
    scope: SettleScope,
    block: suspend () -> T,
): T = withContext(SettleAmbientContext(scope) + SettleScopeElement(scope)) { block() }

private class SettleScopeElement(
    private val scope: SettleScope,
) : ThreadContextElement<SettleScope?> {
    override val key: CoroutineContext.Key<SettleScopeElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): SettleScope? = SettleScopes.install(scope)

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: SettleScope?,
    ) {
        SettleScopes.install(oldState)
    }

    companion object Key : CoroutineContext.Key<SettleScopeElement>
}
