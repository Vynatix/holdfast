@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.cli

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A versioned store, to exercise schema drift in diffs. */
internal class VersionedStore :
    Store<VersionedStore>(),
    SchemaVersioned {
    override val schemaVersion: Int get() = 2

    val title by state(codec = StringCodec, initialize = { "untitled" })

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        // Nothing to migrate in the fixture; the version is what the CLI reports.
    }
}

internal fun decoded(text: String): SnapshotFacts =
    when (val result = decodeSnapshotFacts(text)) {
        is DecodeResult.Decoded -> result.facts
        is DecodeResult.Rejected -> error("fixture must decode: ${result.message}")
    }

class SnapshotLogicTest {
    @Test
    fun decodesRealEncodingWithFacts() {
        val text = encodedFixture { notes["a"] }
        val facts = decoded(text)
        assertEquals(expected = 1, actual = facts.schemaVersion)
        assertTrue("title" in facts.stateNames, "title should be in: ${facts.stateNames}")
        assertTrue("visits" in facts.stateNames)
        assertTrue("notes" in facts.stateNames)
        assertTrue("apiKey" in facts.stateNames, "a Secret state is encoded as null, not omitted")
        assertTrue("remoteNote" !in facts.stateNames, "remote states are omitted by the default scope")
        assertEquals(expected = setOf("cache"), actual = facts.unencodableStateNames)
    }

    @Test
    fun remoteStatesAppearOnlyWhenIncluded() {
        val facts = decoded(encodedFixture(includeRemote = true))
        assertTrue("remoteNote" in facts.stateNames)
    }

    @Test
    fun renderedValuesRedactSecrets() {
        val facts =
            decoded(
                encodedFixture {
                    title mutate "Hello"
                    apiKey mutate "hunter2"
                },
            )
        assertTrue("Hello" in facts.rendered)
        assertTrue("apiKey" in facts.rendered)
        assertTrue("hunter2" !in facts.rendered, "a Secret value must never reach CLI output")
    }

    @Test
    fun rejectsGarbageWithOffsetBearingMessage() {
        val rejected = assertIs<DecodeResult.Rejected>(decodeSnapshotFacts("{not json"))
        assertTrue("offset" in rejected.message, "message should name the position: ${rejected.message}")
    }

    @Test
    fun rejectsDuplicateField() {
        val duplicated =
            """{"format":"holdfast.store","v":1,"schema":1,"states":{},"skipped":[],"schema":2}"""
        val rejected = assertIs<DecodeResult.Rejected>(decodeSnapshotFacts(duplicated))
        assertTrue("duplicate" in rejected.message, "message should name the problem: ${rejected.message}")
    }

    @Test
    fun rejectsTruncatedBody() {
        assertIs<DecodeResult.Rejected>(decodeSnapshotFacts(encodedFixture().dropLast(3)))
    }

    @Test
    fun writerOutputIsCanonical() {
        assertEquals(expected = ValidationOutcome.Valid(canonical = true), actual = validateSnapshotText(encodedFixture()))
    }

    @Test
    fun liberalWhitespaceDecodesButIsNotCanonical() {
        val spaced = encodedFixture().replace(",", ", ")
        assertEquals(expected = ValidationOutcome.Valid(canonical = false), actual = validateSnapshotText(spaced))
    }

    @Test
    fun inspectReportListsFactsAndValues() {
        val report = inspectReport(decoded(encodedFixture()), showValues = true)
        assertTrue("schema version: 1" in report)
        assertTrue("unencodable (no codec): cache" in report)
        assertTrue("values:" in report)
    }

    @Test
    fun inspectReportCanOmitValues() {
        val report = inspectReport(decoded(encodedFixture()), showValues = false)
        assertTrue("values:" !in report)
    }

    @Test
    fun doctorMentionsBuildAndRuntime() {
        val report = doctorReport("0.1.0")
        assertTrue("holdfast library: 0.1.0" in report)
        assertTrue("Kotlin:" in report)
        assertTrue("JVM:" in report)
    }
}
