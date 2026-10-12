@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.cli

import com.vynatix.holdfast.ExperimentalStoreApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DebugSandboxTest {
    @Test
    fun traceRendersTreeAndSnapshotWithoutLeakingSecrets() {
        DebugSandbox.reset()
        val store = FixtureStore()
        DebugSandbox.trace(store, "Fix") {
            store action {
                title mutate "Hi"
                visits mutate 1
                apiKey mutate "hunter2"
            }
        }
        val reports = DebugSandbox.reports()
        assertEquals(expected = 1, actual = reports.size)
        val report = reports[0]
        assertTrue("=== trace: Fix ===" in report, report)
        assertTrue("✓" in report, report)
        assertTrue("apiKey" in report, report)
        assertTrue("=== snapshot: Fix ===" in report, report)
        assertTrue("title = Hi" in report, report)
        assertTrue("hunter2" !in report, "a Secret value must never reach the report: $report")
    }

    @Test
    fun resetClearsTracedStores() {
        DebugSandbox.reset()
        DebugSandbox.trace(FixtureStore(), "A")
        DebugSandbox.reset()
        assertEquals(expected = 0, actual = DebugSandbox.reports().size)
    }
}

class RunScriptTest {
    @Test
    fun scriptEngineRunsAReproThroughTheSandbox() {
        DebugSandbox.reset()
        // The default JSR-223 engine rides the legacy K1 REPL, where holdfast's
        // two state() overloads are ambiguous; the experimental engine uses the
        // current frontend and resolves them like any normal compilation.
        System.setProperty("kotlin.jsr223.experimental.enabled", "true")
        val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")
        assertNotNull(engine, "kotlin-scripting-jsr223 must be on the test classpath")
        engine.eval(
            """
            import com.vynatix.holdfast.ExperimentalStoreApi
            import com.vynatix.holdfast.Store
            import com.vynatix.holdfast.cli.DebugSandbox

            @OptIn(ExperimentalStoreApi::class)
            class S : Store<S>() { val n by state(codec = null, initialize = { 0 }) }

            val s = S()
            DebugSandbox.trace(s) {
                s action { n mutate 1 }
            }
            """.trimIndent(),
        )
        val reports = DebugSandbox.reports()
        assertEquals(expected = 1, actual = reports.size)
        assertTrue("✓" in reports[0], reports[0])
        assertTrue("=== snapshot: S ===" in reports[0], reports[0])
    }
}
