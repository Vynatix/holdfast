package com.vynatix.holdfast.compose

import androidx.compose.runtime.Composable
import com.vynatix.holdfast.Store
import kotlin.test.Test

private class ComposeBindingsVault : Store<ComposeBindingsVault>() {
    val n by state { 0 }
    val s by state { "init" }
}

/**
 * Compose-runtime testing in Kotlin Multiplatform requires a separate test
 * harness (e.g. `@Composable` test rules) that this lightweight module
 * intentionally does not pull in. Instead we use compile-only smoke tests:
 * these prove the API surface compiles and resolves correctly across the
 * KMP targets, which is the contract the module owes its consumers.
 *
 * End-to-end recomposition behavior is verified by app-level tests in the
 * consuming module (`:shared`).
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
            // Avoid unused warnings.
            n.value
            s.value
            rememberDisposable {
                com.vynatix.holdfast.Disposable { /* no-op */ }
            }
        }
        // Reference render to keep the closure in scope.
        @Suppress("UNUSED_EXPRESSION")
        ::render
    }
}
