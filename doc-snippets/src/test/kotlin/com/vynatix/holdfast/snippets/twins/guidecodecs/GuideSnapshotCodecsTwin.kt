// Twins of GUIDE §16.2 (encoding snapshots). The blocks are embedded at top
// level; the tests drive them and assert the text, values and output their
// comments claim.
package com.vynatix.holdfast.snippets.twins.guidecodecs

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.RestoreReport
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.snippets.capturePrintln
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#66
@OptIn(ExperimentalStoreApi::class)
class SettingsStore : Store<SettingsStore>() {
    val theme by state(codec = StringCodec) { "light" }
    val fontSize by state(codec = IntCodec) { 14 }
    val draft by state { "" }        // no codec: captured in memory, never encoded
}

@OptIn(ExperimentalStoreApi::class)
fun moveSettings(from: SettingsStore, to: SettingsStore): RestoreReport {
    // Say `from` holds theme = "dark", fontSize = 16, draft = "unsent".
    val snapshot = from.snapshot()
    println("font size ${snapshot[from.fontSize]}")   // typed read (an Int?): "font size 16"
    val text = snapshot.encode()
    // {"format":"holdfast.store","v":1,"schema":1,
    //  "states":{"fontSize":"16","theme":"dark"},"skipped":["draft"]}
    return to.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
    // restored = [fontSize, theme], kept = [draft]: `to` keeps its own draft.
}
// DOC-SNIPPET-END

// DOC-SNIPPET holdfast/GUIDE.md#67
class KSerializerCodec<T : Any>(
    private val serializer: KSerializer<T>,
    private val json: Json = Json,
) : StateCodec<T> {
    override fun encode(value: T): String = json.encodeToString(serializer, value)
    override fun decode(string: String): T = json.decodeFromString(serializer, string)
}

@OptIn(ExperimentalStoreApi::class)
class InboxStore : Store<InboxStore>() {
    val pinned by state(codec = KSerializerCodec(ListSerializer(String.serializer()))) {
        emptyList<String>()
    }
}
// DOC-SNIPPET-END

@OptIn(ExperimentalStoreApi::class)
class GuideSnapshotCodecsTwin {
    @Test
    fun moveSettingsRestoresTheEncodableStatesAndKeepsTheRest() {
        val from = SettingsStore()
        from action {
            theme mutate "dark"
            fontSize mutate 16
            draft mutate "unsent"
        }
        val to = SettingsStore()
        to action { draft mutate "mine" }

        lateinit var report: RestoreReport
        val printed = capturePrintln { report = moveSettings(from, to) }

        assertEquals(listOf("font size 16"), printed)
        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,""" +
                """"states":{"fontSize":"16","theme":"dark"},"skipped":["draft"]}""",
            from.snapshot().encode(),
            "the text the comment shows",
        )
        assertEquals(listOf("fontSize", "theme"), report.restored.toList())
        assertEquals(setOf("draft"), report.kept)
        assertEquals("dark", to.theme.value)
        assertEquals(16, to.fontSize.value)
        assertEquals("mine", to.draft.value, "`to` keeps its own draft")
    }

    @Test
    fun theKSerializerCodecRoundTripsThroughText() {
        val inbox = InboxStore()
        inbox action { pinned mutate listOf("a", "b,\"c\"") }

        val text = inbox.snapshot().encode()
        val fresh = InboxStore()
        fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()

        assertEquals(listOf("a", "b,\"c\""), fresh.pinned.value)
        val codec: StateCodec<List<String>> = KSerializerCodec(ListSerializer(String.serializer()))
        assertEquals("""["a","b,\"c\""]""", codec.encode(listOf("a", "b,\"c\"")))
    }
}
