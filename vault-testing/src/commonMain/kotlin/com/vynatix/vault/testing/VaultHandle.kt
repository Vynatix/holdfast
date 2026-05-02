package com.vynatix.vault.testing

import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.coroutines.suspendAction

/**
 * Test-scope handle to a tracked [Vault]. Returned by [VaultTestScope.track]; the
 * registry keeps it alive for the duration of the test so subsequent `track`
 * calls with the same vault instance return the same handle.
 *
 * In Issue 03 the surface is [vault], [captureMode], [read], [action], and
 * [suspendAction] — the rest (timeline views, eventually/barrier helpers, the
 * pending-error guard) lands in later issues.
 */
class VaultHandle<V : Vault<V>> internal constructor(val vault: V, val captureMode: Capture) {
    /**
     * Run [block] with the tracked vault as receiver and return its value. The
     * block sees `vault.value` for each [com.vynatix.vault.State] without going
     * through an action — useful for plain read assertions.
     */
    fun <R> read(block: V.() -> R): R = block(vault)

    /**
     * Run [body] inside a blocking [Vault.action] on the tracked vault. Returns
     * the [TransactionResult] verbatim — production-faithful, no transformation
     * or implicit assertion. Test code is responsible for inspecting the result.
     */
    fun <R> action(body: V.() -> R): TransactionResult<R> = vault action body

    /**
     * Run [body] inside a [com.vynatix.vault.coroutines.suspendAction] on the
     * tracked vault. Returns the [TransactionResult] verbatim — production-
     * faithful, no transformation or implicit assertion.
     */
    suspend fun <R> suspendAction(body: suspend V.() -> R): TransactionResult<R> = vault.suspendAction(body)
}
