package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.debug.DebugTraceMiddleware
import com.vynatix.holdfast.debug.TraceSink
import com.vynatix.holdfast.snapshot
import java.io.IOException
import java.nio.file.Path
import javax.script.ScriptEngineManager

/**
 * The repro-script sandbox, driven from `holdfast run repro.kts`:
 *
 * ```kotlin
 * import com.vynatix.holdfast.cli.DebugSandbox
 *
 * class Counter : Store<Counter>() { val n by state { 0 } }
 *
 * val counter = Counter()
 * DebugSandbox.trace(counter) {
 *     counter action { n mutate 1 }
 * }
 * ```
 *
 * [trace] installs a [DebugTraceMiddleware] on the store and runs the block;
 * after the script finishes, `holdfast run` prints every traced store's
 * transaction tree and a final (redaction-aware) snapshot render — the
 * bug-report attachment format.
 *
 * Public by necessity: the script engine compiles the script as a separate
 * compilation, so `internal` would be invisible to it. This class lives in
 * the internal, never-published CLI module.
 */
object DebugSandbox {
    /** One traced store: its label, captured JSONL lines, and a lazy snapshot render. */
    class Traced internal constructor(
        val label: String,
        val store: Store<*>,
        val lines: List<String>,
        internal val snapshotRender: () -> String,
    )

    private val traced = mutableListOf<Traced>()

    /** Drop all traced stores (used between runs and tests). */
    fun reset() {
        synchronized(traced) { traced.clear() }
    }

    /**
     * Trace [store]'s transactions while [block] runs (the middleware stays
     * installed after, so post-block transactions are captured too). Returns
     * the [Traced] record the CLI renders at script end.
     */
    @OptIn(ExperimentalStoreApi::class)
    fun <V : Store<V>> trace(
        store: V,
        label: String? = null,
        block: (() -> Unit)? = null,
    ): Traced {
        val name = label ?: store::class.simpleName ?: "store"
        val lines = mutableListOf<String>()
        store.middlewares(
            DebugTraceMiddleware(
                name,
                TraceSink { line ->
                    synchronized(lines) { lines += line }
                },
            ),
        )
        block?.invoke()
        val record =
            Traced(
                label = name,
                store = store,
                lines = synchronized(lines) { lines.toList() },
                snapshotRender = { store.snapshot().render() },
            )
        synchronized(traced) {
            traced += record
            return record
        }
    }

    /** Rendered reports for every traced store: trace tree + snapshot render. */
    fun reports(): List<String> =
        synchronized(traced) {
            traced.map { record ->
                buildString {
                    appendLine("=== trace: ${record.label} ===")
                    append(renderTrace(parseTrace(record.lines.joinToString("\n"))))
                    appendLine("=== snapshot: ${record.label} ===")
                    appendLine(record.snapshotRender())
                }
            }
        }
}

/** `holdfast run repro.kts` — compile and run a repro script with tracing. */
internal class RunCommand : CliktCommand(name = "run") {
    override fun help(context: Context): String = "Compile and run a Kotlin repro script with trace rendering."

    private val script: Path by
        argument(help = "path to a Kotlin script (.kts)").path(mustExist = true, canBeDir = false)

    override fun run() {
        val startedAt = System.nanoTime()
        val source = readScript()
        val engine = engineOrFail()
        DebugSandbox.reset()
        evalScript(engine, source)
        echo("script ran in ${formatNanos(System.nanoTime() - startedAt)}")
        DebugSandbox.reports().forEach(::echo)
    }

    private fun readScript(): String =
        try {
            script.toFile().readText()
        } catch (e: IOException) {
            throw CliktError("cannot read script $script: ${e.message}", e)
        }

    private fun engineOrFail(): javax.script.ScriptEngine {
        // The default JSR-223 engine rides the legacy K1 REPL; the experimental
        // engine uses the current frontend (correct state() overload resolution).
        System.setProperty("kotlin.jsr223.experimental.enabled", "true")
        return ScriptEngineManager().getEngineByExtension("kts")
            ?: throw CliktError("no Kotlin script engine on the classpath (kotlin-scripting-jsr223)")
    }

    // A script can throw literally anything; every failure must surface as a
    // clean CLI error, never a stack trace.
    @Suppress("TooGenericExceptionCaught")
    private fun evalScript(
        engine: javax.script.ScriptEngine,
        source: String,
    ) {
        try {
            engine.eval(source)
        } catch (e: Exception) {
            throw CliktError("script failed: ${e::class.simpleName}: ${e.message}", e)
        }
    }
}
