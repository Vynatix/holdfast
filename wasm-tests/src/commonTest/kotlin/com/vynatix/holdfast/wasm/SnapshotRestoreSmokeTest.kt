@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.RestoreIssue
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.RestoreRejectedException
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.SnapshotMigrationException
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.reset
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class SnapshotRestoreCounter : Store<SnapshotRestoreCounter>() {
    var countRuns = 0
    var labelRuns = 0
    val count by state(codec = IntCodec) {
        countRuns++
        1
    }
    val label by state(codec = StringCodec) {
        labelRuns++
        "n=${count.value}"
    }

    /** No codec: captured in memory, listed as skipped by encode(). */
    val draft by state { "" }
}

private class SnapshotRestoreDoubling : Store<SnapshotRestoreDoubling>() {
    val count by state(codec = IntCodec) { 1 }
    val doubled by derivedState(count) { count.value * 2 }
}

private class SnapshotRestoreAccount : Store<SnapshotRestoreAccount>() {
    val balance by state(codec = IntCodec) { 100 }
}

private interface SnapshotRestoreShape

private class SnapshotRestoreShapes : Store<SnapshotRestoreShapes>() {
    val count by state { 1 }
    val shape by state<Any> { object : SnapshotRestoreShape {} }
}

/** Declares `count` and `shape` with values of other classes than [SnapshotRestoreShapes] does. */
private class SnapshotRestoreMismatched : Store<SnapshotRestoreMismatched>() {
    val count by state { "one" }
    val shape by state<Any> { 0 }
}

private class SnapshotRestoreResettable : Store<SnapshotRestoreResettable>() {
    val a by state { 1 }
    val b by state<Int> { a.value + 1 }
    val c by state { "same" }
    val docs by keyedState<String, Int> { key -> key.length }
}

private class SnapshotRestoreFragile : Store<SnapshotRestoreFragile>() {
    var cycle = false
    var fail = false
    val a: State<Int> by state { b.value + 1 }
    val b: State<Int> by state { if (cycle) a.value else 0 }
    val c by state<Int> {
        check(!fail) { "c refuses" }
        0
    }
}

private class SnapshotRestoreReaderV1 : Store<SnapshotRestoreReaderV1>() {
    val theme by state(codec = StringCodec) { "light" }
    val fontSize by state(codec = IntCodec) { 14 }
}

private class SnapshotRestoreReaderV2(
    private val onMigrate: SnapshotRestoreReaderV2.(Int, EncodedSnapshotView) -> Unit = { from, view ->
        if (from < 2) view.rename("fontSize", "textSize")
    },
) : Store<SnapshotRestoreReaderV2>(),
    SchemaVersioned {
    val theme by state(codec = StringCodec) { "light" }
    val textSize by state(codec = IntCodec) { 14 }
    val migrations = mutableListOf<String>()

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        migrations += "from=$from, txn=${activeTransaction != null}"
        onMigrate(from, view)
    }
}

private class SnapshotRestoreSecrets : Store<SnapshotRestoreSecrets>() {
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val name by state(codec = StringCodec) { "" }
}

private class SnapshotRestoreTagged : Store<SnapshotRestoreTagged>() {
    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val account by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "anon" }
    val feed by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "for-${account.value}" }
    val plain by state(codec = IntCodec) { 0 }
    val notes by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val inbox by keyedState<String, Int>(codec = IntCodec, keyCodec = StringCodec, tags = setOf(StateTag.Remote)) { key -> key.length }
}

private class SnapshotRestoreDocs : Store<SnapshotRestoreDocs>() {
    val docs by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { id -> "draft $id" }
    val sizes by keyedState<Int, Int>(codec = IntCodec, keyCodec = IntCodec) { it }
    val tokens by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
}

/** Records the id of every transaction its store starts. */
private class SnapshotRestoreIds<V : Store<V>> : Middleware<V>() {
    val ids = mutableListOf<String>()

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        ids += context.transaction.id
    }
}

private class SnapshotRestoreBoom : RuntimeException("boom")

private const val SNAPSHOT_RESTORE_HEADER = """{"format":"holdfast.store","v":1,"schema":1,"""

