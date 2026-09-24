@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #20, R7 with R4/R3: reset() and a sterile restore re-stage every live
// keyed entry from its family's initializer — through the reset pass's hook
// for keyed families (ResetPass.addLiveKeyedEntries) — and never evict one.

private class KrLabels : Store<KrLabels>() {
    val base by state { "base" }
    val labels by keyedState<String, String> { key -> "${base.value}-$key" }
}

private class KrSynced : Store<KrSynced>() {
    val title by state(codec = StringCodec) { "untitled" }
    val remote by keyedState<String, Int>(codec = IntCodec, keyCodec = StringCodec, tags = setOf(StateTag.Remote)) { 0 }
    val local by keyedState<String, Int>(codec = IntCodec, keyCodec = StringCodec) { -1 }
}

/** A declared state whose initializer reads an entry of a family whose initializer reads a declared state. */
private class KrSummary : Store<KrSummary>() {
    val base by state { "base" }
    val labels by keyedState<String, String> { key -> "${base.value}-$key" }
    val summary by state { labels["z"].value }
}

/** A declared state whose initializer gets an entry without reading its value. */
private class KrToucher : Store<KrToucher>() {
    val base by state { "base" }
    val labels by keyedState<String, String> { key -> "${base.value}-$key" }
    val touched by state {
        labels["z"]
        true
    }
}

/** Each entry past the first reads the one before it: nodes[n] = nodes[n - 1] + base. */
private class KrChain : Store<KrChain>() {
    val base by state { 1 }
    val nodes: KeyedState<Int, Int> by keyedState<Int, Int> { n -> if (n == 0) base.value else nodes[n - 1].value + base.value }
}

/** A Remote family whose entry "b" reads entry "a". */
private class KrFeed : Store<KrFeed>() {
    val feed: KeyedState<String, Int> by keyedState<String, Int>(tags = setOf(StateTag.Remote)) { key ->
        if (key == "b") feed["a"].value + 1 else 5
    }
}

/** A Remote state whose initializer, once base is restored, reads an entry nothing has created yet. */
private class KrSterile : Store<KrSterile>() {
    val base by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "base" }
    val labels by keyedState<String, String> { key -> "${base.value}-$key" }
    val remote by state(tags = setOf(StateTag.Remote)) { if (base.value == "base") "none" else labels["z"].value }
}

/** Reads `labels["a"]` when told of a reset: an attachment sees the reset's values. */
private class KrReadingAttachment(
    private val store: KrLabels,
) : StoreAttachment {
    val seen = mutableListOf<String>()

    override fun onStoreReset() {
        seen += store.labels["a"].value
    }
}

private val krAttachmentKey = StoreAttachmentKey<KrReadingAttachment>("keyed-reset-probe")

class KeyedStateResetHookTest {
    @Test fun resetReStagesEveryLiveEntryAndNeverEvictsOne() {
        val store = KrLabels()
        val a = store.labels["a"]
        val b = store.labels["b"]
        store action {
            labels["a"] mutate "edited"
            base mutate "changed"
        }
        val fired = mutableListOf<String>()
        a effect { fired += "a=$this" }
        b effect { fired += "b=$this" }
        fired.clear()

        store.reset().getOrThrow()

        assertSame(a, store.labels["a"], "the reset kept the entry: the same State")
        assertEquals("base-a", a.value)
        assertEquals("base-b", b.value)
        assertEquals(listOf("a=base-a"), fired, "only the entry the reset changed fires")
        assertEquals(setOf("a", "b"), store.labels.entries.keys)
    }

    @Test fun anEntrysInitializerReadsTheResetValuesOfTheStoresStates() {
        val store = KrLabels()
        store action { base mutate "later" }
        val c = store.labels["c"]
        assertEquals("later-c", c.value, "created from the committed value")

        store.reset().getOrThrow()

        assertEquals("base", store.base.value)
        assertEquals("base-c", c.value, "re-run, it reads base's reset value, as in a fresh store")
    }

    @Test fun aResetSkipsTheEntriesItsTransactionEvicts() {
        val store = KrLabels()
        val a = store.labels["a"]
        val b = store.labels["b"]
        store action {
            // Not a's reset value: a reset that wrongly re-staged it would cancel the eviction.
            labels["a"] mutate "edited"
            labels["b"] mutate "edited"
        }

        store action {
            labels.evict("a")
            reset().getOrThrow()
        }

        assertFalse("a" in store.labels, "the reset did not cancel the eviction")
        assertEquals("base-b", b.value)
        assertNotSame(a, store.labels["a"], "a was retired; a get creates a new entry")
    }

    @Test fun aResetInsideAnActionRollsBackWithIt() {
        val store = KrLabels()
        val a = store.labels["a"]
        store action { labels["a"] mutate "edited" }

        val r =
            store action {
                reset().getOrThrow()
                error("the enclosing action fails")
            }

        assertIs<TransactionResult.Error>(r)
        assertEquals("edited", a.value)
    }

