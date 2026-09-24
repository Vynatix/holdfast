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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Schema 1 of a reader's settings, as its first release shipped it. */
private class ReaderV1 : Store<ReaderV1>() {
    val theme by state(codec = StringCodec) { "light" }
    val fontSize by state(codec = IntCodec) { 14 }

    /** No codec: never encoded. */
    val draft by state { "" }
}

/** Schema 2: `fontSize` is renamed `textSize`. Logs each migrate call, and whether a transaction was open. */
private class ReaderV2 :
    Store<ReaderV2>(),
    SchemaVersioned {
    val theme by state(codec = StringCodec) { "light" }
    val textSize by state(codec = IntCodec) { 14 }
    val draft by state { "" }

    val migrations = mutableListOf<String>()

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        migrations += "from=$from, txn=${activeTransaction != null}"
        if (from < 2) view.rename("fontSize", "textSize")
    }
}

/** Schema 3: the theme "dark" is now spelled "night". */
private class ReaderV3 :
    Store<ReaderV3>(),
    SchemaVersioned {
    val theme by state(codec = StringCodec) { "day" }
    val textSize by state(codec = IntCodec) { 14 }

    val froms = mutableListOf<Int>()

    override val schemaVersion: Int get() = 3

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        froms += from
        if (from < 2) view.rename("fontSize", "textSize")
        if (from < 3 && view["theme"] == "dark") view.put("theme", "night")
    }
}

/** Schema 1 of a notes store: its keyed family is called `notes`, and an empty note is "". */
private class NotesV1 : Store<NotesV1>() {
    val notes by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }
}

/** Schema 2: the family is renamed `docs`, and an empty note is spelled "(empty)". */
private class NotesV2 :
    Store<NotesV2>(),
    SchemaVersioned {
    val title by state(codec = StringCodec) { "" }
    val docs by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "(empty)" }

    val seen = mutableListOf<Any?>()

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        if (from >= 2) return
        seen.add(view.families.names)
        seen.add(view.families.rename("notes", "docs"))
        seen.add(view.families.rename("never", "docs"))
        val entries = checkNotNull(view.families["docs"])
        view.families.put("docs", entries.mapValues { (_, text) -> if (text == "") "(empty)" else text })
        seen.add(view.families["docs"])
        seen.add(view.families.remove("gone"))
        seen.add("docs" in view.families)
    }
}

/** `fontSize` renamed `textSize` without a schema version: no migrate runs. */
private class ReaderRenamed : Store<ReaderRenamed>() {
    val theme by state(codec = StringCodec) { "light" }
    val textSize by state(codec = IntCodec) { 14 }
}

/** Schema 2 whose migrate forgets the rename. */
private class ReaderForgetful :
    Store<ReaderForgetful>(),
    SchemaVersioned {
    val theme by state(codec = StringCodec) { "light" }
    val textSize by state(codec = IntCodec) { 14 }

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) = Unit
}

/** Schema 2, migrating with [onMigrate]. */
private class ReaderWith(
    private val onMigrate: ReaderWith.(from: Int, view: EncodedSnapshotView) -> Unit,
) : Store<ReaderWith>(),
    SchemaVersioned {
    val theme by state(codec = StringCodec) { "light" }
    val textSize by state(codec = IntCodec) { 14 }

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) = onMigrate(from, view)
}

/** Declares schema [version], whatever it is. */
private class DeclaredVersion(
    private val version: Int,
) : Store<DeclaredVersion>(),
    SchemaVersioned {
    val n by state(codec = IntCodec) { 0 }

    var migrated = false

    override val schemaVersion: Int get() = version

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        migrated = true
    }
}

/** Issue #20, R2: schema versions, `migrate` upcasting, and the refusal of newer snapshots. */
class SnapshotMigrationTest {
    /** Text schema 1 wrote: theme "dark", fontSize 18, and the codec-less draft skipped. */
    private fun v1Text(theme: String = "dark"): String {
        val v1 = ReaderV1()
        v1 action {
            this.theme mutate theme
            fontSize mutate 18
            draft mutate "unsent"
        }
        return v1.snapshot().encode()
    }

    private val TransactionResult<*>.migrationFailure: SnapshotMigrationException
        get() = assertIs<SnapshotMigrationException>(assertIs<TransactionResult.Error>(this).exception)

