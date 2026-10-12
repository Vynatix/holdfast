package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.Path

/** One traced transaction: the merge of its `txn-started` and `txn-ended` events. */
internal data class TraceTxn(
    val id: String,
    val label: String,
    val parent: String?,
    val frame: String?,
    val savepoint: Boolean,
    val ended: Boolean,
    val status: String?,
    val durNs: Long?,
    val states: List<String>,
    val err: String?,
)

/** A parsed trace file. */
internal data class TraceFile(
    val txns: List<TraceTxn>,
    val malformedLines: Int,
    val totalLines: Int,
)

/** Parse JSONL trace lines (`:holdfast-debug`'s format) into transactions. */
internal fun parseTrace(text: String): TraceFile {
    val txns = LinkedHashMap<String, TraceTxn>()
    var malformed = 0
    var total = 0
    for (raw in text.lineSequence().filterNot(String::isBlank)) {
        total++
        val event = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (event == null || !applyEvent(txns, event)) malformed++
    }
    return TraceFile(txns = txns.values.toList(), malformedLines = malformed, totalLines = total)
}

/** Apply one event to [txns]; false when the line is malformed for its kind. */
private fun applyEvent(
    txns: LinkedHashMap<String, TraceTxn>,
    event: JsonObject,
): Boolean {
    val id = event.stringOf("id") ?: return false
    return when (event.stringOf("e")) {
        "txn-started" -> {
            txns[id] = startedTxn(id, event)
            true
        }
        "txn-ended" -> applyEnd(txns, id, event)
        else -> false
    }
}

private fun applyEnd(
    txns: LinkedHashMap<String, TraceTxn>,
    id: String,
    event: JsonObject,
): Boolean {
    val prior = txns[id] ?: return false
    txns[id] =
        prior.copy(
            ended = true,
            status = event.stringOf("status"),
            durNs = event.longOf("dur_ns"),
            states = event.stringsOf("states"),
            err = event.stringOf("err"),
        )
    return true
}

private fun startedTxn(
    id: String,
    event: JsonObject,
): TraceTxn =
    TraceTxn(
        id = id,
        label = event.stringOf("label") ?: "?",
        parent = event.stringOf("parent"),
        frame = event.stringOf("frame"),
        savepoint = event.booleanOf("savepoint") == true,
        ended = false,
        status = null,
        durNs = null,
        states = emptyList(),
        err = null,
    )

private fun JsonObject.stringOf(name: String): String? =
    when (val element = get(name)) {
        null, is JsonNull -> null
        else -> runCatching { element.jsonPrimitive.content }.getOrNull()
    }

private fun JsonObject.booleanOf(name: String): Boolean? =
    when (val element = get(name)) {
        null, is JsonNull -> null
        else -> runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
    }

private fun JsonObject.longOf(name: String): Long? =
    when (val element = get(name)) {
        null, is JsonNull -> null
        else -> runCatching { element.jsonPrimitive.longOrNull }.getOrNull()
    }

private fun JsonObject.stringsOf(name: String): List<String> =
    runCatching {
        get(name)?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }.getOrNull() ?: emptyList()

/** The `trace` command group: render, stats. */
internal class TraceCommand : CliktCommand(name = "trace") {
    override fun help(context: Context): String = "Render and summarize JSONL traces from :holdfast-debug."

    override fun run() {
        // Bare invocation: clikt prints help and exits non-zero.
    }
}

internal class RenderTraceCommand : CliktCommand(name = "render") {
    override fun help(context: Context): String = "Show the transaction tree of a trace file."

    private val file: Path by
        argument(help = "path to a JSONL trace file").path(mustExist = true, canBeDir = false)

    override fun run() {
        echo(renderTrace(parseTrace(readTraceFile(file))))
    }
}

internal class TraceStatsCommand : CliktCommand(name = "stats") {
    override fun help(context: Context): String = "Aggregate a trace file: counts, durations, slowest."

    private val file: Path by
        argument(help = "path to a JSONL trace file").path(mustExist = true, canBeDir = false)

    override fun run() {
        echo(statsTrace(parseTrace(readTraceFile(file))))
    }
}

private fun readTraceFile(path: Path): String =
    try {
        path.toFile().readText()
    } catch (e: IOException) {
        throw CliktError("cannot read trace file $path: ${e.message}", e)
    }
