package com.vynatix.vault.testing

import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.coroutines.suspendAction
import com.vynatix.vault.testing.internal.PendingErrorRegistry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Test-scope handle to a tracked [Vault]. Returned by [VaultTestScope.track]; the
 * registry keeps it alive for the duration of the test so subsequent `track`
 * calls with the same vault instance return the same handle.
 *
 * Every [TransactionResult.Error] returned by [action] or [suspendAction] is
 * recorded as a pending consumption. Calling
 * [shouldBeError][com.vynatix.vault.testing.matcher.shouldBeError],
 * [shouldBeSuccess][com.vynatix.vault.testing.matcher.shouldBeSuccess] or
 * [shouldRollbackWith][com.vynatix.vault.testing.matcher.shouldRollbackWith]
 * clears the mark for that result; any errors left unconsumed when the
 * surrounding [vaultTest] block returns fail the test. Use
 * [consumeAllPendingErrors] as an explicit opt-out when a test deliberately
 * ignores an error.
 */
class VaultHandle<V : Vault<V>> internal constructor(val vault: V, val captureMode: Capture) : SynchronizedObject() {

    private val pendingErrorList: MutableList<TransactionResult.Error> = mutableListOf()

    /**
     * Snapshot of unconsumed [TransactionResult.Error] values produced by this
     * handle. Exposed for the scope-exit guard; stable to iterate (returns a
     * copy taken under the handle's lock).
     */
    internal val pendingErrors: List<TransactionResult.Error>
        get() = synchronized(this) { pendingErrorList.toList() }

    /**
     * Run [block] with the tracked vault as receiver and return its value. The
     * block sees `vault.value` for each [com.vynatix.vault.State] without going
     * through an action — useful for plain read assertions.
     */
    fun <R> read(block: V.() -> R): R = block(vault)

    /**
     * Run [body] inside a blocking [Vault.action] on the tracked vault. Returns
     * the [TransactionResult] verbatim — production-faithful, no transformation
     * or implicit assertion. If the result is an [TransactionResult.Error], it
     * is recorded for the scope-exit unconsumed-error guard; assert on it via a
     * `shouldBe*` matcher (or [consumeAllPendingErrors]) to clear the mark.
     */
    fun <R> action(body: V.() -> R): TransactionResult<R> {
        val result = vault action body
        trackResult(result)
        return result
    }

    /**
     * Run [body] inside a [com.vynatix.vault.coroutines.suspendAction] on the
     * tracked vault. Returns the [TransactionResult] verbatim — production-
     * faithful, no transformation or implicit assertion. If the result is an
     * [TransactionResult.Error], it is recorded for the scope-exit
     * unconsumed-error guard; assert on it via a `shouldBe*` matcher (or
     * [consumeAllPendingErrors]) to clear the mark.
     */
    suspend fun <R> suspendAction(body: suspend V.() -> R): TransactionResult<R> {
        val result = vault.suspendAction(body)
        trackResult(result)
        return result
    }

    /**
     * Discard every pending [TransactionResult.Error] on this handle without
     * asserting on them. Use when a test deliberately wants to skip the
     * scope-exit guard for an error it knows about (e.g. a fixture that
     * intentionally surfaces a failure but is asserted on out-of-band).
     *
     * Prefer the [shouldBeError][com.vynatix.vault.testing.matcher.shouldBeError] /
     * [shouldRollbackWith][com.vynatix.vault.testing.matcher.shouldRollbackWith]
     * matchers when the goal is to inspect the failure.
     */
    fun consumeAllPendingErrors() {
        PendingErrorRegistry.unregisterAll(this)
        synchronized(this) { pendingErrorList.clear() }
    }

    private fun trackResult(result: TransactionResult<*>) {
        if (result is TransactionResult.Error) {
            synchronized(this) { pendingErrorList.add(result) }
            PendingErrorRegistry.register(result, this)
        }
    }

    /**
     * Drop [error] from the pending list. Called by [PendingErrorRegistry]
     * when a matcher consumes the result; identity-based removal so identical
     * structurally-equal Error data classes don't collapse into one.
     */
    internal fun markConsumedInternal(error: TransactionResult.Error) {
        synchronized(this) {
            pendingErrorList.removeAll { it === error }
        }
    }

    /**
     * Drop every pending error. Called from scope tearDown after the
     * unconsumed-error report has been built; ensures the handle releases its
     * collected results so a leaked handle reference does not prolong them.
     */
    internal fun clearPendingErrorsInternal() {
        synchronized(this) { pendingErrorList.clear() }
    }
}
