package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.derived
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hosts a base state and the derived chain built on it, one per path. */
private class ChainVault : Store<ChainVault>() {
    val base by state { 1 }
}

/** Hosts a `suspendDerived` whose source commits through a sync atomic frame. */
private class PathsVault(
    scope: CoroutineScope,
) : Store<PathsVault>() {
    val n by state { 0 }

    init {
        bindToScope(scope)
    }
}

/**
 * Issue 002 residue, audit rows 15 and 17 (the derived matrix's missing
 * named tests): a derived-of-derived chain must cascade all the way through
 * whichever entry commits the base source, and a `suspendDerived` must fire
 * when its source commits through a sync `atomic` frame.
 */
class DerivedRecomputePathsTest {
    private val disposables = mutableListOf<Disposable>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest fun cleanup() {
        disposables.forEach { runCatching { it.dispose() } }
        scopes.forEach { runCatching { it.cancel() } }
    }

    /**
     * Audit row 15: a derived-of-derived chain (base → mid → top) recomputes
     * correctly when the base commits via each of the four entry kinds. The
     * first link's recompute runs in the entry's post-commit drain (for a
     * frame, at its unwind; for suspendAtomic, deferred to the settle of the
     * outermost entry, which runs before the call returns), its commit queues
     * the second link, and the drain's nested-pass hand-off
     * (`PostCommitQueue.drain`, issue #37) runs that too — so the whole
     * cascade is visible once the entry returns.
     */
    @Test
    fun chainedDerivedCascadesAcrossAllFourPaths() =
        runBlocking {
            fun cascade(
                path: String,
                commit: suspend (ChainVault) -> Unit,
            ) {
                val v = ChainVault()
                var midComputes = 0
                var topComputes = 0
                val (mid, midDisposable) =
                    v.derived(v.base) {
                        midComputes++
                        base.value + 1
                    }
                val (top, topDisposable) =
                    v.derived(mid) {
                        topComputes++
                        mid.value * 10
                    }
                disposables += midDisposable
                disposables += topDisposable
                assertEquals(1, midComputes, "$path: one initial mid compute")
                assertEquals(2, mid.value, "$path: the initial mid value")
                assertEquals(1, topComputes, "$path: one initial top compute")
                assertEquals(20, top.value, "$path: the initial top value")

                runBlocking { commit(v) }

                assertEquals(3, mid.value, "$path: the first link recomputed")
                assertEquals(30, top.value, "$path: the cascade reached the second link")
                assertEquals(2, midComputes, "$path: exactly one mid recompute")
                assertEquals(2, topComputes, "$path: exactly one top recompute")
            }
            cascade("action") { v -> v action { base mutate 2 } }
            cascade("atomic") { v -> atomic(v) { v.action { base mutate 2 } } }
            cascade("suspendAction") { v -> v.suspendAction { base mutate 2 } }
            cascade("suspendAtomic") { v -> suspendAtomic(v) { v { base mutate 2 } } }
        }

    /**
     * Audit row 17: a `suspendDerived` fires when its source commits via a
     * sync `atomic` frame — the frame drains its participants' post-commit
     * queues once the whole frame has unwound (`Atomic.kt`), which runs the
     * queued launch of the suspending recompute on `store.scope`. The
     * recompute itself is async, so the test awaits convergence.
     */
    @Test
    fun suspendDerivedFiresWhenSourceCommitsViaAtomic() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob()).also { scopes += it }
            val v = PathsVault(scope)
            val (derived, disposable) =
                v.suspendDerived(v.n) {
                    n.value + 1
                }
            disposables += disposable
            assertEquals(1, derived.value, "the seeded initial value")

            atomic(v) { v.action { n mutate 41 } }

            withTimeout(2_000) {
                while (derived.value != 42) delay(10)
            }
            assertEquals(42, derived.value, "the atomic frame's drain must launch the suspending recompute")
        }
}
