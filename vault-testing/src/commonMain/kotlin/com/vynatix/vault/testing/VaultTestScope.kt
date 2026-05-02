package com.vynatix.vault.testing

import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.testing.internal.BarrierRegistry
import com.vynatix.vault.testing.internal.HandleRegistry
import com.vynatix.vault.testing.internal.PendingErrorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/**
 * Test scope produced by [vaultTest]. Wraps the underlying [TestScope] (so the
 * body has full access to the coroutine-test machinery — virtual time, the
 * background scope, the scheduler) and adds a per-test [VaultHandle] registry.
 *
 * Implementation note: `TestScope` is a sealed interface and cannot be
 * implemented directly outside its module, so the wrapper delegates
 * [CoroutineScope] (its non-sealed supertype) and forwards [testScheduler] and
 * [backgroundScope] manually. The full [TestScope] is also exposed as
 * [testScope] so extension helpers like `runCurrent()`, `advanceUntilIdle()`,
 * `advanceTimeBy()`, and `currentTime` can be invoked against it directly.
 *
 * Constructed exclusively by [vaultTest]; never instantiated directly by user
 * code.
 */
class VaultTestScope internal constructor(val testScope: TestScope) : CoroutineScope by testScope {

    /** The virtual-time scheduler driving this test. */
    val testScheduler: TestCoroutineScheduler get() = testScope.testScheduler

    /** Background scope whose work is not awaited at test end. */
    val backgroundScope: CoroutineScope get() = testScope.backgroundScope

    private val registry = HandleRegistry()
    private val barriers = BarrierRegistry()

    /**
     * Register [vault] in this scope and return its [VaultHandle]. Calling
     * `track` again with the same instance returns the previously created
     * handle — idempotent by reference identity.
     *
     * The [capture] argument is recorded on the handle but only [Capture.All]
     * has any visible effect in Issue 02; instrumentation lands in Issue 06.
     */
    fun <V : Vault<V>> track(vault: V, capture: Capture = Capture.All): VaultHandle<V> = registry.getOrCreate(vault, capture)

    internal fun barrierRegistry(): BarrierRegistry = barriers

    /**
     * Tear down this scope. Always cancels outstanding barriers, removes every
     * tracked handle's entries from the global [PendingErrorRegistry], and
     * clears the handle registry. When [bodyAlreadyFailed] is `false`, also
     * aggregates any unconsumed [TransactionResult.Error] values across all
     * handles and throws an [AssertionError] listing them —
     * forcing tests to actively assert on (or explicitly discard) every error
     * they observe. When the body already threw, the original failure
     * propagates and the unconsumed-error check is suppressed so the user sees
     * the root-cause exception rather than a teardown-time message.
     */
    internal fun tearDown(bodyAlreadyFailed: Boolean) {
        barriers.cancelAll()

        val handles = registry.allHandles()
        val unconsumed: List<Pair<VaultHandle<*>, TransactionResult.Error>> =
            handles.flatMap { handle -> handle.pendingErrors.map { handle to it } }

        for (handle in handles) {
            PendingErrorRegistry.unregisterAll(handle)
            handle.clearPendingErrorsInternal()
        }
        registry.clear()

        if (!bodyAlreadyFailed && unconsumed.isNotEmpty()) {
            val msg = buildString {
                appendLine("vaultTest body finished with ${unconsumed.size} unconsumed TransactionResult.Error value(s):")
                unconsumed.forEachIndexed { index, (handle, err) ->
                    val type = err.exception::class.simpleName ?: "Throwable"
                    val message = err.exception.message.orEmpty()
                    val handleTag = handleLabel(handle)
                    val txnId = err.transaction.id
                    appendLine(" - [#${index + 1}] handle=$handleTag $type \"$message\" (txn '$txnId')")
                }
                appendLine("Call .shouldBeError / .shouldBeSuccess / .shouldRollbackWith on each,")
                append("or use handle.consumeAllPendingErrors() to opt out.")
            }
            throw AssertionError(msg)
        }
    }

    private fun handleLabel(handle: VaultHandle<*>): String {
        val cls = handle.vault::class.simpleName ?: "Vault"
        // Identity tag so two handles to the same vault class are distinguishable.
        return "$cls@${handle.hashCode().toString(HEX_RADIX)}"
    }

    private companion object {
        private const val HEX_RADIX = 16
    }
}
