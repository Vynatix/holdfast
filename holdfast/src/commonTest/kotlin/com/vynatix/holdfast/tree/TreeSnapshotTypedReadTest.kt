@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.computed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private enum class ThemeMode { Light, Dark }

private class TrSettingsStore : Store<TrSettingsStore>() {
    val themeMode by state { ThemeMode.Light }
    val token by state(tags = setOf(StateTag.Secret)) { "s3cr3t" }
    val doubled = computed { themeMode.value.name + themeMode.value.name }
}

private class TrProfileStore : Store<TrProfileStore>() {
    val name by state { "me" }
}

private class TrThreadStore(
    id: String,
    root: TrRoot,
) : Store<TrThreadStore>(root.threads.at(id)) {
    val title by state { "thread $id" }
}

private class TrRoot : Root("tr") {
    val settingsStore = TrSettingsStore()
    val profileStore = TrProfileStore()
    val settings by branch(settingsStore)
    val profile by branch(profileStore)
    val threads by keyed<String, TrThreadStore>()
}

/** T5 and T7.4: reads are typed by the state and addressed by identity. */
class TreeSnapshotTypedReadTest {
    @Test
    fun aTypedReadHasTheStatesStaticType() {
        val root = TrRoot()
        root.settingsStore action { themeMode mutate ThemeMode.Dark }
        val theme: ThemeMode? = root.snapshot()[root.settingsStore.themeMode]
        assertEquals(ThemeMode.Dark, theme)
        val entry: SnapshotEntry<ThemeMode> = root.snapshot().entry(root.settingsStore.themeMode)
        assertEquals(SnapshotEntry.Present(ThemeMode.Dark), entry)
    }

    @Test
    fun getWithANodeReturnsTheSubtreeCaptureAndNullOutsideIt() {
        val root = TrRoot()
        val whole = root.snapshot()
        assertSame(whole, whole[root])
        assertEquals("settings", whole[root.settings]!!.node.name)
        assertTrue(whole[root.settings]!!.children.single().isLeaf)

        val subtree = root.snapshot(root.settings)
        assertSame(subtree, subtree[root.settings])
        assertNull(subtree[root.profile])
        assertNull(subtree[root])
    }

    @Test
    fun aStateOutsideTheSubtreeReadsNullAndItsEntryIsAbsent() {
        val root = TrRoot()
        val subtree = root.snapshot(root.settings)
        assertNull(subtree[root.profileStore.name])
        assertEquals(SnapshotEntry.Absent, subtree.entry(root.profileStore.name))
        assertEquals("me", subtree[root.profileStore.name] ?: root.snapshot()[root.profileStore.name])
    }

    @Test
    fun getWorksAfterAKeyedStoresDispose() {
        val root = TrRoot()
        val t = root.threads.create("k") { TrThreadStore(it, root) }
        val title = t.title // the delegate itself is gated after dispose; the State is not
        val captured = root.snapshot()
        t.dispose()
        assertEquals("thread k", captured[title])
        assertTrue(captured[root.threads]!!.children.single().isLeaf)
        assertNull(root.snapshot()[root.threads]!!.children.firstOrNull(), "a later capture no longer holds the disposed leaf")
    }

    @Test
    fun getOnANeverAttachedStoresStateThrows() {
        val root = TrRoot()
        val loose = TrProfileStore()
        val error = assertFailsWith<IllegalArgumentException> { root.snapshot()[loose.name] }
        assertTrue("not a member of root 'tr'" in error.message!!, error.message)
    }

    @Test
    fun getOnAComputedStateThrows() {
        val root = TrRoot()
        val error = assertFailsWith<IllegalArgumentException> { root.snapshot()[root.settingsStore.doubled] }
        assertTrue("declared states only" in error.message!!, error.message)
    }

    @Test
    fun aSecretReadsNullUnderAllWhileItsEntryIsRedacted() {
        val root = TrRoot()
        val all = root.snapshot()
        assertNull(all[root.settingsStore.token])
        assertSame(Redacted, all.entry(root.settingsStore.token))
        assertEquals("s3cr3t", root.snapshot(scope = SnapshotScope.Raw)[root.settingsStore.token])
    }

    @Test
    fun renamingAStatePropertyChangesCallSitesAtCompileTime() {
        // T7.4 fixture: the only way to address a value is through the state
        // property itself, so renaming `themeMode` breaks this line at compile
        // time — there is no string-keyed overload to keep an old name alive.
        val root = TrRoot()
        val viaProperty = root.snapshot()[root.settingsStore.themeMode]
        assertEquals(ThemeMode.Light, viaProperty)
    }
}