    @Test fun anAttachmentToldOfTheResetReadsTheEntriesResetValues() {
        val store = KrLabels()
        val attachment = store.internalAttachIfAbsent(krAttachmentKey) { KrReadingAttachment(store) }
        store action { labels["a"] mutate "edited" }

        store.reset().getOrThrow()

        assertEquals(listOf("base-a"), attachment.seen)
    }

    @Test fun aSterileRestoreResetsTheRemoteFamiliesEntriesAndRestoresTheRest() {
        val store = KrSynced()
        store action {
            remote["feed"] mutate 7
            local["draft"] mutate 3
            title mutate "saved"
        }
        val snapshot = store.snapshot()
        store action {
            remote["feed"] mutate 9
            remote["other"] mutate 1
            local["draft"] mutate 4
        }

        val report = store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()

        assertEquals(0, store.remote["feed"].value, "reset to its initial value, not the snapshot's 7")
        assertEquals(0, store.remote["other"].value)
        assertTrue("other" in store.remote, "a sterile restore never evicts")
        assertEquals(3, store.local["draft"].value, "a non-Remote family is restored")
        assertEquals(setOf("remote"), report.sterilized)
        assertTrue("local" in report.restored)
    }

    @Test fun anEntryAnInitializerCreatesDuringTheResetIsResetToo() {
        val store = KrSummary()
        assertEquals("base-z", store.summary.value)
        store.labels.evict("z")
        store action { base mutate "changed" }

        store.reset().getOrThrow()

        assertEquals("base", store.base.value)
        assertEquals("base-z", store.summary.value, "summary read the new entry's reset value")
        assertEquals("base-z", store.labels["z"].value, "z, created from committed values during the reset, was reset")
        val fresh = KrSummary()
        fresh.summary.value
        assertEquals(fresh.snapshot(), store.snapshot(), "the store holds what a fresh store's first reads give")
    }

    @Test fun anEntryAnInitializerOnlyGetsDuringTheResetIsResetToo() {
        val store = KrToucher()
        assertTrue(store.touched.value)
        store.labels.evict("z")
        store action { base mutate "changed" }

        store.reset().getOrThrow()

        assertEquals("base-z", store.labels["z"].value, "reset at the end of the pass, though nothing read it")
    }

    @Test fun aDeclaredStatesInitializerReadsALiveEntrysResetValue() {
        val store = KrSummary()
        store.summary.value
        store action {
            base mutate "changed"
            labels["z"] mutate "edited"
            summary mutate "other"
        }

        store.reset().getOrThrow()

        assertEquals("base-z", store.labels["z"].value)
        assertEquals("base-z", store.summary.value, "summary read z's reset value, computed from base's")
    }

    @Test fun anEntryChainResetsThroughItsLinksEvenAnEvictedOne() {
        val store = KrChain()
        store action { base mutate 10 }
        assertEquals(40, store.nodes[3].value)
        store.nodes.evict(1)

        store.reset().getOrThrow()

        assertEquals(1, store.base.value)
        assertEquals(listOf(1, 2, 3, 4), (0..3).map { store.nodes[it].value }, "every link reads its reset predecessor")
        val fresh = KrChain()
        fresh.nodes[3].value
        assertEquals(setOf(0, 1, 2, 3), store.snapshot().keysOf(store.nodes))
        assertEquals(
            (0..3).map { fresh.snapshot()[fresh.nodes[it]] },
            (0..3).map { store.snapshot()[store.nodes[it]] },
        )
    }

    @Test fun aSterileRestoreLeavesAnEntryItsTransactionEvictsToTheEviction() {
        val store = KrFeed()
        store action { feed["a"] mutate 7 }
        assertEquals(8, store.feed["b"].value)
        val snapshot = store.snapshot()

        store action {
            feed.evict("a")
            restore(snapshot, RestorePolicy.BestEffort, sterile = true).getOrThrow()
        }

        assertFalse("a" in store.feed, "the sterile restore did not cancel the eviction")
        assertEquals(8, store.feed["b"].value, "b's reset read the evicted a at its committed value")
    }

    @Test fun aSterileRestoreResetsAnEntryItBringsToLifeFromTheRestoredValues() {
        val source = KrSterile()
        source action { base mutate "restored" }
        // UserAuthored: base only — no entry, and the Remote state is never read.
        val snapshot = source.snapshot(SnapshotScope.UserAuthored)
        val store = KrSterile()
        assertEquals("none", store.remote.value)

        store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()

        assertEquals("restored", store.base.value)
        assertEquals("restored-z", store.remote.value, "the Remote state read z computed from the restored base")
        assertEquals("restored-z", store.labels["z"].value, "z, brought to life by the restore, was recomputed")
    }

    @Test fun aSterileRestoreOfDecodedTextNeverCreatesARemoteEntry() {
        val source = KrSynced()
        source action { remote["feed"] mutate 7 }
        val text = source.snapshot().encode(includeRemote = true)

        val fresh = KrSynced()
        fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict, sterile = true).getOrThrow()

        assertTrue(fresh.remote.entries.isEmpty(), "the snapshot's Remote entries are dropped")
    }
}
