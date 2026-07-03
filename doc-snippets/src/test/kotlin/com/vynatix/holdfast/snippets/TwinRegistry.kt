package com.vynatix.holdfast.snippets

import java.io.File

/** A doc block reproduced verbatim inside a compilable twin source file. */
data class Twin(
    /** `<doc path>#<block index>`, e.g. `holdfast/GUIDE.md#23`. */
    val key: String,
    /** The twin source file, for failure messages. */
    val file: File,
    /** The embedded code between the markers, still at scaffold indentation. */
    val code: String,
)

/**
 * Scans the twin sources for marker-delimited regions. A region looks like:
 *
 * ```
 * <marker-prefix> holdfast/GUIDE.md#23
 *     ...embedded block, verbatim...
 * <end-marker>
 * ```
 *
 * where the markers are line comments (see [startMarker] / [endMarkerText];
 * spelled via concatenation here so this file never matches itself).
 */
object TwinRegistry {
    private val startMarker = Regex("^// " + "DOC-SNIPPET (\\S+#\\d+)$")
    private val endMarkerText = "// " + "DOC-SNIPPET-END"

    fun scan(): List<Twin> {
        val twinRoot = File(SnippetExtraction.repoRoot, "doc-snippets/src/test/kotlin")
        check(twinRoot.isDirectory) { "Twin source root not found: $twinRoot" }
        return twinRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { parse(it) }
            .toList()
    }

    private fun parse(file: File): List<Twin> {
        val twins = mutableListOf<Twin>()
        var openKey: String? = null
        val region = mutableListOf<String>()
        file.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            val match = startMarker.matchEntire(line)
            when {
                match != null -> {
                    check(openKey == null) {
                        "$file:${i + 1}: nested snippet marker (previous '$openKey' has no end marker)"
                    }
                    openKey = match.groupValues[1]
                    region.clear()
                }
                line == endMarkerText -> {
                    val key = checkNotNull(openKey) { "$file:${i + 1}: end marker without a start marker" }
                    twins += Twin(key, file, region.joinToString("\n"))
                    openKey = null
                }
                openKey != null -> region += raw
            }
        }
        check(openKey == null) { "$file: snippet marker '$openKey' is never closed" }
        return twins
    }
}

/**
 * Parses `doc-snippets/snippet-exclusions.txt`: one `path#index: reason` per
 * line, `#`-prefixed comment lines and blank lines ignored.
 */
object ExclusionList {
    private val entry = Regex("^(\\S+#\\d+):\\s+(.+)$")

    val file: File get() = File(SnippetExtraction.repoRoot, "doc-snippets/snippet-exclusions.txt")

    /** Returns block key → reason. Fails on malformed or duplicate entries. */
    fun load(): Map<String, String> {
        check(file.isFile) { "Exclusion list not found: $file" }
        val entries = mutableMapOf<String, String>()
        file.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val match =
                checkNotNull(entry.matchEntire(line)) {
                    "$file:${i + 1}: malformed exclusion entry (expected 'path#index: reason'): $line"
                }
            val (key, reason) = match.destructured
            check(entries.put(key, reason) == null) { "$file:${i + 1}: duplicate exclusion for $key" }
        }
        return entries
    }
}
