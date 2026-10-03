@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.compose

import androidx.compose.runtime.Composable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test

private class ComposeBindingsVault : Store<ComposeBindingsVault>() {
    val n by state { 0 }
    val s by state { "init" }
}

private class ComposeBindingsParent : Store<ComposeBindingsParent>() {
    val leaf by store { ComposeBindingsVault() }
}

/**
 * Compose-runtime testing in Kotlin Multiplatform requires a separate test
 * harness (e.g. `@Composable` test rules) that this lightweight module
 * intentionally does not pull in. Instead we use compile-only smoke tests:
 * these prove the API surface compiles and resolves correctly across the
 * KMP targets, which is the contract the module owes its consumers.
 *
 * Recomposition itself is verified on the JVM only, by `jvmTest`'s
 * `ComposeRecompositionTest`, which drives a headless `Recomposer` (no UI
 * toolkit) and counts recompositions; app-level tests in a consuming module
 * cover the rest.
 */
class ComposeBindingsCompileTest {
    @Test
    fun apiSurfaceCompiles() {
        // The presence of the Composable references below is the assertion:
        // if the API surface ever drifts, this file fails to compile.
        @Composable
        fun render() {
            val v = ComposeBindingsVault()
            val n = v.collectAsState(v.n)
            val s = v.collectAsState(v.s)
            val tree = ComposeBindingsParent().tree.collectAsState()
            // Avoid unused warnings.
            n.value
            s.value
            tree.value
            rememberDisposable {
                com.vynatix.holdfast.Disposable { /* no-op */ }
            }
        }
        // Reference render to keep the closure in scope.
        @Suppress("UNUSED_EXPRESSION")
        ::render
    }
}
