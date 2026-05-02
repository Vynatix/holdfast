package com.vynatix.vault.testing

import com.vynatix.vault.Vault
import com.vynatix.vault.testing.internal.HandleRegistry
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

    /**
     * Register [vault] in this scope and return its [VaultHandle]. Calling
     * `track` again with the same instance returns the previously created
     * handle — idempotent by reference identity.
     *
     * The [capture] argument is recorded on the handle but only [Capture.All]
     * has any visible effect in Issue 02; instrumentation lands in Issue 06.
     */
    fun <V : Vault<V>> track(vault: V, capture: Capture = Capture.All): VaultHandle<V> = registry.getOrCreate(vault, capture)

    internal fun tearDown() {
        registry.clear()
    }
}
