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
) : Store<TrThreadStore>() {
    val title by state { "thread $id" }
}

/** The receiver: named `Tr` by its class (minus `Store`). */
private class TrStore : Store<TrStore>() {
    val settingsStore = TrSettingsStore()
    val profileStore = TrProfileStore()
    val settings by stores { listOf(settingsStore) }
    val profile by stores { listOf(profileStore) }
    val threads by stores<String, TrThreadStore> { TrThreadStore(it) }
}

/** T5 and T7.4: reads are typed by the state and addressed by identity. */
class TreeSnapshotTypedReadTest {
    @Test
    fun aTypedReadHasTheStatesStaticType() {
        val root = TrStore()
        root.settingsStore action { themeMode mutate ThemeMode.Dark }
        val theme: ThemeMode? = root.tree.snapshot()[root.settingsStore.themeMode]
        assertEquals(ThemeMode.Dark, theme)
        val entry: SnapshotEntry<ThemeMode> = root.tree.snapshot().entry(root.settingsStore.themeMode)
        assertEquals(SnapshotEntry.Present(ThemeMode.Dark), entry)
    }

    @Test
    fun getWithANodeReturnsTheSubtreeCaptureAndNullOutsideIt() {
        val root = TrStore()
        val whole = root.tree.snapshot()
        assertSame(whole, whole[root.tree.node])
        assertEquals("settings", whole[root.settings]!!.name)
        assertTrue(whole[root.settings]!!.children.single().isLeaf)

        val subtree = root.tree.snapshot(root.settings)
        assertSame(subtree, subtree[root.settings])
        assertNull(subtree[root.profile])
        assertNull(subtree[root.tree.node], "the receiver's node is above the subtree")
    }

    @Test
    fun aStateOutsideTheSubtreeReadsNullAndItsEntryIsAbsent() {
        val root = TrStore()
        val subtree = root.tree.snapshot(root.settings)
        assertNull(subtree[root.profileStore.name])
        assertEquals(SnapshotEntry.Absent, subtree.entry(root.profileStore.name))
        assertEquals("me", subtree[root.profileStore.name] ?: root.tree.snapshot()[root.profileStore.name])
    }

    @Test
    fun getWorksAfterAKeyedStoresDispose() {
        val root = TrStore()
        val t = root.threads.create("k")
        val title = t.title // the delegate itself is gated after dispose; the State is not
        val captured = root.tree.snapshot()
        t.dispose()
        assertEquals("thread k", captured[title])
        assertTrue(captured[root.threads]!!.children.single().isLeaf)
        assertNull(
            root.tree
                .snapshot()[root.threads]!!
                .children
                .firstOrNull(),
            "a later capture no longer holds the disposed leaf",
        )
    }

    @Test
    fun getOnANeverAttachedStoresStateThrows() {
        val root = TrStore()
        val loose = TrProfileStore()
        val error = assertFailsWith<IllegalArgumentException> { root.tree.snapshot()[loose.name] }
        assertTrue("not under 'Tr'" in error.message!!, error.message)
    }

    @Test
    fun getOnAComputedStateThrows() {
        val root = TrStore()
        val error = assertFailsWith<IllegalArgumentException> { root.tree.snapshot()[root.settingsStore.doubled] }
        assertTrue("declared states only" in error.message!!, error.message)
    }

    @Test
    fun aSecretReadsNullUnderAllWhileItsEntryIsRedacted() {
        val root = TrStore()
        val all = root.tree.snapshot()
        assertNull(all[root.settingsStore.token])
        assertSame(Redacted, all.entry(root.settingsStore.token))
        assertEquals("s3cr3t", root.tree.snapshot(scope = SnapshotScope.Raw)[root.settingsStore.token])
    }

    @Test
    fun renamingAStatePropertyChangesCallSitesAtCompileTime() {
        // T7.4 fixture: the only way to address a value is through the state
        // property itself, so renaming `themeMode` breaks this line at compile
        // time — there is no string-keyed overload to keep an old name alive.
        val root = TrStore()
        val viaProperty = root.tree.snapshot()[root.settingsStore.themeMode]
        assertEquals(ThemeMode.Light, viaProperty)
    }
}
