package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.testing.internal.PendingErrorRegistry
import kotlin.reflect.KClass

/**
 * Assert that this result is a [TransactionResult.Success], invoke [block] with
 * the success as receiver for further assertions, and clear any pending-error
 * mark for it. Returns the [TransactionResult.Success] for chaining.
 *
 * Throws [AssertionError] if the result is a [TransactionResult.Error]; the
 * carried exception is included in the failure message.
 */
inline fun <R> TransactionResult<R>.shouldBeSuccess(block: TransactionResult.Success<R>.() -> Unit = {}): TransactionResult.Success<R> {
    if (this is TransactionResult.Error) {
        val type = exception::class.simpleName ?: "Throwable"
        val message = exception.message.orEmpty()
        throw AssertionError("Expected TransactionResult.Success, but got Error($type \"$message\")")
    }
    @Suppress("UNCHECKED_CAST")
    val success = this as TransactionResult.Success<R>
    success.block()
    return success
}

/**
 * Assert that this result is a [TransactionResult.Error] whose carried
 * exception is an instance of [E], invoke [block] with the error as receiver,
 * and clear the handle's pending-error mark for this result. Returns the
 * [TransactionResult.Error] for chaining.
 *
 * Throws [AssertionError] if the result is a [TransactionResult.Success], or if
 * it is an Error but the exception type does not match [E].
 */
inline fun <reified E : Throwable> TransactionResult<*>.shouldBeError(
    block: TransactionResult.Error.() -> Unit = {},
): TransactionResult.Error {
    if (this !is TransactionResult.Error) {
        throw AssertionError("Expected TransactionResult.Error<${E::class.simpleName}>, but got Success: $this")
    }
    if (exception !is E) {
        val actual = exception::class.simpleName ?: "Throwable"
        val expected = E::class.simpleName ?: "Throwable"
        val msg = exception.message.orEmpty()
        throw AssertionError("Expected TransactionResult.Error<$expected>, but exception was $actual: \"$msg\"")
    }
    PendingErrorRegistry.markConsumed(this)
    block()
    return this
}

/**
 * Assert that this result is a [TransactionResult.Error] whose transaction
 * rolled back (status [TransactionStatus.RolledBack]) and whose exception is
 * an instance of [exceptionType]. Clears the pending-error mark on success.
 *
 * The "rolled back" check distinguishes a clean savepoint rollback from a
 * commit-time [TransactionStatus.Failed] outcome — useful for asserting that
 * an exception thrown inside an action chose the rollback path rather than
 * leaving the transaction in the Failed state. (Top-level actions on a store
 * that throw will normally end up in RolledBack via [Transaction.rollback];
 * Failed surfaces only from a commit-time error.)
 */
infix fun TransactionResult<*>.shouldRollbackWith(exceptionType: KClass<out Throwable>) {
    val failure = checkRollback(this, exceptionType)
    if (failure != null) throw failure
    PendingErrorRegistry.markConsumed(this as TransactionResult.Error)
}

/**
 * Returns the [AssertionError] that would describe why [result] is not a
 * rollback with [exceptionType], or `null` if everything matches. Pulled out
 * so [shouldRollbackWith] has only one `throw` site.
 */
private fun checkRollback(
    result: TransactionResult<*>,
    exceptionType: KClass<out Throwable>,
): AssertionError? =
    when {
        result !is TransactionResult.Error ->
            AssertionError(
                "Expected TransactionResult.Error rolled back with ${exceptionType.simpleName}, but got Success: $result",
            )
        result.transaction.status != TransactionStatus.RolledBack -> {
            val type = result.exception::class.simpleName ?: "Throwable"
            val message = result.exception.message.orEmpty()
            AssertionError(
                "Expected transaction.status == RolledBack, but was ${result.transaction.status} " +
                    "(error was $type \"$message\")",
            )
        }
        !exceptionType.isInstance(result.exception) -> {
            val actual = result.exception::class.simpleName ?: "Throwable"
            val expected = exceptionType.simpleName ?: "Throwable"
            val message = result.exception.message.orEmpty()
            AssertionError(
                "Expected rollback caused by $expected, but exception was $actual: \"$message\"",
            )
        }
        else -> null
    }
