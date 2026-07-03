package com.vynatix.holdfast.snippets

import java.io.File

/** A fenced ```kotlin block extracted from one of the tracked docs. */
data class DocSnippet(
    /** Doc path relative to the repository root, e.g. `holdfast/GUIDE.md`. */
    val doc: String,
    /** Zero-based index among the doc's ```kotlin fences, in document order. */
    val index: Int,
    /** One-based line number of the block's first content line. */
    val startLine: Int,
    /** Verbatim block content (without the fence lines). */
    val code: String,
) {
    val key: String get() = "$doc#$index"
}

/**
 * Extracts fenced ```kotlin blocks from the doc set gated by issue #9. Plain
 * fences (` ``` `), `sh`, and other info strings are tracked only so their
 * closing fences are not misread, and are otherwise out of scope.
 */
object SnippetExtraction {
    /** Doc paths relative to the repository root. */
    val trackedDocs =
        listOf(
            "README.md",
            "holdfast/README.md",
            "holdfast/GUIDE.md",
            "holdfast-coroutines/README.md",
            "holdfast-compose/README.md",
        )

    /**
     * The repository root, located by walking up from the test working
     * directory (Gradle runs tests with the module directory as cwd).
     */
    val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "doc-snippets").isDirectory }
            ?: error("Could not locate the repository root walking up from ${System.getProperty("user.dir")}")
    }

    fun extractAll(): List<DocSnippet> = trackedDocs.flatMap(::extract)

    fun extract(doc: String): List<DocSnippet> {
        val file = File(repoRoot, doc)
        check(file.isFile) { "Tracked doc not found: $file" }
        val snippets = mutableListOf<DocSnippet>()
        var fenceInfo: String? = null
        var blockStart = 0
        var kotlinIndex = -1
        val block = mutableListOf<String>()
        file.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (fenceInfo == null) {
                if (line.startsWith("```")) {
                    fenceInfo = line.removePrefix("```").trim()
                    if (fenceInfo == "kotlin") {
                        kotlinIndex++
                        blockStart = i + 2
                        block.clear()
                    }
                }
            } else if (line == "```") {
                if (fenceInfo == "kotlin") {
                    snippets += DocSnippet(doc, kotlinIndex, blockStart, block.joinToString("\n"))
                }
                fenceInfo = null
            } else if (fenceInfo == "kotlin") {
                block += raw
            }
        }
        check(fenceInfo == null) { "Unterminated code fence in $doc" }
        return snippets
    }

    /**
     * Comparison form for drift detection: trailing whitespace stripped,
     * surrounding blank lines dropped, and the common leading indentation
     * removed — twins embed blocks at scaffold indentation while docs embed
     * them at column zero.
     */
    fun normalize(code: String): String {
        val lines =
            code
                .lines()
                .map { it.trimEnd() }
                .dropWhile { it.isEmpty() }
                .dropLastWhile { it.isEmpty() }
        val indent =
            lines
                .filter { it.isNotEmpty() }
                .minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: 0
        return lines.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it }
    }
}
