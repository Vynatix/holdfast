@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.SettleScope
import com.vynatix.holdfast.SettleScopes

/**
 * iOS: `ThreadContextElement` is unavailable, so propagate via the delegating
 * [SlotBracketingInterceptor], starting [block] undispatched. See
 * [withSettleScope]'s expect KDoc for the nested-`withContext(dispatcher)` gap.
 */
internal actual suspend fun <T> withSettleScope(
    scope: SettleScope,
    block: suspend () -> T,
): T = withSlotIntercepted(scope, SettleScopes::install, SettleAmbientContext(scope), block)
