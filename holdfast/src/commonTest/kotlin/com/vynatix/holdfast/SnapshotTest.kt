package com.vynatix.holdfast

import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val SEED = "snap-test-seed".encodeToByteArray()

private class SnapshotVault : Store<SnapshotVault>() {
    val n by state { 0 }
    val s by state { "init" }
    val items by state { emptyList<String>() }
}

private class FailingInitStore(
    var failInit: Boolean,
) : Store<FailingInitStore>() {
    val ok by state { 0 }
    val fragile by state {
        check(!failInit) { "initializer fails" }
        1
    }
}

/** `b`'s initializer reads `a`. */
private class ChainedInitStore : Store<ChainedInitStore>() {
    val a by state { 0 }
    val b by state { a.value + 1 }
}

private class FrameSourceStore : Store<FrameSourceStore>() {
    val x by state { 0 }
}

/** `y`'s initializer reads another store's state. */
private class FrameReaderStore(
    private val source: FrameSourceStore,
) : Store<FrameReaderStore>() {
    val y by state { source.x.value + 1 }
}

private class CryptoVault : Store<CryptoVault>() {
    val token by state(EncryptingTransformer(XorCipher(SEED))) { "" }
    val plain by state { "p" }
}

class SnapshotCaptureTest {
    @Test fun snapshotContainsCurrentRawValues() {
        val v = SnapshotVault()
        v action {
            n mutate 5
            s mutate "hello"
            items mutate listOf("a", "b")
        }
        val snap = v.snapshot()
        assertEquals(setOf("n", "s", "items"), snap.stateNames)
        assertEquals(5, snap.rawValues["n"])
        assertEquals("hello", snap.rawValues["s"])
        assertEquals(listOf("a", "b"), snap.rawValues["items"])
    }

    @Test fun snapshotIsDetachedFromSubsequentMutations() {
        val v = SnapshotVault()
        v action { n mutate 1 }
        val snap = v.snapshot()
        v action { n mutate 99 }
        assertEquals(1, snap.rawValues["n"], "snapshot pinned the captured value")
    }

    @Test fun untouchedDeclaredStatesAreCaptured() {
        val v = SnapshotVault()
        v.n // touch only n
        val snap = v.snapshot()
        assertEquals(setOf("n", "s", "items"), snap.stateNames, "declared states are captured, read or not")
        assertEquals("init", snap.rawValues["s"], "a never-read state is captured at its initial value")
        assertEquals(emptyList<String>(), snap.rawValues["items"])
    }

