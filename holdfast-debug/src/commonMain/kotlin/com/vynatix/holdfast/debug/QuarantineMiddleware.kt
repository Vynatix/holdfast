package com.vynatix.holdfast.debug

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import kotlin.concurrent.Volatile

/**
 * Thrown into every action of a quarantined store. Surfaces as
 * [com.vynatix.holdfast.TransactionResult.Error]; the body never runs.
 */
@ExperimentalStoreApi
class StoreQuarantinedException(
    storeName: String,
) : IllegalStateException(
        "Store '$storeName' is quarantined by holdfast-debug: every action rolls back " +
            "until `quarantine $storeName off`.",
    )

/**
 * Kill switch for one store. While [enabled], `onTransactionStarted` throws
 * [StoreQuarantinedException], so every action (and the store's participation
 * in any `atomic` frame) rolls back before its body runs and no observer or
 * bridge publish fires. The rest of the app keeps running, and the process —
 * with all its evidence — stays alive.
 *
 * Installed by [StoreRegistry.register] as the *innermost* middleware so the
 * journal (outer) still records each rejected attempt.
 */
@ExperimentalStoreApi
class QuarantineMiddleware<V : Store<V>> internal constructor(
    private val storeName: String,
) : Middleware<V>() {
    @Volatile
    var enabled: Boolean = false

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        if (enabled) throw StoreQuarantinedException(storeName)
    }
}