private fun TransactionResult<*>.snapshotRestoreError(): Throwable = assertIs<TransactionResult.Error>(this).exception

/**
 * Issue #20's snapshot, restore, reset and schema surface on wasmJs, with the
 * JVM as the control. Most paths here take a store lock, an initializer latch
 * or a platform slot (the no-write region's, the materializing stack, the
 * settle scope); the decoder tests guard the snapshot reader's string
 * handling and its nesting cap on wasmJs's stack.
 */
class SnapshotRestoreSmokeTest {
    @Test
    fun snapshotMaterializesEveryDeclaredStateAndCapturesCommittedValues() {
        val store = SnapshotRestoreCounter()
        assertTrue(store.properties.isEmpty(), "declared, not materialized: ${store.properties.keys}")

        val first = store.snapshot()

        assertEquals(setOf("count", "label", "draft"), first.stateNames)
        assertEquals(setOf("count", "label", "draft"), store.properties.keys, "snapshot() materialized every state")
        assertEquals(1, first[store.count])
        assertEquals("n=1", first[store.label])
        assertEquals("", first[store.draft])
        assertEquals(1 to 1, store.countRuns to store.labelRuns, "each initializer ran once")
        assertEquals(1, store.count.value)
        assertEquals(1 to 1, store.countRuns to store.labelRuns, "a read after the snapshot runs no initializer")

        var inside: StoreSnapshot? = null
        store action {
            count mutate 5
            inside = snapshot()
        }
        assertEquals(1, assertNotNull(inside)[store.count], "a snapshot inside an action captures committed values only")
        assertEquals(5, store.snapshot()[store.count])
        assertEquals(1, first[store.count], "a snapshot is detached from later commits")
        assertEquals("StoreSnapshot(schema=1, states=[count, draft, label])", first.toString())
        assertEquals(SnapshotRestoreCounter().snapshot(), first, "equal by value across instances")
        assertNotEquals(store.snapshot(), first)
    }

    @Test
    fun encodeAndDecodeRoundTripCanonicalTextIntoAFreshStore() {
        // Escapes, a non-BMP character and a lone surrogate: the writer escapes them canonically, the reader reads them back.
        val tricky = "q\"b\\s/\n\t\u0001 é 😀\uD800"
        val store = SnapshotRestoreCounter()
        store action {
            count mutate 3
            label mutate tricky
            draft mutate "not written"
        }

        val text = store.snapshot().encode()

        assertEquals(
            SNAPSHOT_RESTORE_HEADER + """"states":{"count":"3","label":"q\"b\\s/\n\t\u0001 é 😀\ud800"},"skipped":["draft"]}""",
            text,
        )
        val decoded = StoreSnapshot.decode(text)
        assertEquals(text, decoded.encode(), "decode(s.encode()) holds the same text")
        assertEquals(StoreSnapshot.decode(text), decoded)
        assertNotEquals<Any>(store.snapshot(), decoded, "a captured snapshot never equals a decoded one")
        assertEquals(setOf("draft"), decoded.unencodableStateNames)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(tricky, decoded[store.label])

        val fresh = SnapshotRestoreCounter()
        val report = fresh.restore(decoded, RestorePolicy.Strict).getOrThrow()

        assertEquals(setOf("count", "label"), report.restored)
        assertEquals(setOf("draft"), report.kept)
        assertEquals(emptyList(), report.issues)
        assertEquals(3, fresh.count.value)
        assertEquals(tricky, fresh.label.value)
        assertEquals("", fresh.draft.value)
    }

