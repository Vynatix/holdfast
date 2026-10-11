@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #20, R7 acceptance (snapshots): every live entry appears in
// snapshot() under its family and key, and restores into a fresh store —
// from memory, and through encode()/decode() with a keyCodec.

private class KsnDocs : Store<KsnDocs>() {
    val title by state(codec = StringCodec) { "t" }
    val docs by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { id -> "draft $id" }
    val sizes by keyedState<Int, Int>(codec = IntCodec, keyCodec = IntCodec) { it }
    val cache by keyedState<String, Int> { 0 }
    val keyless by keyedState<String, Int>(codec = IntCodec) { 0 }
}

private class KsnTagged : Store<KsnTagged>() {
    val tokens by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val feed by keyedState<String, Int>(codec = IntCodec, keyCodec = StringCodec, tags = setOf(StateTag.Remote)) { -1 }
    val pins by keyedState<String, Int>(codec = IntCodec, keyCodec = StringCodec, tags = setOf(StateTag.UserAuthored)) { 0 }
}

/** Declares a plain state where [KsnDocs] declares the family `docs`, and a family where it declares `title`. */
private class KsnSwapped : Store<KsnSwapped>() {
    val docs by state(codec = StringCodec) { "" }
    val title by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }
}

/** A family whose value codec calls [hook] with every text it decodes. */
private class KsnHooked : Store<KsnHooked>() {
    var hook: (String) -> Unit = {}
    val docs by keyedState<String, String>(codec = KsnHookCodec(this), keyCodec = StringCodec) { "" }
}

private class KsnHookCodec(
    private val store: KsnHooked,
) : StateCodec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String {
        store.hook(string)
        return string
    }
}

/** Encodes keys in lower case: "Key" and "key" collide. */
private object KsnLower : StateCodec<String> {
    override fun encode(value: String): String = value.lowercase()

    override fun decode(string: String): String = string
}

/** A codec whose failure quotes the value, as a careless one would. */
private object KsnQuoting : StateCodec<String> {
    override fun encode(value: String): String = throw IllegalArgumentException("cannot encode $value")

    override fun decode(string: String): String = string
}

private class KsnColliding : Store<KsnColliding>() {
    val names by keyedState<String, String>(codec = StringCodec, keyCodec = KsnLower) { "" }
}

private class KsnQuotingValues : Store<KsnQuotingValues>() {
    val notes by keyedState<String, String>(codec = KsnQuoting, keyCodec = StringCodec) { "" }
}

private class KsnQuotingKeys : Store<KsnQuotingKeys>() {
    val notes by keyedState<String, String>(codec = StringCodec, keyCodec = KsnQuoting) { "" }
}

private class KsnSecretQuoting : Store<KsnSecretQuoting>() {
    val notes by keyedState<String, String>(codec = KsnQuoting, keyCodec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
}

/** A family with a keyCodec but no codec for its values. */
private class KsnKeyOnly : Store<KsnKeyOnly>() {
    val keyOnly by keyedState<String, Int>(keyCodec = StringCodec) { 0 }
    val keyless by keyedState<String, Int>(codec = IntCodec) { 0 }
}

private fun familyText(states: String): String = """{"format":"holdfast.store","v":1,"schema":1,"states":{$states},"skipped":[]}"""

private fun KsnDocs.fill() {
    this action {
        docs["b"] mutate "B"
        docs["a"] mutate "A"
        sizes[2] mutate 20
        cache["x"] mutate 1
    }
}

class KeyedStateSnapshotTest {
    @Test fun everyKeyAppearsInTheSnapshotAndRestoresIntoAFreshStore() {
        val store = KsnDocs()
        store.fill()
        val snapshot = store.snapshot()

        assertEquals(setOf("b", "a"), snapshot.keysOf(store.docs), "every live entry, under its key")
        assertEquals("B", snapshot[store.docs["b"]])
        assertEquals(setOf(2), snapshot.keysOf(store.sizes))
        assertTrue("docs" in snapshot.stateNames, "a family is listed under its name")

        val fresh = KsnDocs()
        fresh.restore(snapshot).getOrThrow()
        val restored = fresh.docs.entries
        assertEquals(listOf("b", "a"), restored.keys.toList(), "the restore created each entry")
        assertEquals(listOf("B", "A"), restored.values.map { it.value })
        assertEquals(20, fresh.sizes[2].value)
        assertEquals(1, fresh.cache["x"].value, "a captured snapshot restores families without codecs too")
        assertEquals(snapshot, fresh.snapshot(), "equal by value, families included")
    }