    @Test fun snapshotInsideAnActionMaterializesFromCommittedValues() {
        val v = ChainedInitStore()
        v.a // a is live; b has never been read
        var snap: StoreSnapshot? = null
        val r =
            v action {
                a mutate 5
                snap = snapshot()
                error("abort")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(
            mapOf("a" to 0, "b" to 1),
            snap?.rawValues,
            "one committed cut: b's initializer read the committed a, not the pending 5",
        )
        assertEquals(0, v.a.value)
        assertEquals(1, v.b.value, "the rolled-back write never reached b's initial value")
    }

    @Test fun snapshotInsideAFrameMaterializesFromAnotherStoresCommittedValue() {
        val source = FrameSourceStore()
        val reader = FrameReaderStore(source)
        var snap: StoreSnapshot? = null
        val r =
            atomic(reader, source) {
                source.action { x mutate 5 }
                snap = reader.snapshot()
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(mapOf("y" to 1), snap?.rawValues, "y's initializer read source's committed x, not the frame's 5")
        assertEquals(5, source.x.value)
        assertEquals(1, reader.y.value)
    }

    @Test fun derivedBackingStatesAreHiddenFromStateNames() {
        val v = SnapshotVault()
        val (doubled, d) = v.derived(v.n) { n.value * 2 }
        try {
            val snap = v.snapshot()
            assertEquals(setOf("n", "s", "items"), snap.stateNames, "a derived's backing state is not a declared state")
            assertEquals(3, snap.size)
            assertEquals(0, doubled.value)
        } finally {
            d.dispose()
        }
    }
}

class SnapshotRestoreTest {
    @Test fun restoreAppliesAllValuesAtomically() {
        val v = SnapshotVault()
        v action {
            n mutate 5
            s mutate "captured"
            items mutate listOf("x")
        }
        val snap = v.snapshot()

        // Mutate further.
        v action {
            n mutate 100
            s mutate "later"
            items mutate listOf("y", "z")
        }

        val r = v.restore(snap)
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(5, v.n.value)
        assertEquals("captured", v.s.value)
        assertEquals(listOf("x"), v.items.value)
    }

    @Test fun restoreFiresObserversOnceForEachChangedState() {
        val v = SnapshotVault()
        v action {
            n mutate 1
            s mutate "a"
        }
        val snap = v.snapshot()
        v action {
            n mutate 99
            s mutate "z"
        }

        val nSeen = mutableListOf<Int>()
        val sSeen = mutableListOf<String>()
        val sub1 = v { n effect { nSeen.add(this) } }
        val sub2 = v { s effect { sSeen.add(this) } }
        nSeen.clear()
        sSeen.clear()

        v.restore(snap)

        assertEquals(listOf(1), nSeen, "observer fires once with restored value")
        assertEquals(listOf("a"), sSeen)
        sub1.dispose()
        sub2.dispose()
    }

    @Test fun restoreOfUnknownStateNameRollsBack() {
        val v = SnapshotVault()
        val foreign = StoreSnapshot(mapOf("not-here" to 42))
        val r = v.restore(foreign)
        assertIs<TransactionResult.Error>(r)
        assertEquals(0, v.n.value, "no states changed; transaction rolled back")
    }

    @Test fun restoreRoundTripsAsymmetricTransformerWithoutDoubleEncrypting() {
        val v = CryptoVault()
        v action {
            token mutate "secret-1"
            plain mutate "p1"
        }
        // Capture the encrypted ciphertext.
        val snap = v.snapshot()
        val ciphertext = snap.rawValues["token"]
        assertTrue(ciphertext is String && ciphertext != "secret-1", "snapshot pinned ciphertext, not plaintext")

        v action {
            token mutate "secret-2"
            plain mutate "p2"
        }
        v.restore(snap)

        assertEquals("secret-1", v.token.value, "decrypt-after-restore yields the original plaintext")
        assertEquals("p1", v.plain.value)
    }

    @Test fun snapshotAndRestoreOnSeparateButIdenticallyShapedVaults() {
        val a = SnapshotVault()
        a action {
            n mutate 7
            s mutate "from-a"
        }
        val snap = a.snapshot()

        val b = SnapshotVault()
        // Restore into states that are already live. (Touching them first is
        // not required: see restoreIntoAnUntouchedStoreMaterializesItsTargets.)
        b.n
        b.s
        b.items
        val r = b.restore(snap)
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(7, b.n.value)
        assertEquals("from-a", b.s.value)
    }

    @Test fun restoreIntoAnUntouchedStoreMaterializesItsTargets() {
        val a = SnapshotVault()
        a action {
            n mutate 7
            s mutate "from-a"
        }
        val snap = a.snapshot()

        // No touch: restore materializes every declared target itself.
        val b = SnapshotVault()
        val r = b.restore(snap)
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(7, b.n.value)
        assertEquals("from-a", b.s.value)
        assertEquals(emptyList<String>(), b.items.value)
    }

    @Test fun aFailingTargetInitializerRollsTheRestoreBack() {
        val source = FailingInitStore(failInit = false)
        source action { ok mutate 5 }
        val snap = source.snapshot()

        val target = FailingInitStore(failInit = true)
        val r = target.restore(snap)
        assertIs<TransactionResult.Error>(r, "the initializer runs inside the restore's action")
        assertEquals("initializer fails", r.exception.message)
        assertEquals(0, target.ok.value, "nothing was staged")

        target.failInit = false
        assertIs<TransactionResult.Success<Unit>>(target.restore(snap))
        assertEquals(5, target.ok.value)
    }

    @Test fun restoreTargetInitializersNeverSeeTheRestoresOwnWrites() {
        // Untouched store: restore materializes a and b itself, and the unknown
        // name fails the restore after both are materialized. b's initializer
        // must read the committed a = 0, never the a = 5 this restore stages.
        val v = ChainedInitStore()
        val r = v.restore(StoreSnapshot(mapOf("a" to 5, "b" to 7, "ghost" to 0)))
        assertIs<TransactionResult.Error>(r)
        assertEquals(1, v.b.value, "b was seeded from the committed a")
        assertEquals(0, v.a.value, "the failed restore rolled back")
    }

    @Test fun restoreNestedInAnActionMaterializesFromCommittedValues() {
        val v = ChainedInitStore()
        v.a // a is live; b has never been read
        val r =
            v action {
                a mutate 5
                val inner = restore(StoreSnapshot(mapOf("b" to 9)))
                check(inner is TransactionResult.Success) { "the nested restore failed: $inner" }
                error("abort")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals("abort", r.exception.message)
        assertEquals(0, v.a.value)
        assertEquals(
            1,
            v.b.value,
            "b's initializer ran in the nested restore and read the committed a; the rollback dropped b = 9",
        )
    }

    @Test fun sameInstanceUndoRestoresTheDerived() {
        val v = SnapshotVault()
        val (doubled, d) = v.derived(v.n) { n.value * 2 }
        try {
            v action { n mutate 2 }
            val snap = v.snapshot()
            v action { n mutate 5 }
            assertEquals(10, doubled.value)
            // What an observer of the source sees of the derived during the
            // restore's own commit: the backing state is restored in that
            // same commit, not only by the recompute that follows it.
            val seenInRestoreCommit = mutableListOf<Int>()
            val sub = v.n effect { seenInRestoreCommit += doubled.value }
            seenInRestoreCommit.clear()

            assertIs<TransactionResult.Success<Unit>>(v.restore(snap))
            sub.dispose()
            assertEquals(2, v.n.value)
            assertEquals(4, doubled.value, "the derived is back at its captured value")
            assertEquals(listOf(4), seenInRestoreCommit, "restored together with its source, in one commit")
        } finally {
            d.dispose()
        }
    }

    @Test fun anUndoSkipsADerivedBackingStateDroppedSinceTheSnapshot() {
        val v = SnapshotVault()
        val (_, d) = v.derived(v.n) { n.value * 2 }
        try {
            v action { n mutate 2 }
            val snap = v.snapshot()
            v.clearStates() // drops the backing state along with its declaration

            assertIs<TransactionResult.Success<Unit>>(v.restore(snap))
            assertEquals(2, v.n.value, "the declared states are restored")
            assertEquals(setOf("n", "s", "items"), v.properties.keys, "the dropped backing state was not revived")
        } finally {
            d.dispose()
        }
    }

    @Test fun restoringIntoAnotherInstanceSkipsDerivedBackingStates() {
        val a = SnapshotVault()
        val (_, da) = a.derived(a.n) { n.value * 2 }
        val b = SnapshotVault()
        val (doubledB, db) = b.derived(b.n) { n.value * 2 }
        try {
            a action { n mutate 3 }
            // a's backing state has a name b does not declare; it must not
            // make the restore fail, and b's own derived follows b's source.
            val r = b.restore(a.snapshot())
            assertIs<TransactionResult.Success<Unit>>(r)
            assertEquals(3, b.n.value)
            assertEquals(6, doubledB.value)
        } finally {
            da.dispose()
            db.dispose()
        }
    }
}
