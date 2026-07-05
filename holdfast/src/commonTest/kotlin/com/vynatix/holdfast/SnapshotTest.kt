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
        assertEquals(5, snap.entries["n"]?.rawValue)
        assertEquals("hello", snap.entries["s"]?.rawValue)
        assertEquals(listOf("a", "b"), snap.entries["items"]?.rawValue)
    }

    @Test fun snapshotIsDetachedFromSubsequentMutations() {
        val v = SnapshotVault()
        v action { n mutate 1 }
        val snap = v.snapshot()
        v action { n mutate 99 }
        assertEquals(1, snap.entries["n"]?.rawValue, "snapshot pinned the captured value")
    }

    @Test fun untouchedStatesAreAbsentFromSnapshot() {
        val v = SnapshotVault()
        v.n // touch only n
        val snap = v.snapshot()
        assertEquals(setOf("n"), snap.stateNames)
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
        val foreign = StoreSnapshot(mapOf("not-here" to SnapshotEntry(42, Int::class)))
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
        val ciphertext = snap.entries["token"]?.rawValue
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
        // Touch the states b will receive — restore matches by name and the
        // by-state delegate is lazy. Without a touch the property hasn't
        // been registered yet, so getState(name) returns null.
        b.n
        b.s
        b.items
        val r = b.restore(snap)
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(7, b.n.value)
        assertEquals("from-a", b.s.value)
    }

    @Test fun restoreOfTypeMismatchedStateFailsNamingTheStateAndMutatesNothing() {
        // F11: a cross-store restore where a same-named state holds an incompatible
        // type is rejected before staging — Error names the state, nothing mutates,
        // no observer fires.
        val a = TypeAVault()
        a action { field mutate 123 }
        val snap = a.snapshot()

        val b = TypeBVault()
        b.field // register
        b action { field mutate "keep" }
        val seen = mutableListOf<String>()
        val sub = b { field effect { seen.add(this) } }
        seen.clear()

        val r = b.restore(snap)
        val err = assertIs<TransactionResult.Error>(r)
        assertTrue(err.exception.message?.contains("field") == true, "message names the state")
        assertEquals("keep", b.field.value, "destination unchanged after rejected restore")
        assertEquals(emptyList(), seen, "no observer fired for the rolled-back restore")
        sub.dispose()
    }

    @Test fun restoreWithValidateTypesFalseAllowsPolymorphicSubtypeChange() {
        // F11: opt-out lets a polymorphic state accept a different subtype.
        val a = ShapeVault()
        a action { shape mutate Circle(2.0) }
        val snap = a.snapshot()

        val b = ShapeVault()
        b.shape
        b action { shape mutate Square(1.0) }

        val strict = b.restore(snap)
        assertIs<TransactionResult.Error>(strict, "strict restore rejects Circle into a Square-holding state")
        assertTrue(b.shape.value is Square, "strict rejection left the Square in place")

        val lenient = b.restore(snap, validateTypes = false)
        assertIs<TransactionResult.Success<Unit>>(lenient)
        assertTrue(b.shape.value is Circle, "validateTypes=false restored the Circle")
    }
}

private class TypeAVault : Store<TypeAVault>() {
    val field by state { 0 }
}

private class TypeBVault : Store<TypeBVault>() {
    val field by state { "" }
}

private sealed interface Shape

private data class Circle(
    val r: Double,
) : Shape

private data class Square(
    val side: Double,
) : Shape

private class ShapeVault : Store<ShapeVault>() {
    val shape by state<Shape> { Circle(1.0) }
}
