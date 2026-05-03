package com.vynatix.vault.validation.coroutines

import com.vynatix.validation.Boxed
import com.vynatix.validation.coroutines.SuspendValidator
import com.vynatix.vault.State
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.coroutines.suspendAction

/**
 * Run [suspendValidator] against [primitive] (a suspending operation, possibly
 * doing I/O), then atomically mutate [state] with the resulting [Boxed] inside
 * a `suspendAction { }`. Throws [com.vynatix.validation.ValidationException]
 * inside the action on validation failure — the action returns
 * [TransactionResult.Error] and every other state write in the transaction
 * rolls back atomically.
 *
 * ```kotlin
 * suspend fun adoptUsername(name: String): TransactionResult<Unit> =
 *     vault.suspendValidateAndMutate(vault.username, UsernameValidator, name)
 * ```
 *
 * This is the suspend-side counterpart to the sync pattern
 * `vault action { state mutate (validator of primitive) }`. The difference is
 * that the validation step itself may suspend (e.g. unique-name lookup).
 */
suspend fun <V : Vault<V>, P : Any, O : Boxed<P>> V.suspendValidateAndMutate(
    state: State<O>,
    suspendValidator: SuspendValidator<P, O>,
    primitive: P,
): TransactionResult<Unit> = suspendAction {
    state mutate suspendValidator.of(primitive)
}