    @Test
    fun theOneArgumentRestoreIgnoresAnUnknownNameAndFiresOnce() {
        val store = SnapshotRestoreDoubling()
        assertEquals(2, store.doubled.value)
        val ids = SnapshotRestoreIds<SnapshotRestoreDoubling>()
        store.middlewares(ids)
        val fired = mutableListOf<Int>()
        val subscription = store.count effect { fired += this }
        fired.clear()

        val decoded = StoreSnapshot.decode(SNAPSHOT_RESTORE_HEADER + """"states":{"count":"9","ghost":"1"},"skipped":[]}""")
        val result = store.restore(decoded)

        assertIs<TransactionResult.Success<Unit>>(result)
        assertEquals("Restore", result.transaction.id)
        assertEquals("Restore", ids.ids.first(), "the restore's own transaction comes first: ${ids.ids}")
        assertEquals(1, ids.ids.count { it == "Restore" }, "one transaction: ${ids.ids}")
        assertEquals(9, store.count.value)
        assertEquals(listOf(9), fired, "the restored state's observer fired once")
        assertEquals(18, store.doubled.value, "a derivedState recomputes once the restore settles")

        // An undo: a captured snapshot restored into the store that took it.
        val undo = store.snapshot()
        store action { count mutate 30 }
        store.restore(undo).getOrThrow()
        subscription.dispose()
        assertEquals(9, store.count.value)
        assertEquals(18, store.doubled.value)
        assertEquals(listOf(9, 30, 9), fired)
    }

    @Test
    fun restorePoliciesDecideWhichIssuesFailTheRestore() {
        val text =
            SNAPSHOT_RESTORE_HEADER + """"states":{"count":"abc","draft":"x","ghost":"1","label":"restored"},"skipped":[]}"""
        val store = SnapshotRestoreCounter()
        store action { label mutate "before" }

        val strict =
            assertIs<RestoreRejectedException>(
                store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).snapshotRestoreError(),
            )
        assertEquals(RestorePolicy.Strict, strict.policy)
        assertEquals(setOf("count", "draft", "ghost"), strict.issues.map { it.stateName }.toSet())
        assertFalse(strict.message.orEmpty().contains("abc"), "no value in the message: ${strict.message}")
        assertEquals("before", store.label.value, "nothing changed")

        val lenient = assertIs<RestoreRejectedException>(store.restore(StoreSnapshot.decode(text)).snapshotRestoreError())
        assertEquals(RestorePolicy.IgnoreUnknown, lenient.policy)
        assertEquals(setOf("count", "draft"), lenient.issues.map { it.stateName }.toSet(), "an unknown name is tolerated")
        assertEquals("before", store.label.value, "nothing changed")

