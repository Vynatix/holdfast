@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.SettleScope
import com.vynatix.holdfast.SettleScopes

/**
 * wasmJs: `ThreadContextElement` is unavailable, so propagate via the
 * delegating [SlotBracketingInterceptor], starting [block] undispatched. The
 * bracketing keeps the single-thread global slot scoped to this entry's
 * resumptions, so interleaved other coroutines never see its scope. See
 * [withSettleScope]'s expect KDoc for the nested-`withContext(dispatcher)` gap.
 */
internal actual suspend fun <T> withSettleScope(
    scope: SettleScope,
    block: suspend () -> T,
): T = withSlotIntercepted(scope, SettleScopes::install, SettleAmbientContext(scope), block)
