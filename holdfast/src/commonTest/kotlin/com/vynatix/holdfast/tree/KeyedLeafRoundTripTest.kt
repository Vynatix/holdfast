@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class KlThreadStore(
    id: String,
    root: KlRoot,
) : Store<KlThreadStore>(root.threads.at(id)) {
    val title by state(codec = StringCodec) { "thread $id" }
}

private class KlOpaqueStore(
    key: Any,
    root: KlRoot,
) : Store<KlOpaqueStore>(root.opaque.at(key)) {
    val n by state(codec = IntCodec) { 0 }
}

private class KlRoot : Root("app") {
    val session by branch()
    val threads by keyed<String, KlThreadStore>(under = session)
    val opaque by keyed<Any, KlOpaqueStore>()
}

/** A key whose `toString()` must never reach a message or a persisted text. */
private class KlLeakingKey {
    override fun toString(): String = "LEAKED-KEY-TEXT"
}

/** T5 for a capture whose node is one keyed leaf: its `path` ends in the encoded key, and `decode` resolves it. */
class KeyedLeafRoundTripTest {
    @Test
    fun aCaptureOfOneLiveKeyedLeafRoundTripsAndRestores() {
        val root = KlRoot()
        val t1 = root.threads.create("t1") { KlThreadStore(it, root) }
        t1 action { title mutate "first" }
        val node = root.nodeOf(t1)!!
        val captured = root.snapshot(node)
        val text = captured.encode()
        assertContains(text, """"path":["session","threads","t1"]""")

        val decoded = root.decode(text)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "the keyed segment resolves via the key codec")
        assertSame(node, decoded.node)
        assertTrue(decoded.isLeaf)
        assertEquals("first", decoded[t1.title])
        assertTrue(decoded.equalsEncodable(captured))
        assertTrue(captured.equalsEncodable(decoded))
        assertEquals(emptySet<String>(), decoded.pendingKeys(root.threads), "the store is live: nothing pending")

        t1 action { title mutate "changed" }
        val report = root.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("first", t1.title.value, "the decoded leaf restores into the live store")
        assertEquals(listOf(node), report.perNode.keys.toList())
        assertEquals(emptyList<StoreNode>(), report.rebound)
        assertEquals(emptyList<StoreNode>(), report.skipped)
    }

    @Test
    fun aCaptureOfAKeyedLeafWhoseStoreIsGoneDecodesToAPendingLeaf() {
        val root = KlRoot()
        val t1 = root.threads.create("t1") { KlThreadStore(it, root) }
        t1 action { title mutate "first" }
        val text = root.snapshot(root.nodeOf(t1)!!).encode()
        t1.dispose()

        val decoded = root.decode(text)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths)
        assertTrue(decoded.isLeaf)
        val leaf = assertIs<LeafNode>(decoded.node)
        assertEquals("t1", leaf.key)
        assertSame(root.threads, leaf.parent)
        assertEquals(NameOrigin.Key, leaf.nameOrigin)
        assertNull(leaf.store, "no store is live under the key")
        assertEquals(setOf("t1"), decoded.pendingKeys(root.threads))
        assertSame(decoded, decoded[leaf])

        // The process-death idiom: create the pending key's store, then restore.
        val recreated = root.threads.create("t1") { KlThreadStore(it, root) }
        val report = root.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("first", recreated.title.value)
        assertEquals(listOf(root.nodeOf(recreated)!!), report.perNode.keys.toList())
        assertEquals(emptyList<StoreNode>(), report.rebound, "a pending key captured no store, so nothing is rebound")
        assertEquals(emptyList<StoreNode>(), report.skipped)
    }

    @Test
    fun aCaptureUnderAKeyedBranchWithoutAKeyCodecIsRefusedByEncodeAndUnresolvedByDecode() {
        val root = KlRoot()
        val store = root.opaque.create(KlLeakingKey()) { KlOpaqueStore(it, root) }
        val captured = root.snapshot(root.nodeOf(store)!!)
        assertTrue(captured.isLeaf)

        // Its path from the root would have to spell the key, which no codec can: refused, never `toString()`.
        val refused = assertFailsWith<IllegalStateException> { captured.encode() }
        assertContains(refused.message!!, "opaque")
        assertFalse("LEAKED-KEY-TEXT" in refused.message!!, "a key's toString() never reaches a message")

        // A text naming a path through that branch (written by hand: this root never writes one) does not resolve.
        val body = store.snapshot().encode()
        val text =
            """{"format":"holdfast.tree","v":1,"scope":"All","path":["opaque","x"],""" +
                """"tree":{"kind":"leaf","store":$body},"skipped":[]}"""
        val decoded = root.decode(text)
        assertEquals(listOf(listOf("opaque", "x")), decoded.unresolvedPaths)
        assertSame(root, decoded.node)
        assertTrue(decoded.children.isEmpty())
        assertNull(decoded[root.nodeOf(store)!!])
        assertEquals(emptySet<Any>(), decoded.pendingKeys(root.opaque))
    }
}
