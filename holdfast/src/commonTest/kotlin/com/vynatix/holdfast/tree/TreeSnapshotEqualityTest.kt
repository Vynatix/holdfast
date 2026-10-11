@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class EqStore : Store<EqStore>() {
    val count by state(codec = IntCodec) { 0 }
    val secret by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "s" }
    val remote by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "r" }
    val codecLess by state { "c" }
}

private class EqApp : Store<EqApp>() {
    val eq = EqStore()
    val leaf by group { listOf(eq) }
    val untypedKeys by keyed<Any, EqKeyedStore> { EqKeyedStore() }
}

private class EqKeyedStore : Store<EqKeyedStore>() {
    val n by state(codec = IntCodec) { 0 }
}

/** U8: `equals` is full value equality; `equalsEncodable` is the encodable projection. */
class TreeSnapshotEqualityTest {
    @Test
    fun equalsIsFullValueEqualityIncludingSecretRemoteAndCodecLessStates() {
        val root = EqApp()
        val before = root.tree.snapshot()
        assertEquals(before, root.tree.snapshot())
        assertEquals(before.hashCode(), root.tree.snapshot().hashCode())

        root.eq action { secret mutate "changed" }
        val secretChanged = root.tree.snapshot()
        assertNotEquals(before, secretChanged, "a Secret-only change is a change")

        root.eq action { remote mutate "changed" }
        val remoteChanged = root.tree.snapshot()
        assertNotEquals(secretChanged, remoteChanged)

        root.eq action { codecLess mutate "changed" }
        assertNotEquals(remoteChanged, root.tree.snapshot())
    }

    @Test
    fun equalsEncodableIgnoresSecretRemoteAndCodecLessValues() {
        val root = EqApp()
        val before = root.tree.snapshot()
        root.eq action {
            secret mutate "changed"
            codecLess mutate "changed"
        }
        assertTrue(before.equalsEncodable(root.tree.snapshot()))

        root.eq action { remote mutate "changed" }
        assertTrue(before.equalsEncodable(root.tree.snapshot()), "Remote is left out unless includeRemote")
        assertFalse(before.equalsEncodable(root.tree.snapshot(), includeRemote = true))

        root.eq action { count mutate 1 }
        assertFalse(before.equalsEncodable(root.tree.snapshot()))
    }

    @Test
    fun equalsEncodableIgnoresAKeyedBranchWithoutAKeyCodec() {
        val root = EqApp()
        val before = root.tree.snapshot()
        root.untypedKeys.create(42)
        val after = root.tree.snapshot()
        assertNotEquals(before, after, "value equality sees the new keyed leaf")
        assertTrue(before.equalsEncodable(after), "the un-encodable keyed branch is not part of the projection")
    }

    @Test
    fun equalityDistinguishesNamesStructureAndScope() {
        val root = EqApp()
        val whole = root.tree.snapshot()
        val subtree = root.tree.snapshot(root.leaf)
        assertNotEquals(whole, subtree)
        assertEquals(subtree, whole[root.leaf])
        assertNotEquals(whole, root.tree.snapshot(scope = SnapshotScope.Raw), "scope is part of equality")

        class OtherName : Store<OtherName>() {
            val leaf by group { listOf(EqStore()) }
            val untypedKeys by keyed<Any, EqKeyedStore> { EqKeyedStore() }
        }
        val renamed = OtherName().tree.snapshot()
        assertEquals(whole.children.map { it.name }, renamed.children.map { it.name }, "the same children")
        assertNotEquals(whole, renamed, "the receiver's class-derived name differs")
    }

    @Test
    fun hashCodeIsConsistentWithEquals() {
        val root = EqApp()
        val a = root.tree.snapshot()
        val b = root.tree.snapshot()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(setOf(a), setOf(a, b))
    }
}