    @Test fun theEncodingWritesEachFamilyAsAnObjectUnderItsName() {
        val store = KsnDocs()
        store.fill()
        store.keyless["k"]

        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"docs":{"a":"A","b":"B"},"sizes":{"2":"20"},""" +
                """"title":"t"},"skipped":["cache","keyless"]}""",
            store.snapshot().encode(),
        )
        assertEquals(setOf("cache", "keyless"), store.snapshot().unencodableStateNames)
    }

    @Test fun aDecodedSnapshotRoundTripsThroughTheKeyCodec() {
        val store = KsnDocs()
        store.fill()
        val snapshot = store.snapshot()
        val decoded = StoreSnapshot.decode(snapshot.encode())

        assertEquals(setOf("a", "b"), decoded.keysOf(store.docs))
        assertEquals(setOf(2), decoded.keysOf(store.sizes))
        assertEquals("A", decoded[store.docs["a"]])
        assertEquals(20, decoded[store.sizes[2]])
        assertTrue(decoded.equalsEncodable(snapshot))
        assertEquals(snapshot.encode(), decoded.encode(), "a decoded family is written back as read")

        val fresh = KsnDocs()
        val report = fresh.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals(setOf("docs", "sizes", "title"), report.restored)
        assertEquals(setOf("cache", "keyless"), report.kept)
        assertEquals(setOf("a", "b"), fresh.docs.entries.keys)
        assertEquals("B", fresh.docs["b"].value)
        assertTrue(fresh.snapshot().equalsEncodable(snapshot))
    }

    @Test fun typedReadsGoThroughTheEntrysFamilyAndKey() {
        val store = KsnDocs()
        store.fill()
        val snapshot = store.snapshot()
        val decoded = StoreSnapshot.decode(snapshot.encode())
        val later = store.docs["later"]

        assertEquals(SnapshotEntry.Absent, snapshot.entry(later), "created after the capture")
        assertEquals(SnapshotEntry.Absent, decoded.entry(later))
        assertFailsWith<IllegalArgumentException> { snapshot[KsnDocs().docs["a"]] }
        assertFailsWith<IllegalArgumentException> { snapshot.keysOf(KsnDocs().docs) }
        assertEquals("A", decoded[KsnDocs().docs["a"]], "a decoded snapshot answers any store's family by name")
        assertEquals(emptySet(), decoded.keysOf(store.cache), "an unencodable family holds no entries")

        val noKeyCodec =
            StoreSnapshot.decode(
                """{"format":"holdfast.store","v":1,"schema":1,"states":{"keyless":{"k":"1"}},"skipped":[]}""",
            )
        assertContains(assertFailsWith<IllegalStateException> { noKeyCodec.keysOf(store.keyless) }.message.orEmpty(), "no keyCodec")
        val noKeys = StoreSnapshot.decode(familyText(""""keyless":{}"""))
        assertEquals(emptySet(), noKeys.keysOf(store.keyless), "no key to decode: no keyCodec needed")
        val single = StoreSnapshot.decode("""{"format":"holdfast.store","v":1,"schema":1,"states":{"docs":"x"},"skipped":[]}""")
        assertFailsWith<SnapshotFormatException> { single.keysOf(store.docs) }
        assertFailsWith<SnapshotFormatException> { single[store.docs["a"]] }
    }

    @Test fun keysAndEntryReadsKeepAnsweringAfterTheStoreIsDisposed() {
        val store = KsnDocs()
        store.fill()
        val family = store.docs
        val entry = store.docs["a"]
        val snapshot = store.snapshot()
        val decoded = StoreSnapshot.decode(snapshot.encode())

        store.dispose()

        assertEquals(setOf("b", "a"), snapshot.keysOf(family))
        assertEquals("A", snapshot[entry])
        assertEquals(setOf("a", "b"), decoded.keysOf(family))
        assertEquals("A", decoded[entry])
    }

    @Test fun aCaptureListsAgainOnlyWhenAFamilyItCapturesGrew() {
        val store = KsnTagged()
        store.pins["a"]
        val authored = store.listCapture(SnapshotScope.UserAuthored, 1)
        val all = store.listCapture(SnapshotScope.All, 1)

        store.feed["x"] // a family the UserAuthored capture does not hold grows…
        store action { feed["x"] mutate 1 } // …and a commit applies

        assertTrue(authored.membership.committedSince())
        assertFalse(authored.membership.grewSince(), "growth outside the captured families does not count")
        assertTrue(all.membership.grewSince())
        store.pins["b"]
        assertTrue(authored.membership.grewSince(), "a captured family grew")
    }

    @Test fun aSecretFamilysValuesAreWithheldButItsKeysAreNot() {
        val store = KsnTagged()
        store action { tokens["github"] mutate "hunter2" }

        val all = store.snapshot()
        assertEquals(Redacted, all.entry(store.tokens["github"]))
        assertEquals("hunter2", store.snapshot(SnapshotScope.Raw)[store.tokens["github"]])
        val text = all.encode()
        assertContains(text, """"tokens":{"github":null}""")
        assertFalse("hunter2" in text)
        assertFalse("hunter2" in all.render())
        assertContains(all.render(), "[github] = <redacted>")

        val fresh = KsnTagged()
        fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals("", fresh.tokens["github"].value, "a withheld value restores its entry, not its value")
        fresh.restore(all).getOrThrow()
        assertEquals("hunter2", fresh.tokens["github"].value, "a captured snapshot holds the raw value: undo is lossless")
    }

    @Test fun aRemoteFamilyIsLeftOutOfTheEncodingUnlessAskedFor() {
        val store = KsnTagged()
        store action { feed["x"] mutate 5 }

        assertFalse("feed" in store.snapshot().encode())
        assertContains(store.snapshot().encode(includeRemote = true), """"feed":{"x":"5"}""")
    }

    @Test fun theUserAuthoredScopeCapturesExactlyTheUserAuthoredFamilies() {
        val store = KsnTagged()
        store action {
            pins["a"] mutate 1
            feed["b"] mutate 2
        }

        val authored = store.snapshot(SnapshotScope.UserAuthored)
        assertEquals(setOf("pins"), authored.stateNames)
        assertEquals(setOf("a"), authored.keysOf(store.pins))
        assertEquals(emptySet(), authored.keysOf(store.feed))
    }

    @Test fun aRestoreNeverEvictsButEvictAllFirstMakesAnExactUndo() {
        val store = KsnDocs()
        store action { docs["a"] mutate "A" }
        val before = store.snapshot()
        store action {
            docs["a"] mutate "changed"
            docs["new"] mutate "N"
        }

        store.restore(before).getOrThrow()
        assertEquals("A", store.docs["a"].value)
        assertEquals("N", store.docs.getOrNull("new")?.value, "a restore never evicts")

        store action {
            docs.evictAll()
            restore(before).getOrThrow()
        }
        assertEquals(setOf("a"), store.docs.entries.keys, "the entries the snapshot holds survive, the rest go")
        assertEquals(before, store.snapshot())
    }

    @Test fun theRestorePolicyAppliesToEachEntry() {
        val store = KsnDocs()
        store.sizes[1]
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"sizes":{"1":"10","2":"oops","x":"3"}},"skipped":[]}"""
        val decoded = StoreSnapshot.decode(text)

