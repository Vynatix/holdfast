package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.pair
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path

/** One line of a render() line-diff: unchanged, removed (only in A), or added (only in B). */
internal sealed interface LineChange {
    data class Unchanged(
        val text: String,
    ) : LineChange

    data class Removed(
        val text: String,
    ) : LineChange

    data class Added(
        val text: String,
    ) : LineChange
}

/** What two decoded snapshots share and how they differ. */
internal data class SnapshotDiff(
    val a: SnapshotFacts,
    val b: SnapshotFacts,
    val statesAdded: List<String>,
    val statesRemoved: List<String>,
    val equalByValue: Boolean,
    val valueChanges: List<LineChange>,
)

internal fun diffSnapshots(
    a: SnapshotFacts,
    b: SnapshotFacts,
    equalByValue: Boolean,
): SnapshotDiff =
    SnapshotDiff(
        a = a,
        b = b,
        statesAdded = (b.stateNames - a.stateNames).sorted(),
        statesRemoved = (a.stateNames - b.stateNames).sorted(),
        equalByValue = equalByValue,
        valueChanges = lineDiff(a.rendered.lineSequence().toList(), b.rendered.lineSequence().toList()),
    )

/**
 * Classic LCS line diff over the two renderings. Renderings are small (one line
 * per state plus family entries), so the quadratic table is fine here.
 */
internal fun lineDiff(
    a: List<String>,
    b: List<String>,
): List<LineChange> {
    // lengths[i][j] = length of the longest common subsequence of a[i..] and b[j..].
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lengths[i][j] =
                if (a[i] == b[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
        }
    }
    val changes = mutableListOf<LineChange>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> {
                changes.add(LineChange.Unchanged(a[i]))
                i++
                j++
            }
            lengths[i + 1][j] >= lengths[i][j + 1] -> {
                changes.add(LineChange.Removed(a[i]))
                i++
            }
            else -> {
                changes.add(LineChange.Added(b[j]))
                j++
            }
        }
    }
    while (i < a.size) {
        changes.add(LineChange.Removed(a[i]))
        i++
    }
    while (j < b.size) {
        changes.add(LineChange.Added(b[j]))
        j++
    }
    return changes
}

/** `holdfast snapshot diff A B` — schema drift, name changes, and value changes. */
internal class DiffCommand : CliktCommand(name = "diff") {
    override fun help(context: Context): String = "Diff two snapshots: schema versions, state names, rendered values."

    private val files: Pair<Path, Path> by
        argument(help = "the two snapshot files to compare (A then B)")
            .path(mustExist = true, canBeDir = false, canBeFile = true)
            .pair()

    override fun run() {
        val (pathA, pathB) = files
        val textA = readSnapshotFileOrExit("snapshot A", pathA)
        val textB = readSnapshotFileOrExit("snapshot B", pathB)
        val a = requireDecoded("A", textA)
        val b = requireDecoded("B", textB)
        // Canonical forms compare by value: the writer emits equal text for equal bodies,
        // so this is decoded-equality without needing app codecs (secrets are null either way).
        val equalByValue = canonicalForm(textA) == canonicalForm(textB)
        echo(diffReport(diffSnapshots(a, b, equalByValue)))
    }

    private fun requireDecoded(
        label: String,
        text: String,
    ): SnapshotFacts =
        when (val decoded = decodeSnapshotFacts(text)) {
            is DecodeResult.Decoded -> decoded.facts
            is DecodeResult.Rejected ->
                throw CliktError("snapshot $label is not decodable: ${decoded.message}")
        }
}

internal fun diffReport(diff: SnapshotDiff): String =
    buildString {
        val schemaDrift = diff.a.schemaVersion != diff.b.schemaVersion
        if (schemaDrift) {
            appendLine("schema version: ${diff.a.schemaVersion} → ${diff.b.schemaVersion}")
        }
        if (diff.statesAdded.isNotEmpty()) appendLine("states added: ${diff.statesAdded.joinToString(", ")}")
        if (diff.statesRemoved.isNotEmpty()) appendLine("states removed: ${diff.statesRemoved.joinToString(", ")}")
        if (!schemaDrift && diff.statesAdded.isEmpty() && diff.statesRemoved.isEmpty()) {
            appendLine("state names: identical (${diff.a.stateNames.size})")
        }
        appendLine(if (diff.equalByValue) "decoded values: equal" else "decoded values: differ")
        if (!diff.equalByValue) {
            appendLine("values (− A, + B):")
            diff.valueChanges
                .filterIsInstance<LineChange.Removed>()
                .forEach { appendLine("  − ${it.text}") }
            diff.valueChanges
                .filterIsInstance<LineChange.Added>()
                .forEach { appendLine("  + ${it.text}") }
        }
    }
