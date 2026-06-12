package com.vynatix.holdfast.snippets

import kotlin.test.Test
import kotlin.test.fail

/**
 * The accounting test behind issue #9: every fenced ```kotlin block in the
 * tracked docs must either have a verbatim twin (which the normal compiler
 * then proves compilable) or an explicit entry in `snippet-exclusions.txt`.
 * Any doc edit that changes a twinned block fails here, naming the doc file
 * and block index; the twin is then updated (and recompiled) to match.
 */
class DocSnippetDriftTest {
    private val snippets = SnippetExtraction.extractAll()
    private val snippetsByKey = snippets.associateBy { it.key }
    private val twins = TwinRegistry.scan()
    private val exclusions = ExclusionList.load()

    @Test
    fun twinKeysAreUnique() {
        val duplicates = twins.groupBy { it.key }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            fail(
                duplicates.entries.joinToString("\n") { (key, dupes) ->
                    "$key is twinned ${dupes.size} times: ${dupes.joinToString { it.file.name }}"
                },
            )
        }
    }

    @Test
    fun everyDocBlockIsTwinnedOrExcluded() {
        val twinned = twins.map { it.key }.toSet()
        val missing =
            snippets.filter { it.key !in twinned && it.key !in exclusions }
        if (missing.isNotEmpty()) {
            fail(
                "Fenced kotlin blocks with neither a twin nor an exclusion entry:\n" +
                    missing.joinToString("\n") { snippet ->
                        val firstLine =
                            snippet.code
                                .lines()
                                .firstOrNull { it.isNotBlank() }
                                ?.trim()
                                .orEmpty()
                        "  ${snippet.key} (${snippet.doc} line ${snippet.startLine}): $firstLine"
                    } +
                    "\nAdd a twin under doc-snippets/src/test/kotlin/.../twins " +
                    "or a reasoned entry in doc-snippets/snippet-exclusions.txt.",
            )
        }
    }

    @Test
    fun noBlockIsBothTwinnedAndExcluded() {
        val both = twins.map { it.key }.toSet() intersect exclusions.keys
        if (both.isNotEmpty()) {
            fail("Blocks both twinned and excluded (drop one): ${both.sorted()}")
        }
    }

    @Test
    fun twinsReferToExistingDocBlocks() {
        val stale = twins.filter { it.key !in snippetsByKey }
        if (stale.isNotEmpty()) {
            fail(
                "Twins referencing doc blocks that no longer exist (doc edited or block index shifted?):\n" +
                    stale.joinToString("\n") { "  ${it.key} in ${it.file}" },
            )
        }
    }

    @Test
    fun exclusionsReferToExistingDocBlocks() {
        val stale = exclusions.keys.filter { it !in snippetsByKey }
        if (stale.isNotEmpty()) {
            fail(
                "Exclusion entries referencing doc blocks that no longer exist " +
                    "(doc edited or block index shifted?): ${stale.sorted()}",
            )
        }
    }

    @Test
    fun twinsMatchTheirDocBlocksVerbatim() {
        val mismatches =
            twins.mapNotNull { twin ->
                val snippet = snippetsByKey[twin.key] ?: return@mapNotNull null
                val docCode = SnippetExtraction.normalize(snippet.code)
                val twinCode = SnippetExtraction.normalize(twin.code)
                if (docCode == twinCode) {
                    null
                } else {
                    "${twin.key} drifted (doc ${snippet.doc} line ${snippet.startLine} vs twin ${twin.file}):\n" +
                        "--- doc block ---\n$docCode\n--- twin ---\n$twinCode\n-----------------"
                }
            }
        if (mismatches.isNotEmpty()) {
            fail(mismatches.joinToString("\n\n"))
        }
    }
}
