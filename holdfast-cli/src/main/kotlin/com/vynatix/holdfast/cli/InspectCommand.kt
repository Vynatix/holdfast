package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path

/** `holdfast snapshot inspect FILE` — summarize one decoded snapshot. */
internal class InspectCommand : CliktCommand(name = "inspect") {
    override fun help(context: Context): String = "Decode a snapshot: schema version, states, rendered values."

    private val file: Path by
        argument(help = "path to a file holding one store snapshot body").path(
            mustExist = true,
            canBeDir = false,
            canBeFile = true,
        )

    private val showValues by
        option(
            "--values",
            help = "show the rendered values block (default: shown)",
        ).flag("--no-values", default = true)

    override fun run() {
        val text = readSnapshotFileOrExit("snapshot", file)
        when (val decoded = decodeSnapshotFacts(text)) {
            is DecodeResult.Decoded -> echo(inspectReport(decoded.facts, showValues))
            is DecodeResult.Rejected -> exitWithRejection(decoded)
        }
    }
}

/** The human summary of one snapshot; every value shown went through `render()`. */
internal fun inspectReport(
    facts: SnapshotFacts,
    showValues: Boolean,
): String =
    buildString {
        appendLine("schema version: ${facts.schemaVersion}")
        appendLine("states: ${facts.stateNames.size}")
        val skipped = facts.unencodableStateNames.sorted()
        appendLine(
            if (skipped.isEmpty()) {
                "unencodable (no codec): none"
            } else {
                "unencodable (no codec): ${skipped.joinToString(", ")}"
            },
        )
        if (showValues) {
            appendLine("values:")
            facts.rendered.lineSequence().forEach { line -> appendLine("  $line") }
        }
    }

internal fun readSnapshotFileOrExit(
    label: String,
    path: Path,
): String =
    try {
        readSnapshotFile(label, path)
    } catch (e: SnapshotFileUnreadable) {
        throw CliktError(e.message ?: "cannot read $path", e)
    }

internal fun exitWithRejection(rejected: DecodeResult.Rejected): Nothing =
    throw CliktError("not a decodable store snapshot: ${rejected.message}")
