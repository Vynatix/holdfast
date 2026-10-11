// Twin of GUIDE §16.3 (schema versions and migration). The block is embedded
// at top level; the test drives it with the text its comment shows and
// asserts the values its last comment claims.
package com.vynatix.holdfast.snippets.twins.guidemigration

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotMigrationException
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// DOC-SNIPPET holdfast/GUIDE.md#69
// Schema 1, the first release, saved:
// {"format":"holdfast.store","v":1,"schema":1,"states":{"fontSize":"16","theme":"dark"},"skipped":[]}
@OptIn(ExperimentalStoreApi::class)
class ReaderSettings : Store<ReaderSettings>(), SchemaVersioned {
    val theme by state(codec = StringCodec) { "day" }
    val textSize by state(codec = IntCodec) { 14 }

    override val schemaVersion: Int get() = 3

    override fun migrate(from: Int, view: EncodedSnapshotView) {
        if (from < 2) view.rename("fontSize", "textSize")                   // schema 2 renamed it
        if (from < 3 && view["theme"] == "dark") view.put("theme", "night") // schema 3 respelled it
    }
}

@OptIn(ExperimentalStoreApi::class)
fun boot(saved: String): ReaderSettings {
    val settings = ReaderSettings()
    settings.restore(StoreSnapshot.decode(saved), RestorePolicy.Strict).getOrThrow()
    return settings   // from the text above: textSize = 16, theme = "night"
}
// DOC-SNIPPET-END

/** Schema 1 of the store, as the first release declared it. */
@OptIn(ExperimentalStoreApi::class)
private class ReaderSettingsV1 : Store<ReaderSettingsV1>() {
    val theme by state(codec = StringCodec) { "day" }
    val fontSize by state(codec = IntCodec) { 14 }
}

@OptIn(ExperimentalStoreApi::class)
class GuideSchemaMigrationTwin {
    private val schema1 =
        """{"format":"holdfast.store","v":1,"schema":1,"states":{"fontSize":"16","theme":"dark"},"skipped":[]}"""

    @Test
    fun bootMigratesTheTextTheCommentShows() {
        val v1 = ReaderSettingsV1()
        v1 action {
            theme mutate "dark"
            fontSize mutate 16
        }
        assertEquals(schema1, v1.snapshot().encode(), "schema 1 saves the text the comment shows")

        val settings = boot(schema1)

        assertEquals(16, settings.textSize.value)
        assertEquals("night", settings.theme.value)
        assertEquals(3, settings.snapshot().schemaVersion)
    }

    @Test
    fun anOlderReleaseRefusesTheTextThisOneSaves() {
        val saved = boot(schema1).snapshot().encode()
        val result = ReaderSettingsV1().restore(StoreSnapshot.decode(saved), RestorePolicy.BestEffort)
        val failure = assertIs<SnapshotMigrationException>(assertIs<TransactionResult.Error>(result).exception)
        assertEquals(3, failure.snapshotVersion)
        assertEquals(1, failure.storeVersion)
    }
}
