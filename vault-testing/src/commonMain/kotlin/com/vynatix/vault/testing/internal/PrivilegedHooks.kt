@file:OptIn(VaultInternalApi::class)

package com.vynatix.vault.testing.internal

import com.vynatix.vault.State
import com.vynatix.vault.Transaction
import com.vynatix.vault.Vault
import com.vynatix.vault.VaultInternalApi

/**
 * Centralises every `@VaultInternalApi` access used by the testing module so the
 * unsafe surface lives in one auditable file. Call sites elsewhere should reach
 * for these helpers rather than opt in directly — that way new dependencies on
 * vault internals are visible at PR time.
 *
 * The hooks here are a strict subset of what `:vault-coroutines.SuspendAction`
 * uses; the testing module's recorder needs read-side access plus the active-
 * transaction toggle (to bypass read-your-own-writes when capturing committed
 * values from inside a middleware hook), never the write-side hooks
 * (`internalSetActiveTransaction(txn)`) that suspendAction uses to manufacture
 * a transaction.
 */
internal object PrivilegedHooks {

    /**
     * Read every state currently registered on [vault] mapped to its committed
     * post-`transformer.get` value, bypassing the active transaction's
     * read-your-own-writes overlay.
     *
     * Why we bypass: `MutableState.value` on the owner thread inside an active
     * transaction returns pending writes first (so the body sees its own
     * uncommitted mutations). The recorder needs the COMMITTED view at the
     * boundary between body return and commit apply — that's the `oldValue` of
     * each [com.vynatix.vault.testing.EmissionEvent].
     *
     * How we bypass: temporarily clear the vault's `_activeTransaction` via
     * `internalSetActiveTransaction(null)`, read every state, then restore.
     * The middleware hook holds the vault's `transactionLock` (because we run
     * inside `runBlockingActionUnderLock` -> `runMiddlewareChain`), so no peer
     * action can observe the transient null. Off-owner-thread mutate calls are
     * also blocked on the same lock, so they cannot race with this read.
     *
     * Single-call semantics: this captures the committed view at one instant
     * and returns. The caller MUST keep the hold-lock invariant intact — never
     * call this from outside a middleware hook.
     */
    fun snapshotCommittedStateValues(vault: Vault<*>): Map<State<*>, Any> {
        val active = vault.activeTransaction
        return if (active == null) {
            // No active transaction → state.value already returns the committed
            // view. Cheap path; avoids the toggle.
            buildMap {
                for ((_, state) in vault.properties) {
                    put(state, state.value)
                }
            }
        } else {
            vault.internalSetActiveTransaction(null)
            try {
                buildMap {
                    for ((_, state) in vault.properties) {
                        put(state, state.value)
                    }
                }
            } finally {
                vault.internalSetActiveTransaction(active)
            }
        }
    }

    /**
     * Owner-thread-only read of the transaction's modified-state set. Wraps
     * [Transaction.modifiedStates] without adding any new logic — provided here
     * so the call site in [com.vynatix.vault.testing.internal.Recorder] doesn't
     * need its own `@OptIn`.
     *
     * Throws if invoked off the owner thread; the recorder's hooks always run
     * on the owner thread (the middleware chain is synchronous and on the
     * thread that called [com.vynatix.vault.Vault.action]).
     */
    fun modifiedStates(transaction: Transaction): Set<State<*>> = transaction.modifiedStates
}
