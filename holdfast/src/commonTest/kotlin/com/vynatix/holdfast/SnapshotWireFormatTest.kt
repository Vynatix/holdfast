@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class WireStore : Store<WireStore>() {
    val label by state(codec = StringCodec) { "" }
    val count by state(codec = IntCodec) { 0 }

    /** No codec, and declared before [draft]: the skipped names are written sorted, not in declaration order. */
    val zeta by state { 0 }
    val draft by state { "" }
}

private const val HEADER = """{"format":"holdfast.store","v":1,"schema":1,"""

/** Issue #20, R1: the v1 wire format — canonical writing, escaping, and a reader that is strict yet tolerant of the unknown. */
class SnapshotWireFormatTest {
    @Test fun theEncodingIsCanonical() {
        val store = WireStore()
        store action {
            label mutate "hi"
            count mutate 3
            draft mutate "not written"
        }

        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"count":"3","label":"hi"},"skipped":["draft","zeta"]}""",
            store.snapshot().encode(),
            "fields in order, states and skipped names sorted by name, no whitespace",
        )
        assertEquals(
            HEADER + """"states":{},"skipped":["draft","zeta"]}""",
            StoreSnapshot.decode(HEADER + """"states":{},"skipped":["zeta","draft"]}""").encode(),
            "a decoded snapshot's skipped names are written sorted too",
        )
    }

    @Test fun stringsAreEscapedCanonicallyAndRoundTrip() {
        val tricky = "q\"b\\s/\b\u000C\n\r\t\u0000\u001f é 😀 \u2028"
        val store = WireStore()
        store action { label mutate tricky }

        val text = store.snapshot().encode()

        assertTrue(
            text.contains(""""label":"q\"b\\s/\b\f\n\r\t\u0000\u001f é 😀 """ + "\u2028" + "\""),
            "short escapes where RFC 8259 has them, lowercase \\u00xx for other control characters, " +
                "everything else as is: $text",
        )
        val fresh = WireStore()
        fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals(tricky, fresh.label.value)
    }

    @Test fun unpairedSurrogatesAreEscapedAndRoundTrip() {
        val lone = "a\uD800b\uDC00c\uD83D"
        val store = WireStore()
        store action { label mutate lone }

        val text = store.snapshot().encode()

        assertTrue(text.contains("a\\ud800b\\udc00c\\ud83d"), "each unpaired surrogate is escaped: $text")
        assertTrue(text.none { it.isSurrogate() }, "the text itself is valid Unicode")
        assertEquals(lone, StoreSnapshot.decode(text)[WireStore().label])
    }

    @Test fun aRawUnpairedSurrogateIsRejectedAndAnEscapedOneAccepted() {
        val raw = HEADER + "\"states\":{\"label\":\"x\uD800y\"},\"skipped\":[]}"
        val failure = assertFailsWith<SnapshotFormatException> { StoreSnapshot.decode(raw) }
        assertTrue(failure.message!!.contains("unpaired surrogate"), failure.message)

        val escaped = StoreSnapshot.decode(HEADER + """"states":{"label":"x\uD800y"},"skipped":[]}""")
        assertEquals("x\uD800y", escaped[WireStore().label], "uppercase hex escapes read too")
    }

    @Test fun deepNestingIsRejectedWithoutOverflowingTheStack() {
        val depth = 100_000
        val deep = "[".repeat(depth) + "]".repeat(depth)

        val inUnknownField =
            assertFailsWith<SnapshotFormatException> {
                StoreSnapshot.decode(HEADER + """"states":{},"skipped":[],"x":$deep}""")
            }
        assertTrue(inUnknownField.message!!.contains("nested deeper than 64"), inUnknownField.message)
        val deepFamily = "{\"k\":".repeat(depth) + "\"v\"" + "}".repeat(depth)
        val inFamily = assertFailsWith<SnapshotFormatException> { StoreSnapshot.decode(HEADER + """"states":{"f":$deepFamily}}""") }
        assertTrue(inFamily.message!!.contains("nested deeper than 64"), inFamily.message)
        // A document that opens as an object and nests objects deeply, through an unknown field.
        val deepObject = "{\"x\":".repeat(depth) + "1" + "}".repeat(depth)
        val atTop = assertFailsWith<SnapshotFormatException> { StoreSnapshot.decode(deepObject) }
        assertTrue(atTop.message!!.contains("nested deeper than 64"), atTop.message)
    }

    @Test fun nestingUpToTheCapIsSkipped() {
        // The body is level 1, so an unknown field may nest 63 more levels.
        val allowed = "[".repeat(63) + "]".repeat(63)
        StoreSnapshot.decode(HEADER + """"states":{},"skipped":[],"x":$allowed}""")

        val tooDeep = "[".repeat(64) + "]".repeat(64)
        assertFailsWith<SnapshotFormatException> { StoreSnapshot.decode(HEADER + """"states":{},"skipped":[],"x":$tooDeep}""") }
    }

    @Test fun unknownFieldsOfEveryKindAreSkipped() {
        val text =
            """
            {
              "extra": {"nested": [1, -0, 2.5, -1.5e+10, 3E-2, true, false, null, "}]\"\\", {"a": []}]},
              "states" : { "count" : "7" , "label" : "x" } ,
              "schema": 1, "later": "text with \u00e9scapes \ud83d\ude00",
              "v": 1, "format": "holdfast.store",
              "skipped": [ "draft" ], "empty": {}, "list": []
            }
            """.trimIndent()

        val decoded = StoreSnapshot.decode(text)

        val store = WireStore()
        assertEquals(7, decoded[store.count])
        assertEquals("x", decoded[store.label])
        assertEquals(setOf("draft"), decoded.unencodableStateNames)
        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"count":"7","label":"x"},"skipped":["draft"]}""",
            decoded.encode(),
            "re-encoding a decoded snapshot writes the canonical form, without the unknown fields",
        )
    }

    @Test fun malformedTextIsRejectedNamingTheProblem() {
        val body = """"states":{"count":"1"},"skipped":[]}"""
        val cases =
            mapOf(
                "" to "expected '{'",
                "[]" to "expected '{'",
                """{"format":"other","v":1,"schema":1,$body""" to "not a Holdfast store snapshot",
                """{"format":"holdfast.store","v":2,"schema":1,$body""" to "format version 2",
                """{"format":"holdfast.store","schema":1,$body""" to "\"v\" field is missing",
                """{"format":"holdfast.store","v":1,"schema":0,$body""" to "\"schema\" field",
                """{"format":"holdfast.store","v":1,"schema":1,"skipped":[]}""" to "\"states\" field is missing",
                HEADER + """"states":{"a":"1","a":"2"},"skipped":[]}""" to "duplicate state",
                HEADER + """"states":{"a":"1"},"skipped":["a"]}""" to "both encoded and skipped",
                HEADER + """"states":{},"skipped":[1]}""" to "expected a state name",
                HEADER + """"states":{"a":1},"skipped":[]}""" to "expected a state's text",
                HEADER + body + " {}" to "unexpected content after the snapshot",
                HEADER + """"states":{"a":"1",},"skipped":[]}""" to "expected a string",
                HEADER + """"states":{},"skipped":[],"x":01}""" to "expected ',' or '}'",
                HEADER + """"states":{},"skipped":[],"x":1.}""" to "malformed number",
                HEADER + """"states":{},"skipped":[],"x":+1}""" to "expected a value",
                HEADER + """"states":{},"skipped":[],"x":tru}""" to "expected a value",
                HEADER + """"states":{"a":"\q"},"skipped":[]}""" to "invalid escape sequence",
                HEADER + """"states":{"a":"\u12G4"},"skipped":[]}""" to "invalid \\u escape",
                HEADER + """"states":{"a":"open""" to "unterminated string",
                HEADER + """"v":1,"states":{},"skipped":[]}""" to "duplicate field \"v\"",
            )
        val wrong =
            cases.mapNotNull { (text, expected) ->
                val failure = runCatching { StoreSnapshot.decode(text) }.exceptionOrNull()
                val message = failure?.let { assertIs<SnapshotFormatException>(it).message }
                if (message != null && expected in message && "offset" in message) null else "$text -> $message"
            }
        assertEquals(emptyList(), wrong, "each malformed text fails with its own SnapshotFormatException")
    }

    @Test fun familyObjectsAreReadButNeverWritten() {
        val text = HEADER + """"states":{"docs":{"a":"1","b":null},"label":"x"},"skipped":[]}"""
        val decoded = StoreSnapshot.decode(text)

        assertTrue("docs" in decoded.stateNames)
        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"label":"x"},"skipped":[]}""",
            decoded.encode(),
        )
        val store = WireStore()
        val report = store.restore(decoded).let { assertIs<TransactionResult.Success<Unit>>(it) }
        val reported = store.restore(decoded, RestorePolicy.BestEffort).getOrThrow()
        assertEquals(Unit, report.value)
        assertEquals(listOf<RestoreIssue>(RestoreIssue.UnknownState("docs")), reported.issues, "no store declares families yet")
        assertEquals("x", store.label.value)
    }

    @Test fun aFamilyUnderAPlainStatesNameIsNotAValue() {
        val decoded = StoreSnapshot.decode(HEADER + """"states":{"label":{"k":"v"}},"skipped":[]}""")
        val store = WireStore()

        assertFailsWith<SnapshotFormatException> { decoded[store.label] }
        val report = store.restore(decoded, RestorePolicy.BestEffort).getOrThrow()
        assertEquals("label", report.issues.single().stateName)
        assertIs<RestoreIssue.Undecodable>(report.issues.single())
        assertIs<TransactionResult.Error>(store.restore(decoded), "only BestEffort skips it")
    }

    @Test fun theSchemaVersionIsReadBack() {
        val decoded = StoreSnapshot.decode("""{"format":"holdfast.store","v":1,"schema":4,"states":{},"skipped":[]}""")
        assertEquals(4, decoded.schemaVersion)
        assertTrue(decoded.encode().contains(""""schema":4"""))
    }
}
