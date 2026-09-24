package com.vynatix.holdfast.hallmark.coroutines

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.HallmarkException
import com.vynatix.hallmark.HallmarkResult
import com.vynatix.hallmark.coroutines.SuspendValidator
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.hallmark.withheld
import com.vynatix.holdfast.tags

/**
 * Run [suspendValidator] against [primitive] (a suspending operation, possibly
 * doing I/O), then atomically mutate [state] with the resulting [Boxed] inside
 * a `suspendAction { }`. Throws [com.vynatix.hallmark.HallmarkException]
 * inside the action on validation failure — the action returns
 * [TransactionResult.Error] and every other state write in the transaction
 * rolls back atomically.
 *
 * ```kotlin
 * suspend fun adoptUsername(name: String): TransactionResult<Unit> =
 *     store.suspendValidateAndMutate(store.username, UsernameValidator, name)
 * ```
 *
 * This is the suspend-side counterpart to the sync pattern
 * `store action { state mutate (validator of primitive) }`. The difference is
 * that the validation step itself may suspend (e.g. unique-name lookup).
 *
 * For a `StateTag.Secret` state (declared with `:holdfast-hallmark`'s
 * experimental `boxed(validator, codec, tags)` or
 * `boxedHandle(validator, codec, tags)`), the exception withholds the
 * rejected value, as those factories' own paths do: each violation keeps its
 * code, path and rule, reads "`<code>` rejected the value (withheld: a Secret
 * state)" and carries no arguments. Any other state gets hallmark's messages,
 * which may quote the value.
 */
@OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)
suspend fun <V : Store<V>, P : Any, O : Boxed<P>> V.suspendValidateAndMutate(
    state: State<O>,
    suspendValidator: SuspendValidator<P, O>,
    primitive: P,
): TransactionResult<Unit> =
    suspendAction {
        val boxed =
            if (StateTag.Secret !in state.tags) {
                suspendValidator.of(primitive)
            } else {
                when (val result = suspendValidator.validate(primitive)) {
                    is HallmarkResult.Success -> result.value
                    is HallmarkResult.Failure -> throw HallmarkException(result.violations.withheld())
                }
            }
        state mutate boxed
    }