        val report = store.restore(StoreSnapshot.decode(text), RestorePolicy.BestEffort).getOrThrow()
        assertEquals(setOf("label"), report.restored)
        assertEquals(
            setOf(
                RestoreIssue.Undecodable("count", "its codec threw NumberFormatException"),
                RestoreIssue.NoCodec("draft"),
                RestoreIssue.UnknownState("ghost"),
            ),
            report.issues.toSet(),
        )
        assertEquals("restored", store.label.value)
        assertEquals(1, store.count.value)
    }

    // The witness names classes by KClass.simpleName, which Kotlin/Wasm
    // implements on its own; an anonymous object has none: <anonymous>.
    @Test
    fun theTypeWitnessRejectsABuiltinValueOfAnotherClassAndNamesAnAnonymousOne() {
        val source = SnapshotRestoreShapes()
        val target = SnapshotRestoreMismatched()

        val report = target.restore(source.snapshot(), RestorePolicy.BestEffort).getOrThrow()

        assertEquals(
            listOf(
                RestoreIssue.TypeMismatch("count", "Int", "String"),
                RestoreIssue.TypeMismatch("shape", "<anonymous>", "Int"),
            ),
            report.issues.sortedBy { it.stateName },
        )
        assertEquals("one" to 0, target.count.value to target.shape.value, "neither value was restored")
    }

    @Test
    fun aRestoreOrResetInsideAnActionOrAFrameCommitsAndRollsBackWithIt() {
        val first = SnapshotRestoreAccount()
        val second = SnapshotRestoreAccount()
        val firstSnapshot = first.snapshot()
        val secondSnapshot = second.snapshot()
        first action { balance mutate 50 }
        second action { balance mutate 150 }
        val fired = mutableListOf<Int>()
        val subscription = first.balance effect { fired += this }
        fired.clear()

        var seenInside = -1
        val rolledBack =
            first action {
                restore(firstSnapshot).getOrThrow()
                seenInside = balance.value
                throw SnapshotRestoreBoom()
            }
        assertIs<SnapshotRestoreBoom>(rolledBack.snapshotRestoreError())
        assertEquals(100, seenInside, "read-your-own-writes sees the restored value")
        assertEquals(50, first.balance.value, "the savepoint rolled back with its action")

        val failedFrame =
            atomic(first, second) {
                first.restore(firstSnapshot).getOrThrow()
                second.restore(secondSnapshot).getOrThrow()
                throw SnapshotRestoreBoom()
            }
        assertIs<SnapshotRestoreBoom>(failedFrame.snapshotRestoreError())
        assertEquals(50 to 150, first.balance.value to second.balance.value, "nothing changed")
        assertEquals(emptyList(), fired)

        atomic(first, second) {
            first.restore(firstSnapshot).getOrThrow()
            second.restore(secondSnapshot).getOrThrow()
        }.getOrThrow()
        assertEquals(100 to 100, first.balance.value to second.balance.value)
        assertEquals(listOf(100), fired, "one commit, one fire")

        first action { balance mutate 7 }
        second action { balance mutate 8 }
        atomic(first, second) {
            first.reset().getOrThrow()
            second.reset().getOrThrow()
        }.getOrThrow()
        subscription.dispose()
        assertEquals(100 to 100, first.balance.value to second.balance.value)
        assertEquals(listOf(100, 7, 100), fired)
    }

    @Test
    fun resetReRunsInitializersAndFiresOnlyForStatesItChanges() {
        val store = SnapshotRestoreResettable()
        val ids = SnapshotRestoreIds<SnapshotRestoreResettable>()
        store.middlewares(ids)
        store action {
            a mutate 10
            b mutate 20
            docs["abc"] mutate 7
        }
        assertEquals(2, store.docs["zz"].value)
        val fired = mutableListOf<String>()
        val subscriptions =
            listOf(
                store.a effect { fired += "a=$this" },
                store.b effect { fired += "b=$this" },
                store.c effect { fired += "c=$this" },
                store.docs["abc"] effect { fired += "abc=$this" },
                store.docs["zz"] effect { fired += "zz=$this" },
            )
        fired.clear()
        ids.ids.clear()

        val result = store.reset()

        assertIs<TransactionResult.Success<Unit>>(result)
        assertEquals(listOf("Reset"), ids.ids)
        assertEquals(1 to 2, store.a.value to store.b.value, "b's initializer read a's reset value")
        assertEquals(3, store.docs["abc"].value)
        val entries = store.docs.entries
        assertEquals(listOf("abc", "zz"), entries.keys.toList(), "a reset never evicts")
        assertEquals(setOf("a=1", "b=2", "abc=3"), fired.toSet())
        assertEquals(3, fired.size, "once per changed state, none for an unchanged one: $fired")

        val fresh = SnapshotRestoreResettable()
        fresh.docs["abc"]
        fresh.docs["zz"]
        assertEquals(fresh.snapshot(), store.snapshot(), "a reset store snapshots like a fresh one")

        fired.clear()
        store.reset().getOrThrow()
        subscriptions.forEach { it.dispose() }
        assertEquals(emptyList(), fired, "a second reset changes nothing, so nothing fires")
    }

    @Test
    fun aResetThatThrowsOrMeetsACycleChangesNothingAndLeavesTheStoreUsable() {
        val store = SnapshotRestoreFragile()
        assertEquals(1, store.a.value)
        store action {
            a mutate 5
            b mutate 7
            c mutate 9
        }

        store.fail = true
        val thrown = assertIs<IllegalStateException>(store.reset().snapshotRestoreError())
        assertEquals("c refuses", thrown.message)
        assertEquals(Triple(5, 7, 9), Triple(store.a.value, store.b.value, store.c.value))

        store.fail = false
        store.cycle = true
        val cycle = assertIs<IllegalStateException>(store.reset().snapshotRestoreError())
        assertContains(cycle.message.orEmpty(), "cycle")
        assertEquals(Triple(5, 7, 9), Triple(store.a.value, store.b.value, store.c.value))

        store.cycle = false
        store.reset().getOrThrow()
        assertEquals(Triple(1, 0, 0), Triple(store.a.value, store.b.value, store.c.value))

        // No initializer frame, latch or cycle mark is left behind.
        (store action { a mutate 3 }).getOrThrow()
        assertEquals(3, store.a.value)
        assertEquals(1, SnapshotRestoreFragile().a.value)
    }

    @Test
    fun schemaVersionsMigrateAnOlderDecodedSnapshotAndRefuseTheRest() {
        val v1 = SnapshotRestoreReaderV1()
        v1 action {
            theme mutate "dark"
            fontSize mutate 18
        }
        val v1Text = v1.snapshot().encode()

        val upgraded = SnapshotRestoreReaderV2()
        val report = upgraded.restore(StoreSnapshot.decode(v1Text), RestorePolicy.Strict).getOrThrow()
        assertEquals(setOf("theme", "textSize"), report.restored)
        assertEquals("dark" to 18, upgraded.theme.value to upgraded.textSize.value)
        assertEquals(listOf("from=1, txn=false"), upgraded.migrations, "migrate ran once, before the action opened")

        val newer = StoreSnapshot.decode(upgraded.snapshot().encode())
        assertEquals(2, newer.schemaVersion)
        val refused = assertIs<SnapshotMigrationException>(v1.restore(newer).snapshotRestoreError())
        assertEquals(2 to 1, refused.snapshotVersion to refused.storeVersion)
        assertContains(refused.message.orEmpty(), "schema version 2 into SnapshotRestoreReaderV1, whose schema version is 1")
        assertEquals("dark", v1.theme.value)

        val captured = assertIs<SnapshotMigrationException>(SnapshotRestoreReaderV2().restore(v1.snapshot()).snapshotRestoreError())
        assertEquals(1 to 2, captured.snapshotVersion to captured.storeVersion)

        val writer = SnapshotRestoreReaderV2 { _, _ -> textSize mutate 1 }
        val failure = assertIs<SnapshotMigrationException>(writer.restore(StoreSnapshot.decode(v1Text)).snapshotRestoreError())
        assertIs<IllegalStateException>(failure.cause, "the refused write is attached")
        assertEquals(14, writer.textSize.value, "the refused write never landed")
        // The no-write region closed with the migration.
        (writer action { textSize mutate 2 }).getOrThrow()
        assertEquals(2, writer.textSize.value)
    }

    @Test
    fun secretValuesAreWithheldWhereverTheyAreReadOut() {
        val store = SnapshotRestoreSecrets()
        store action {
            token mutate "hunter2"
            name mutate "ann"
        }

        val all = store.snapshot()
        assertSame(Redacted, all.entry(store.token))
        assertNull(all[store.token])
        assertEquals("ann", all[store.name])
        val text = all.encode()
        assertEquals(SNAPSHOT_RESTORE_HEADER + """"states":{"name":"ann","token":null},"skipped":[]}""", text)
        assertContains(all.render(), "<redacted>")
        assertFalse("hunter2" in all.render() || "hunter2" in all.toString(), all.render())

        val raw = store.snapshot(SnapshotScope.Raw)
        assertEquals("hunter2", raw[store.token], "a Raw capture reads the plaintext, in memory")
        assertEquals(text, raw.encode(), "and still withholds it when encoded")
        assertFalse("hunter2" in raw.render(), raw.render())

        store action { token mutate "changed" }
        store.restore(all).getOrThrow()
        assertEquals("hunter2", store.token.value, "a capture holds the Secret's raw value, so an undo restores it")

        val decoded = StoreSnapshot.decode(text)
        assertSame(Redacted, decoded.entry(store.token))
        val fresh = SnapshotRestoreSecrets()
        val report = fresh.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("" to "ann", fresh.token.value to fresh.name.value, "a withheld value leaves the state as it is")
        assertEquals(setOf("name"), report.restored)
        assertEquals(setOf("token"), report.kept)
    }

    @Test
    fun tagsDecideTheUserAuthoredScopeTheEncodingAndASterileRestore() {
        val store = SnapshotRestoreTagged()
        store action {
            pins mutate "p1"
            account mutate "ann"
            feed mutate "fetched"
            plain mutate 1
            notes["n"] mutate "note"
            inbox["mail"] mutate 99
        }
        val userAuthored = store.snapshot(SnapshotScope.UserAuthored)
        assertEquals(setOf("pins", "account", "notes"), userAuthored.stateNames)
        val snap = store.snapshot()
        assertFalse("feed" in snap.encode() || "inbox" in snap.encode(), snap.encode())
        assertContains(snap.encode(includeRemote = true), """"feed":"fetched"""")
        assertContains(snap.encode(includeRemote = true), """"inbox":{"mail":"99"}""")

        val other = SnapshotRestoreTagged()
        other action { plain mutate 5 }
        other.restore(userAuthored).getOrThrow()
        assertEquals(Triple("p1", "ann", 5), Triple(other.pins.value, other.account.value, other.plain.value))
        assertEquals("note", other.notes["n"].value)

        store action {
            account mutate "bob"
            feed mutate "stale"
            inbox["mail"] mutate 50
        }
        val fired = mutableListOf<String>()
        val subscription = store.feed effect { fired += this }
        fired.clear()

        val report = store.restore(snap, RestorePolicy.Strict, sterile = true).getOrThrow()
        assertEquals(setOf("feed", "inbox"), report.sterilized)
        assertTrue("account" in report.restored, "${report.restored}")
        assertEquals("ann", store.account.value)
        assertEquals("for-ann", store.feed.value, "a Remote initializer reads the restored value")
        assertEquals(4, store.inbox["mail"].value, "a Remote family's live entry is reset")
        assertEquals(listOf("for-ann"), fired)

        fired.clear()
        store.restore(snap, RestorePolicy.Strict, sterile = true).getOrThrow()
        assertEquals(emptyList(), fired, "an unchanged Remote state does not fire")

        store.restore(snap).getOrThrow()
        subscription.dispose()
        assertEquals("fetched" to 99, store.feed.value to store.inbox["mail"].value, "a plain restore restores Remote states")
    }

    @Test
    fun keyedFamiliesEncodeSortedByKeyAndRestoreWithoutEvicting() {
        val store = SnapshotRestoreDocs()
        store action {
            docs["c"] mutate "C"
            docs["a"] mutate "A"
            docs["b"] mutate "B"
            sizes[10] mutate 100
            sizes[9] mutate 90
            tokens["t"] mutate "hunter2"
        }

        val snap = store.snapshot()
        assertEquals(listOf("c", "a", "b"), snap.keysOf(store.docs).toList(), "a capture lists keys in creation order")
        assertEquals("A", snap[store.docs["a"]])
        assertSame(Redacted, snap.entry(store.tokens["t"]))
        assertContains(snap.render(), "keyed state family (3 entries)")
        assertFalse("hunter2" in snap.render(), snap.render())

        // Sorted by encoded key, so "10" before "9".
        val text = snap.encode()
        assertEquals(
            SNAPSHOT_RESTORE_HEADER +
                """"states":{"docs":{"a":"A","b":"B","c":"C"},"sizes":{"10":"100","9":"90"},"tokens":{"t":null}},"skipped":[]}""",
            text,
        )

        val fresh = SnapshotRestoreDocs()
        assertEquals("draft z", fresh.docs["z"].value)
        val decoded = StoreSnapshot.decode(text)
        assertEquals(listOf("a", "b", "c"), decoded.keysOf(fresh.docs).toList(), "a decoded snapshot lists keys sorted")
        fresh.restore(decoded, RestorePolicy.Strict).getOrThrow()

        assertEquals(setOf("z", "a", "b", "c"), fresh.docs.entries.keys)
        assertEquals(listOf("A", "B", "C", "draft z"), listOf("a", "b", "c", "z").map { fresh.docs[it].value })
        assertEquals(90 to 100, fresh.sizes[9].value to fresh.sizes[10].value)
        assertEquals("", fresh.tokens["t"].value, "a withheld entry is not restored")
        assertEquals("B", decoded[fresh.docs["b"]])
    }

    @Test
    fun malformedTextFailsWithASnapshotFormatExceptionNamingTheProblem() {
        val body = """"states":{"count":"1"},"skipped":[]}"""
        val cases =
            mapOf(
                "" to "expected '{'",
                """{"format":"other","v":1,"schema":1,$body""" to "not a Holdfast store snapshot",
                """{"format":"holdfast.store","v":2,"schema":1,$body""" to "format version 2",
                """{"format":"holdfast.store","schema":1,$body""" to "\"v\" field is missing",
                SNAPSHOT_RESTORE_HEADER + """"states":{"a":"1","a":"2"},"skipped":[]}""" to "duplicate state",
                SNAPSHOT_RESTORE_HEADER + """"states":{"a":1},"skipped":[]}""" to "expected a state's text",
                SNAPSHOT_RESTORE_HEADER + """"states":{"a":"\q"},"skipped":[]}""" to "invalid escape sequence",
                SNAPSHOT_RESTORE_HEADER + """"states":{"a":"\u12G4"},"skipped":[]}""" to "invalid \\u escape",
                SNAPSHOT_RESTORE_HEADER + "\"states\":{\"a\":\"x\uD800y\"},\"skipped\":[]}" to "unpaired surrogate",
                SNAPSHOT_RESTORE_HEADER + """"states":{"docs":{"a":1}},"skipped":[]}""" to "expected a keyed state entry's text or null",
                SNAPSHOT_RESTORE_HEADER + """"states":{},"skipped":[],"x":1.}""" to "malformed number",
                SNAPSHOT_RESTORE_HEADER + body + " {}" to "unexpected content after the snapshot",
            )
        val wrong =
            cases.mapNotNull { (text, expected) ->
                val failure = runCatching { StoreSnapshot.decode(text) }.exceptionOrNull()
                val message = (failure as? SnapshotFormatException)?.message
                if (message != null && expected in message && "offset" in message) null else "$text -> $failure"
            }
        assertEquals(emptyList(), wrong)

        // A raw lone surrogate is refused above; the same one written as an escape reads.
        val escaped = StoreSnapshot.decode(SNAPSHOT_RESTORE_HEADER + """"states":{"label":"x\uD800y"},"skipped":[]}""")
        assertEquals("x\uD800y", escaped[SnapshotRestoreCounter().label], "an escaped lone surrogate reads")
    }

    @Test
    fun nestingIsCappedAt64LevelsWithoutOverflowingTheStack() {
        val allowed = "[".repeat(63) + "]".repeat(63)
        StoreSnapshot.decode(SNAPSHOT_RESTORE_HEADER + """"states":{},"skipped":[],"x":$allowed}""")

        val tooDeep = "[".repeat(64) + "]".repeat(64)
        val capped =
            assertFailsWith<SnapshotFormatException> {
                StoreSnapshot.decode(SNAPSHOT_RESTORE_HEADER + """"states":{},"skipped":[],"x":$tooDeep}""")
            }
        assertContains(capped.message.orEmpty(), "nested deeper than 64")

        // Far deeper than the cap: refused where the cap is reached (in a
        // family, at its first nested object), never descended into further.
        val depth = 100_000
        val deepObject = "{\"x\":".repeat(depth) + "1" + "}".repeat(depth)
        val atTop = assertFailsWith<SnapshotFormatException> { StoreSnapshot.decode(deepObject) }
        assertContains(atTop.message.orEmpty(), "nested deeper than 64")
        val deepFamily = "{\"k\":".repeat(depth) + "\"v\"" + "}".repeat(depth)
        val inFamily =
            assertFailsWith<SnapshotFormatException> {
                StoreSnapshot.decode(SNAPSHOT_RESTORE_HEADER + """"states":{"f":$deepFamily}}""")
            }
        assertContains(inFamily.message.orEmpty(), "expected a keyed state entry's text or null")
    }

    // A blocking restore()/reset() of the host from its own suspendAction body
    // hangs on every platform (issue #31), so the body drives another store:
    // each call commits on its own, while `other`'s derived state waits for
    // the settle of the outermost entry, the host's. The yields park the body,
    // so that settle scope has to follow it across resumptions (on wasmJs
    // through SettleAmbientContext's slot-bracketing interceptor).
    @Test
    fun aRestoreAndAResetOfAnotherStoreInsideASuspendActionSettleWithIt() =
        runTest {
            val host = SnapshotRestoreDoubling()
            val other = SnapshotRestoreDoubling()
            other action { count mutate 4 }
            val snap = other.snapshot()
            other action { count mutate 9 }
            assertEquals(18, other.doubled.value)
            val ids = SnapshotRestoreIds<SnapshotRestoreDoubling>()
            other.middlewares(ids)
            val fired = mutableListOf<Int>()
            val subscription = other.count effect { fired += this }
            fired.clear()
            val seen = mutableListOf<Int?>()

            val result =
                host.suspendAction {
                    yield()
                    other.restore(snap).getOrThrow()
                    seen += other.count.value
                    seen += other.doubled.value
                    yield()
                    other.reset().getOrThrow()
                    seen += other.count.value
                    seen += other.doubled.value
                    count mutate 6
                    yield()
                    seen += snapshot()[count]
                    count.value
                }

            subscription.dispose()
            assertEquals(6, result.getOrThrow())
            assertEquals(listOf<Int?>(4, 18, 1, 18, 1), seen, "derived states settle once the outermost entry exits")
            assertEquals(listOf("Restore", "Reset"), ids.ids.take(2), "${ids.ids}")
            assertEquals(listOf(4, 1), fired, "each committed on its own")
            assertEquals(1 to 2, other.count.value to other.doubled.value)
            assertEquals(6 to 12, host.count.value to host.doubled.value)
        }

    // Refused as a write into a commit that has already applied (D16): from
    // a blocking commit's fanout and from a suspending one's, which a
    // FanoutMarkers slot marks (on wasmJs through an interceptor). Neither
    // call may wait for the store its own observer is running inside.
    @Test
    fun aRestoreOrResetFromTheFanoutOfItsStoresOwnCommitIsRefused() =
        runTest {
            val store = SnapshotRestoreAccount()
            val snap = store.snapshot()
            val results = mutableListOf<TransactionResult<*>>()
            val subscription =
                store.balance effect {
                    if (this == 5 || this == 6) {
                        results += store.restore(snap)
                        results += store.reset()
                    }
                }

            store action { balance mutate 5 }
            val suspended =
                store.suspendAction {
                    yield()
                    balance mutate 6
                }
            suspended.getOrThrow()

            subscription.dispose()
            assertEquals(4, results.size)
            for (result in results) {
                val refused = assertIs<IllegalStateException>(result.snapshotRestoreError())
                assertContains(refused.message.orEmpty(), "has already applied its writes")
            }
            assertEquals(6, store.balance.value)
        }

    @Test
    fun anObserverOfARestoreSnapshotsTheWholeRestoredCut() {
        val store = SnapshotRestoreCounter()
        store action {
            count mutate 2
            label mutate "two"
        }
        val snap = store.snapshot()
        store action {
            count mutate 3
            label mutate "three"
        }
        val seen = mutableListOf<Pair<Int?, String?>>()
        val subscription =
            store.label effect {
                val inFanout = store.snapshot()
                seen += inFanout[store.count] to inFanout[store.label]
            }
        seen.clear()

        store.restore(snap).getOrThrow()

        subscription.dispose()
        assertEquals(listOf<Pair<Int?, String?>>(2 to "two"), seen)
    }

    @Test
    fun aThrowingObserverOfARestoreIsReportedAndTheRestoreStillCommits() {
        val store = SnapshotRestoreCounter()
        val snap = store.snapshot()
        store action {
            count mutate 7
            label mutate "seven"
        }
        val reported = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { reported += it }
        val throwing = store.count effect { if (this == 1) throw SnapshotRestoreBoom() }
        val seen = mutableListOf<String>()
        val watching = store.label effect { seen += this }
        seen.clear()

        val result = store.restore(snap)

        assertIs<TransactionResult.Success<Unit>>(result)
        assertEquals(1 to "n=1", store.count.value to store.label.value)
        assertEquals(listOf("n=1"), seen, "the other observer still ran")
        assertEquals(1, reported.size, "$reported")
        assertTrue(generateSequence(reported.single()) { it.cause }.any { it is SnapshotRestoreBoom }, "${reported.single()}")

        // With no handler the failure goes to the platform's default log (stdout
        // on wasmJs, so this prints one expected "boom"): still not thrown.
        store.uncaughtObserverHandler = null
        store action { count mutate 7 }
        store.reset().getOrThrow()
        throwing.dispose()
        watching.dispose()
        assertEquals(1, store.count.value)
        assertEquals(1, reported.size)
    }
}
