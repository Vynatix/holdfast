@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RiPrefsStore : Store<RiPrefsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
}

/** Two top-level classes with nothing in common but their children. */
private class RiAppStore : Store<RiAppStore>() {
    val prefs by store(named = "prefs") { RiPrefsStore() }
}

private class RiOtherStore : Store<RiOtherStore>() {
    val prefs by store(named = "prefs") { RiPrefsStore() }
}

/** A class "renamed" across versions: the same tree id under two class names. */
private class RiAppV1 :
    Store<RiAppV1>(),
    TreeIdentified {
    override val treeId: String get() = "app"
    val prefs by store(named = "prefs") { RiPrefsStore() }
}

private class RiRenamedAppV2 :
    Store<RiRenamedAppV2>(),
    TreeIdentified {
    override val treeId: String get() = "app"
    val prefs by store(named = "prefs") { RiPrefsStore() }
}

private class RiEmptyId :
    Store<RiEmptyId>(),
    TreeIdentified {
    override val treeId: String get() = ""
}

/** Two parents that both declare the same pinned child under different properties. */
private class RiParentA : Store<RiParentA>() {
    val settingsA by store(named = "settings") { RiPrefsStore() }
}

private class RiParentB : Store<RiParentB>() {
    val settingsB by store(named = "settings") { RiPrefsStore() }
}

/** Decision 1: the tree envelope carries the receiver's identity and decode checks it. */
class TreeReceiverIdentityTest {
    @Test
    fun encodeWritesTheReceiversNodeNameOrItsTreeId() {
        val plain = RiAppStore()
        assertTrue(
            plain.tree
                .snapshot()
                .encode()
                .startsWith("""{"format":"holdfast.tree","v":1,"receiver":"RiApp","""),
        )
        val identified = RiAppV1()
        assertTrue(
            identified.tree
                .snapshot()
                .encode()
                .startsWith("""{"format":"holdfast.tree","v":1,"receiver":"app","""),
        )
        // A child receiver is identified by its pin.
        val child = RiAppStore().prefs
        assertTrue(
            child.tree
                .snapshot()
                .encode()
                .contains(""""receiver":"prefs""""),
        )
    }

    @Test
    fun aMismatchedReceiverThrowsNamingBothAndNoValue() {
        val app = RiAppStore()
        app.prefs action { theme mutate "secret-ish value" }
        val text = app.tree.snapshot().encode()
        val other = RiOtherStore()
        val failure = assertFailsWith<SnapshotFormatException> { other.tree.decode(text) }
        val message = failure.message.orEmpty()
        assertTrue("captured under 'RiApp', decoding under 'RiOther'" in message, message)
        assertFalse("secret-ish" in message, "never a value")
        assertEquals("light", other.prefs.theme.value, "nothing touched")
    }

    @Test
    fun aTreeIdSurvivesAClassRename() {
        val v1 = RiAppV1()
        v1.prefs action { theme mutate "dark" }
        val text = v1.tree.snapshot().encode()
        val v2 = RiRenamedAppV2()
        val decoded = v2.tree.decode(text)
        assertTrue(decoded.unresolvedPaths.isEmpty(), "${decoded.unresolvedPaths}")
        assertIs<TransactionResult.Success<*>>(v2.tree.restore(decoded))
        assertEquals("dark", v2.prefs.theme.value)
        // Without the id, the same rename is refused.
        val plainText = RiAppStore().tree.snapshot().encode()
        assertFailsWith<SnapshotFormatException> { v2.tree.decode(plainText) }
    }

    @Test
    fun aChildPinnedWithNamedDecodesUnderTheSamePinThroughAnotherParent() {
        val a = RiParentA()
        a.settingsA action { theme mutate "dark" }
        val text =
            a.settingsA.tree
                .snapshot()
                .encode()
        assertTrue(""""receiver":"settings"""" in text, text)
        val b = RiParentB()
        val decoded = b.settingsB.tree.decode(text)
        assertIs<TransactionResult.Success<*>>(b.settingsB.tree.restore(decoded))
        assertEquals("dark", b.settingsB.theme.value)
    }

    @Test
    fun aMissingReceiverIsAFormatError() {
        val app = RiAppStore()
        val text =
            app.tree
                .snapshot()
                .encode()
                .replace(""""receiver":"RiApp",""", "")
        val failure = assertFailsWith<SnapshotFormatException> { app.tree.decode(text) }
        assertTrue("missing \"receiver\"" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun anEmptyTreeIdIsRefused() {
        val failure = assertFailsWith<IllegalStateException> { RiEmptyId().tree.snapshot() }
        assertTrue("treeId must not be empty" in failure.message.orEmpty(), failure.message)
    }
}