    @Test fun aVersionOneSnapshotWithARenamedStateRestoresIntoVersionTwoThroughMigrate() {
        // R2 acceptance 1.
        val text = v1Text()
        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"fontSize":"18","theme":"dark"},""" +
                """"skipped":["draft"]}""",
            text,
        )
        val decoded = StoreSnapshot.decode(text)
        val store = ReaderV2()

        val report = store.restore(decoded, RestorePolicy.Strict).getOrThrow()

        assertEquals(18, store.textSize.value)
        assertEquals("dark", store.theme.value)
        assertEquals(setOf("textSize", "theme"), report.restored)
        assertEquals(setOf("draft"), report.kept)
        assertEquals(emptyList(), report.issues)
        assertEquals(listOf("from=1, txn=false"), store.migrations, "migrate ran once, before the action opened")
        assertEquals(text, decoded.encode(), "migrate edits a copy: the snapshot is unchanged")
        assertEquals(1, decoded.schemaVersion)

        val viaFrozen = ReaderV2()
        viaFrozen.restore(decoded).getOrThrow()
        assertEquals(18, viaFrozen.textSize.value, "the one-argument restore migrates too")
    }

    @Test fun migrateUpcastsThroughEveryVersionInOneCall() {
        val fromV1 = ReaderV3()
        fromV1.restore(StoreSnapshot.decode(v1Text()), RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf(1), fromV1.froms)
        assertEquals(18, fromV1.textSize.value)
        assertEquals("night", fromV1.theme.value)

        val v2 = ReaderV2()
        v2 action {
            theme mutate "dark"
            textSize mutate 21
        }
        val fromV2 = ReaderV3()
        fromV2.restore(StoreSnapshot.decode(v2.snapshot().encode()), RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf(2), fromV2.froms)
        assertEquals(21, fromV2.textSize.value)
        assertEquals("night", fromV2.theme.value)
    }

    @Test fun aNewerSnapshotFailsNamingBothVersionsAndTheStoreWithNothingChanged() {
        // R2 acceptance 2, under every policy.
        val v2 = ReaderV2()
        v2 action {
            theme mutate "dark"
            textSize mutate 20
        }
        val newer = StoreSnapshot.decode(v2.snapshot().encode())
        val store = ReaderV1()
        store action { theme mutate "sepia" }
        val before = store.snapshot()
        val fired = mutableListOf<String>()
        val sub = store { theme effect { fired += this } }
        fired.clear()

        val results =
            RestorePolicy.entries.map { store.restore(newer, it) } + store.restore(newer)
        for (result in results) {
            val failure = result.migrationFailure
            assertEquals(2, failure.snapshotVersion)
            assertEquals(1, failure.storeVersion)
            val message = failure.message.orEmpty()
            assertContains(message, "schema version 2 into ReaderV1, whose schema version is 1")
            assertContains(message, "Nothing was changed")
            assertNull(failure.cause)
        }
        assertEquals(before, store.snapshot(), "nothing changed")
        assertEquals(emptyList(), fired, "no observer fired")
        sub.dispose()

        // Refused first: a fresh store's states are not even materialized.
        val fresh = ReaderV1()
        fresh.restore(newer, RestorePolicy.BestEffort).migrationFailure
        assertTrue(fresh.properties.isEmpty(), "no initializer ran: ${fresh.properties.keys}")

        // A versioned store refuses a later schema of itself the same way.
        val v3 = ReaderV3()
        v3 action { theme mutate "night" }
        val v2Store = ReaderV2()
        val failure = v2Store.restore(StoreSnapshot.decode(v3.snapshot().encode()), RestorePolicy.BestEffort)
        assertEquals(3, failure.migrationFailure.snapshotVersion)
        assertEquals(2, failure.migrationFailure.storeVersion)
        assertEquals(emptyList(), v2Store.migrations, "migrate never runs for a newer snapshot")
        assertEquals("light", v2Store.theme.value)
    }

    @Test fun aRenameWithoutMigrateIsReportedAndFailsUnderStrict() {
        val text = v1Text()
        // No schema version at all, and a versioned store whose migrate forgets the rename.
        val unversioned = ReaderRenamed()
        val forgetful = ReaderForgetful()

        val lenient = unversioned.restore(StoreSnapshot.decode(text), RestorePolicy.IgnoreUnknown).getOrThrow()
        assertEquals(listOf<RestoreIssue>(RestoreIssue.UnknownState("fontSize")), lenient.issues)
        assertTrue("textSize" in lenient.kept, "the renamed state keeps its value: ${lenient.kept}")
        assertEquals(14, unversioned.textSize.value)
        assertEquals("dark", unversioned.theme.value, "the rest restores")

        val alsoLenient = forgetful.restore(StoreSnapshot.decode(text), RestorePolicy.IgnoreUnknown).getOrThrow()
        assertEquals(listOf<RestoreIssue>(RestoreIssue.UnknownState("fontSize")), alsoLenient.issues)

        assertStrictRejectsTheRename(ReaderRenamed(), text)
        assertStrictRejectsTheRename(ReaderForgetful(), text)
    }

    private fun <V : Store<V>> assertStrictRejectsTheRename(
        store: V,
        text: String,
    ) {
        val strict = store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict)
        val rejected = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(strict).exception)
        assertEquals(listOf("fontSize"), rejected.issues.map { it.stateName })
        assertEquals("light", store.snapshot().rawValues["theme"], "nothing changed")
    }

    @Test fun aThrowingMigrateFailsCleanly() {
        val secret = "S3CR3T-7731"
        val text = v1Text(theme = secret)
        for (policy in RestorePolicy.entries) {
            val store =
                ReaderWith { _, view ->
                    view.rename("fontSize", "textSize")
                    throw IllegalArgumentException("cannot read theme ${view["theme"]}")
                }
            store action { textSize mutate 16 }
            val before = store.snapshot()

            val failure = store.restore(StoreSnapshot.decode(text), policy).migrationFailure

            assertEquals(1, failure.snapshotVersion)
            assertEquals(2, failure.storeVersion)
            val message = failure.message.orEmpty()
            assertContains(message, "schema version 1 into ReaderWith, whose schema version is 2")
            assertContains(message, "ReaderWith.migrate(from = 1) threw IllegalArgumentException")
            assertNull(failure.cause, "migrate's own exception is never attached")
            val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(chain.none { secret in it.toString() }, "$policy: the value leaks: $chain")
            assertEquals(before, store.snapshot(), "$policy: nothing changed")
        }
    }

    @Test fun migrateMayNotWriteAnyStore() {
        val text = v1Text()
        val other = ReaderV1()
        val attempts: List<Pair<String, ReaderWith.() -> Unit>> =
            listOf(
                "mutate" to { textSize mutate 1 },
                "action" to { action { textSize mutate 1 } },
                "another store" to { other.action { theme mutate "sepia" } },
                "restore" to { restore(snapshot()) },
                "reset" to { reset() },
            )
        for ((case, attempt) in attempts) {
            val store = ReaderWith { _, _ -> attempt() }
            val before = store.snapshot()

            val failure = store.restore(StoreSnapshot.decode(text), RestorePolicy.BestEffort).migrationFailure

            val refused = assertIs<IllegalStateException>(failure.cause, "$case: the refusal is attached")
            assertContains(refused.message.orEmpty(), "ReaderWith.migrate(from = 1) is running", message = case)
            assertContains(failure.message.orEmpty(), "attached as the cause", message = case)
            assertEquals(before, store.snapshot(), "$case: nothing changed")
        }
        assertEquals("light", other.theme.value)

        // A refusal migrate catches is still attached when migrate then throws something else.
        val swallowing =
            ReaderWith { _, _ ->
                runCatching { textSize mutate 1 }
                error("gave up")
            }
        val failure = swallowing.restore(StoreSnapshot.decode(text), RestorePolicy.BestEffort).migrationFailure
        assertContains(failure.cause?.message.orEmpty(), "migrate(from = 1) is running")
        assertContains(failure.message.orEmpty(), "threw IllegalStateException, which is not attached")
    }

    @Test fun migrateRunsBeforeTheActionAndReadsCommittedValues() {
        val seen = mutableListOf<String>()
        val store =
            ReaderWith { _, view ->
                seen += "textSize=${textSize.value}, txn=${activeTransaction?.id}"
                view.rename("fontSize", "textSize")
            }
        store.restore(StoreSnapshot.decode(v1Text()), RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf("textSize=14, txn=null"), seen, "at top level, no transaction is open yet")

        seen.clear()
        store action { textSize mutate 99 }
        store action {
            textSize mutate 7
            restore(StoreSnapshot.decode(v1Text()), RestorePolicy.Strict).getOrThrow()
        }
        assertEquals(1, seen.size)
        assertTrue(seen.single().startsWith("textSize=99,"), "migrate reads committed values: $seen")
        assertEquals(18, store.textSize.value)
    }

    @Test fun migrateSeesAnEmptyFamiliesSection() {
        // A snapshot of a store that declares no keyed state family holds none...
        val seen = mutableListOf<Set<String>>()
        val store =
            ReaderWith { _, view ->
                seen += view.families.names
                view.rename("fontSize", "textSize")
            }
        store.restore(StoreSnapshot.decode(v1Text()), RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf(emptySet<String>()), seen)

        // ...unless text a later Holdfast wrote holds one: the view names it, and keeps its name for it.
        seen.clear()
        val withFamily =
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"docs":{"a":"1"},"theme":"dark"},"skipped":[]}"""
        val claiming =
            ReaderWith { _, view ->
                seen += view.families.names
                view.put("docs", "1")
            }
        val failure = claiming.restore(StoreSnapshot.decode(withFamily), RestorePolicy.BestEffort).migrationFailure
        assertEquals(listOf(setOf("docs")), seen)
        assertIs<IllegalArgumentException>(failure.cause, "a misuse of the view is attached")
        assertContains(failure.cause?.message.orEmpty(), "keyed state family under that name")
        assertEquals("light", claiming.theme.value)
    }

    @Test fun migrateRenamesAndEditsAKeyedStateFamily() {
        val v1 = NotesV1()
        v1 action {
            notes["a"] mutate "hello"
            notes["b"] mutate ""
        }
        val v2 = NotesV2()

        v2.restore(StoreSnapshot.decode(v1.snapshot().encode()), RestorePolicy.Strict).getOrThrow()

        assertEquals(
            listOf<Any?>(setOf("notes"), true, false, mapOf("a" to "hello", "b" to "(empty)"), false, true),
            v2.seen,
        )
        assertEquals(mapOf("a" to "hello", "b" to "(empty)"), v2.docs.entries.mapValues { it.value.value })
    }

    @Test fun aFamilyEditUnderAStatesNameIsAMisuseOfTheView() {
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"theme":"dark"},"skipped":[]}"""
        for (edit in listOf<(EncodedSnapshotView) -> Unit>(
            { it.families.put("theme", mapOf("k" to "v")) },
            { it.families.rename("gone", "theme") },
        )) {
            val store = ReaderWith { _, view -> edit(view) }

            val failure = store.restore(StoreSnapshot.decode(text), RestorePolicy.BestEffort).migrationFailure

            assertIs<IllegalArgumentException>(failure.cause, "a misuse of the view is attached")
            assertContains(failure.cause?.message.orEmpty(), "holds a state's entry under that name")
        }
    }

    @Test fun theViewEditsEncodedText() {
        val text =
            """{"format":"holdfast.store","v":1,"schema":1,""" +
                """"states":{"fontSize":"18","old":"x","theme":"dark","withheld":null},"skipped":["draft"]}"""
        lateinit var leaked: EncodedSnapshotView
        val observed = mutableListOf<Any?>()
        val store =
            ReaderWith { _, view ->
                leaked = view
                observed.add(view.stateNames)
                observed.add(listOf("withheld" in view, view["withheld"], "draft" in view, view["draft"]))
                observed.add(view.toString())
                // stateNames is a copy: editing while iterating it is safe.
                for (name in view.stateNames) if (name == "old") view.remove(name)
                observed.add(view.remove("old"))
                observed.add(view.rename("nothing", "textSize"))
                observed.add(view.rename("fontSize", "textSize"))
                view.put("theme", null)
                observed.add(view.stateNames)
            }

        val report = store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict)

        assertEquals(
            listOf<Any?>(
                setOf("fontSize", "old", "theme", "withheld"),
                listOf(true, null, false, null),
                "EncodedSnapshotView(states=[fontSize, old, theme, withheld], families=[])",
                false,
                false,
                true,
                setOf("textSize", "theme", "withheld"),
            ),
            observed,
        )
        // "withheld" is unknown to the store, so Strict fails: withheld entries are still entries.
        val rejected = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(report).exception)
        assertEquals(listOf("withheld"), rejected.issues.map { it.stateName })

        val lenient = store.restore(StoreSnapshot.decode(text), RestorePolicy.IgnoreUnknown).getOrThrow()
        assertEquals(setOf("textSize"), lenient.restored)
        assertTrue("theme" in lenient.kept, "a withheld theme keeps its value")
        assertEquals(18, store.textSize.value)
        assertEquals("light", store.theme.value)

        val closed = assertFailsWith<IllegalStateException> { leaked.put("theme", "dark") }
        assertContains(closed.message.orEmpty(), "valid only while the migrate")
    }

    @Test fun aSameVersionRoundTripSkipsMigrate() {
        val source = ReaderV2()
        source action {
            theme mutate "dark"
            textSize mutate 20
        }
        val decodedInto = ReaderV2()
        decodedInto.restore(StoreSnapshot.decode(source.snapshot().encode()), RestorePolicy.Strict).getOrThrow()
        assertEquals(20, decodedInto.textSize.value)
        assertEquals(emptyList(), decodedInto.migrations)

        val capturedInto = ReaderV2()
        capturedInto.restore(source.snapshot(), RestorePolicy.Strict).getOrThrow()
        assertEquals(20, capturedInto.textSize.value)
        assertEquals(emptyList(), capturedInto.migrations)

        // A store that declares version 1 is a store without a version.
        val one = DeclaredVersion(1)
        one.restore(StoreSnapshot.decode(v1Text()), RestorePolicy.IgnoreUnknown).getOrThrow()
        assertEquals(1, one.snapshot().schemaVersion)
        assertFalse(one.migrated)
    }

    @Test fun theVersionIsCapturedEncodedAndDecoded() {
        val versioned = ReaderV2().snapshot()
        assertEquals(2, versioned.schemaVersion)
        val text = versioned.encode()
        assertContains(text, """"schema":2,""")
        assertEquals(2, StoreSnapshot.decode(text).schemaVersion)
        assertEquals("StoreSnapshot(schema=2, states=[draft, textSize, theme])", versioned.toString())

        assertEquals(1, ReaderV1().snapshot().schemaVersion)
        assertContains(ReaderV1().snapshot().encode(), """"schema":1,""")
        assertEquals(DeclaredVersion(7).snapshot(), DeclaredVersion(7).snapshot())
        assertFalse(
            DeclaredVersion(7).snapshot() == DeclaredVersion(8).snapshot(),
            "snapshots of different schema versions are not equal",
        )
    }

    @Test fun typedReadsOfAnOlderDecodedSnapshotAreNotMigrated() {
        val old = StoreSnapshot.decode(v1Text())
        val store = ReaderV3()
        assertNull(old[store.textSize], "still fontSize in the text")
        assertEquals("dark", old[store.theme], "not respelled night")
        assertEquals(emptyList(), store.froms, "a typed read runs no migrate")
        assertEquals(1, old.schemaVersion)
    }

    @Test fun aCapturedSnapshotOfAnotherVersionIsRefusedAndMigratesThroughText() {
        val v1 = ReaderV1()
        v1 action { fontSize mutate 18 }
        val captured = v1.snapshot()
        val store = ReaderV2()

        val older = store.restore(captured, RestorePolicy.BestEffort).migrationFailure
        assertTrue(store.properties.isEmpty(), "refused first, no initializer ran: ${store.properties.keys}")
        assertEquals(1, older.snapshotVersion)
        assertEquals(2, older.storeVersion)
        assertContains(older.message.orEmpty(), "StoreSnapshot.decode(snapshot.encode())")
        assertEquals(emptyList(), store.migrations)
        assertEquals(14, store.textSize.value)

        store.restore(StoreSnapshot.decode(captured.encode()), RestorePolicy.Strict).getOrThrow()
        assertEquals(18, store.textSize.value, "through text, migrate runs")

        val target = ReaderV1()
        val newer = target.restore(ReaderV2().snapshot()).migrationFailure
        assertEquals(2, newer.snapshotVersion)
        assertEquals(1, newer.storeVersion)
        assertTrue(target.properties.isEmpty(), "refused first, no initializer ran: ${target.properties.keys}")
    }

    @Test fun aSchemaVersionBelowOneIsRefused() {
        for (version in listOf(0, -3)) {
            val store = DeclaredVersion(version)
            val onSnapshot = assertFailsWith<IllegalStateException> { store.snapshot() }
            assertContains(onSnapshot.message.orEmpty(), "DeclaredVersion.schemaVersion is $version")
            assertTrue(store.properties.isEmpty(), "checked before any initializer runs")

            val onRestore = store.restore(StoreSnapshot.decode(v1Text()), RestorePolicy.BestEffort)
            val failure = assertIs<IllegalStateException>(assertIs<TransactionResult.Error>(onRestore).exception)
            assertContains(failure.message.orEmpty(), "schema version is at least 1")
            assertFalse(store.migrated)
        }
    }
}