        val strict = store.restore(decoded, RestorePolicy.Strict)
        val rejected = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(strict).exception)
        assertEquals(listOf("sizes", "sizes"), rejected.issues.map { it.stateName })
        assertFalse("oops" in rejected.message.orEmpty(), "never quotes a value")
        assertEquals(
            mapOf(1 to 1, 2 to 2),
            store.sizes.entries.mapValues { it.value.value },
            "nothing was restored; the plan created entry 2 at its initial value and keeps it, the undecodable key none",
        )

        val report = store.restore(decoded, RestorePolicy.BestEffort).getOrThrow()
        assertEquals(10, store.sizes[1].value, "BestEffort restores the entries that decode")
        assertEquals(2, report.issues.size)
        assertTrue(report.issues.all { it is RestoreIssue.Undecodable && it.stateName == "sizes" })
        assertTrue("sizes" in report.restored)
    }

    @Test fun anEntryEvictedBetweenThePlanAndTheRestoreIsCreatedAgain() {
        val store = KsnHooked()
        val old = store.docs["a"]
        var evicted = false
        // Decoding b's value runs after a was planned: evict a then, in an action of its own.
        store.hook = { text ->
            if (text == "B" && !evicted) {
                evicted = true
                store action { docs.evict("a") }
            }
        }

        store.restore(StoreSnapshot.decode(familyText(""""docs":{"a":"A","b":"B"}""")), RestorePolicy.Strict).getOrThrow()

        assertTrue(evicted)
        assertEquals("A", store.docs.getOrNull("a")?.value, "the value lands in a new entry")
        assertNotSame(old, store.docs.getOrNull("a"))
        assertFailsWith<IllegalStateException> { store { old mutate "x" } }
        assertEquals(setOf("a", "b"), store.docs.entries.keys)
    }

    @Test fun twoKeysEncodingToOneTextFailTheEncodeNamingOnlyTheFamily() {
        val store = KsnColliding()
        store.names["Key-5ECRET"]
        store.names["key-5ecret"]

        val failure = assertFailsWith<IllegalStateException> { store.snapshot().encode() }
        val message = failure.message.orEmpty()
        assertContains(message, "keyed state family 'names'")
        assertContains(message, "encode to the same text")
        assertFalse("5ecret" in message.lowercase(), message)
    }

    @Test fun aThrowingCodecFailsTheEncodeWithoutQuotingOrAttachingIt() {
        val values = KsnQuotingValues()
        values action { notes["key-5ECRET"] mutate "value-5ECRET" }
        val keys = KsnQuotingKeys()
        keys action { notes["key-5ECRET"] mutate "value-5ECRET" }

        for ((failure, codec) in listOf(
            assertFailsWith<IllegalStateException> { values.snapshot().encode() } to "its codec",
            assertFailsWith<IllegalStateException> { keys.snapshot().encode() } to "its keyCodec",
        )) {
            val message = failure.message.orEmpty()
            assertContains(message, "$codec threw IllegalArgumentException")
            assertNull(failure.cause, "the codec's exception, which may quote the value, is not attached")
            assertFalse("5ECRET" in message, message)
        }

        val secret = KsnSecretQuoting()
        secret action { notes["k"] mutate "value-5ECRET" }
        assertContains(secret.snapshot().encode(), """"notes":{"k":null}""", message = "a Secret value never reaches its codec")
    }

    @Test fun aKeyCodecThatCannotDecodeAKeyFailsKeysOfWithoutQuotingIt() {
        val decoded = StoreSnapshot.decode(familyText(""""sizes":{"notanumber42":"1"}"""))

        val failure = assertFailsWith<SnapshotFormatException> { decoded.keysOf(KsnDocs().sizes) }

        assertFalse("notanumber42" in failure.message.orEmpty(), failure.message)
        assertNull(failure.cause)
    }

    @Test fun aDecodedFamilyWithoutAKeyCodecOrACodecIsAnIssueNamingTheFamily() {
        val store = KsnKeyOnly()

        val keyless = store.restore(StoreSnapshot.decode(familyText(""""keyless":{"k":"1"}""")), RestorePolicy.BestEffort)
        val keylessIssue = assertIs<RestoreIssue.Undecodable>(keyless.getOrThrow().issues.single())
        assertEquals("keyless", keylessIssue.stateName)
        assertContains(keylessIssue.reason, "no keyCodec")
        assertTrue(store.keyless.entries.isEmpty(), "no entry was created")

        val keyOnly = store.restore(StoreSnapshot.decode(familyText(""""keyOnly":{"k":"1"}""")), RestorePolicy.BestEffort)
        assertEquals(listOf<RestoreIssue>(RestoreIssue.NoCodec("keyOnly")), keyOnly.getOrThrow().issues)
        assertTrue(store.keyOnly.entries.isEmpty(), "no entry was created")

        val withheld = store.restore(StoreSnapshot.decode(familyText(""""keyOnly":{"k":null}""")), RestorePolicy.BestEffort)
        assertEquals(emptyList(), withheld.getOrThrow().issues, "a withheld value needs no codec")
        assertEquals(0, store.keyOnly.getOrNull("k")?.value, "…and creates its entry")

        for (states in listOf(""""keyless":{"k":"1"}""", """"keyOnly":{"j":"1"}""")) {
            val strict = store.restore(StoreSnapshot.decode(familyText(states)), RestorePolicy.Strict)
            val rejected = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(strict).exception)
            assertEquals(1, rejected.issues.size)
            assertTrue(rejected.issues.single().stateName in setOf("keyless", "keyOnly"))
            assertFalse("\"k\"" in rejected.message.orEmpty() || "\"j\"" in rejected.message.orEmpty())
        }
        assertEquals(setOf("k"), store.keyOnly.entries.keys, "a rejected restore created no entry for j")
        assertTrue(store.keyless.entries.isEmpty())
    }

    @Test fun aFamilyAndAStateOfOneNameDoNotRestoreIntoEachOther() {
        val source = KsnDocs()
        source.fill()
        val decoded = StoreSnapshot.decode(source.snapshot().encode())
        val swapped = KsnSwapped()

        val report = swapped.restore(decoded, RestorePolicy.BestEffort).getOrThrow()
        val byName = report.issues.associateBy { it.stateName }
        assertIs<RestoreIssue.Undecodable>(byName["docs"], "the snapshot's family is not the store's state")
        assertIs<RestoreIssue.Undecodable>(byName["title"], "the snapshot's state is not the store's family")
        assertEquals("", swapped.docs.value)
        assertTrue(swapped.title.entries.isEmpty())
        assertIs<TransactionResult.Error>(swapped.restore(decoded), "IgnoreUnknown refuses a mismatch")
    }

    @Test fun capturedSnapshotsCompareFamiliesByValue() {
        val one = KsnDocs()
        val two = KsnDocs()
        one action { docs["a"] mutate "same" }
        two action { docs["a"] mutate "same" }

        assertEquals(one.snapshot(), two.snapshot())
        assertEquals(one.snapshot().hashCode(), two.snapshot().hashCode())
        two action { docs["b"] mutate "more" }
        assertNotEquals(one.snapshot(), two.snapshot())
    }

    @Test fun anEvictionAndTheWritesOfItsCommitAreInTheSameSnapshots() {
        val store = KsnDocs()
        store.docs["gone"]
        val before = store.snapshot()

        store action {
            docs.evict("gone")
            title mutate "after"
        }
        val after = store.snapshot()

        assertEquals(setOf("gone"), before.keysOf(store.docs))
        assertEquals("t", StoreSnapshot.decode(before.encode())[store.title])
        assertEquals(emptySet(), after.keysOf(store.docs))
        assertEquals("after", after[store.title])
    }

    @Test fun renderListsEachEntryAndToStringNeverShowsAKey() {
        val store = KsnDocs()
        store action { docs["user@example.com"] mutate "hello" }
        val snapshot = store.snapshot()

        assertContains(snapshot.render(), "docs = keyed state family (1 entries)\n    [user@example.com] = hello")
        assertContains(StoreSnapshot.decode(snapshot.encode()).render(), "[\"user@example.com\"] = \"hello\"")
        assertFalse("user@example.com" in snapshot.toString())
        assertNull(snapshot.keysOf(store.sizes).firstOrNull())
    }
}
