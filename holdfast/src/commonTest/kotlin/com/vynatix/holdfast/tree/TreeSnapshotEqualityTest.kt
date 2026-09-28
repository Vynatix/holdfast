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

private class EqRoot : Root("eq") {
    val store = EqStore()
    val leaf by branch(store)
    val untypedKeys by keyed<Any, EqKeyedStore>()
}

private class EqKeyedStore(
    key: Any,
    root: EqRoot,
) : Store<EqKeyedStore>(root.untypedKeys.at(key)) {
    val n by state(codec = IntCodec) { 0 }
}

/** U8: `equals` is full value equality; `equalsEncodable` is the encodable projection. */
class TreeSnapshotEqualityTest {
    @Test
    fun equalsIsFullValueEqualityIncludingSecretRemoteAndCodecLessStates() {
        val root = EqRoot()
        val before = root.snapshot()
        assertEquals(before, root.snapshot())
        assertEquals(before.hashCode(), root.snapshot().hashCode())

        root.store action { secret mutate "changed" }
        val secretChanged = root.snapshot()
        assertNotEquals(before, secretChanged, "a Secret-only change is a change")

        root.store action { remote mutate "changed" }
        val remoteChanged = root.snapshot()
        assertNotEquals(secretChanged, remoteChanged)

        root.store action { codecLess mutate "changed" }
        assertNotEquals(remoteChanged, root.snapshot())
    }

    @Test
    fun equalsEncodableIgnoresSecretRemoteAndCodecLessValues() {
        val root = EqRoot()
        val before = root.snapshot()
        root.store action {
            secret mutate "changed"
            codecLess mutate "changed"
        }
        assertTrue(before.equalsEncodable(root.snapshot()))

        root.store action { remote mutate "changed" }
        assertTrue(before.equalsEncodable(root.snapshot()), "Remote is left out unless includeRemote")
        assertFalse(before.equalsEncodable(root.snapshot(), includeRemote = true))

        root.store action { count mutate 1 }
        assertFalse(before.equalsEncodable(root.snapshot()))
    }

    @Test
    fun equalsEncodableIgnoresAKeyedBranchWithoutAKeyCodec() {
        val root = EqRoot()
        val before = root.snapshot()
        root.untypedKeys.create(42) { EqKeyedStore(it, root) }
        val after = root.snapshot()
        assertNotEquals(before, after, "value equality sees the new keyed leaf")
        assertTrue(before.equalsEncodable(after), "the un-encodable keyed branch is not part of the projection")
    }

    @Test
    fun equalityDistinguishesNamesStructureAndScope() {
        val root = EqRoot()
        val whole = root.snapshot()
        val subtree = root.snapshot(root.leaf)
        assertNotEquals(whole, subtree)
        assertEquals(subtree, whole[root.leaf])
        assertNotEquals(whole, root.snapshot(scope = SnapshotScope.Raw), "scope is part of equality")

        class OtherName : Root("other") {
            val leaf by branch(EqStore())
        }
        assertNotEquals(whole, OtherName().snapshot(), "the root name differs")
    }

    @Test
    fun hashCodeIsConsistentWithEquals() {
        val root = EqRoot()
        val a = root.snapshot()
        val b = root.snapshot()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(setOf(a), setOf(a, b))
    }
}
