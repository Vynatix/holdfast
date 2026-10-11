@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.LongCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
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

/** A codec of our own, not a `bridge.Codec`: tags joined by commas. */
private object TagsCodec : StateCodec<List<String>> {
    override fun encode(value: List<String>): String = value.joinToString(",")

    override fun decode(string: String): List<String> = if (string.isEmpty()) emptyList() else string.split(",")
}

private class CodecProfileStore : Store<CodecProfileStore>() {
    val name by state(codec = StringCodec) { "guest" }
    val visits by state(codec = IntCodec) { 0 }
    val lastSeen by state(codec = LongCodec) { 0L }
    val tags by state(codec = TagsCodec) { emptyList() }

    /** No codec: captured in memory, never encoded. */
    val draft by state { "" }
}

private val PIN_SEED = "snapshot-codec-seed".encodeToByteArray()

private class PinStore : Store<PinStore>() {
    val pin by state(transformer = EncryptingTransformer(XorCipher(PIN_SEED)), codec = StringCodec) { "" }
}

/** Same state name as [CodecProfileStore.visits], another type. */
private class LabelledStore : Store<LabelledStore>() {
    val visits by state { "many" }
    val name by state { "guest" }
}

private sealed interface Screen {
    data object Loading : Screen

    data class Loaded(
        val items: Int,
    ) : Screen
}

private class ScreenStoreA : Store<ScreenStoreA>() {
    val screen by state<Screen> { Screen.Loading }
    val lines by state { emptyList<String>() }
}

private class ScreenStoreB : Store<ScreenStoreB>() {
    val screen by state<Screen> { Screen.Loading }
    val lines by state { emptyList<String>() }
}

private class TransactionIds : Middleware<CodecProfileStore>() {
    val ids = mutableListOf<String>()

    override fun onTransactionStarted(context: MiddlewareContext<CodecProfileStore>) {
        ids += context.transaction.id
    }
}

/** Decodes every number as a Long, whatever it held. */
private object NumberAsLong : StateCodec<Number> {
    override fun encode(value: Number): String = value.toString()

    override fun decode(string: String): Number = string.toDouble().toLong()
}

/** A broad declared type: its values are built-in types of different classes. */
private open class NumberStore : Store<NumberStore>() {
    val n by state<Number> { 0 }
    val m by state<Number>(codec = NumberAsLong) { 0 }
}

private class SubNumberStore : NumberStore()

/** A generic store class: instances with different type arguments share one erased class. */
private class Box<T : Any>(
    init: T,
) : Store<Box<T>>() {
    val v by state { init }
}

/** The same state name and value, with a codec and without one. */
private class CodecCount : Store<CodecCount>() {
    val count by state(codec = IntCodec) { 0 }
}

private class PlainCount : Store<PlainCount>() {
    val count by state { 0 }
}

/** Logs, in order, when [n]'s initializer and its codec's decode run, and whether a transaction is open. */
private class PlanOrderStore : Store<PlanOrderStore>() {
    val events = mutableListOf<String>()

    private val loggingCodec =
        object : StateCodec<Int> {
            override fun encode(value: Int): String = value.toString()

            override fun decode(string: String): Int {
                events += "decode:txn=${activeTransaction != null}"
                return string.toInt()
            }
        }

    val n by state(codec = loggingCodec) {
        events += "init:txn=${activeTransaction != null}"
        0
    }
}

/**
 * [r]'s initializer restores [source] into its own store: through the policy
 * overload when [policy] is set, the one-argument `restore` otherwise. [n]
 * logs when its initializer and its codec's decode run.
 */
private class RestoringInitStore(
    private val source: StoreSnapshot,
    private val policy: RestorePolicy?,
) : Store<RestoringInitStore>() {
    val events = mutableListOf<String>()

    private val loggingCodec =
        object : StateCodec<Int> {
            override fun encode(value: Int): String = value.toString()

            override fun decode(string: String): Int {
                events += "decode"
                return string.toInt()
            }
        }

    val n by state(codec = loggingCodec) {
        events += "init"
        0
    }

    val r by state {
        if (policy == null) restore(source) else restore(source, policy)
        1
    }
}

/** Logs each transaction's start into [PlanOrderStore.events]. */
private class StartLog : Middleware<PlanOrderStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<PlanOrderStore>) {
        context.store.events += "started:${context.transaction.id}"
    }
}

/** Removes the state [name] as a restore's action starts: after the restore planned it, before it stages. */
private class RemoveOnRestore(
    private val name: String,
) : Middleware<PlanOrderStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<PlanOrderStore>) {
        if (context.transaction.id == "Restore") context.store.removeState(name)
    }
}

/** Issue #20, R1: codec-bearing states, encode/decode, restore policies and typed reads. */
class SnapshotCodecTest {
    private fun CodecProfileStore.visit() {
        action {
            name mutate "ada"
            visits mutate 42
            lastSeen mutate 1_700_000_000_000L
            tags mutate listOf("admin", "beta")
            draft mutate "unsent"
        }
    }

    @Test fun codecStatesRoundTripThroughTextIntoAFreshInstance() {
        // R1 acceptance 1: restore(decode(snapshot().encode())) is lossless.
        val source = CodecProfileStore()
        source.visit()

        val fresh = CodecProfileStore()
        val r = fresh.restore(StoreSnapshot.decode(source.snapshot().encode()), RestorePolicy.Strict)

        assertIs<TransactionResult.Success<RestoreReport>>(r)
        assertEquals("ada", fresh.name.value)
        assertEquals(42, fresh.visits.value)
        assertEquals(1_700_000_000_000L, fresh.lastSeen.value)
        assertEquals(listOf("admin", "beta"), fresh.tags.value)
        assertEquals(setOf("name", "visits", "lastSeen", "tags"), r.value.restored)
    }

    @Test fun aStateWithoutACodecIsExcludedAndReportedByName() {
        // R1 acceptance 2.
        val source = CodecProfileStore()
        source.visit()
        val snapshot = source.snapshot()
        val text = snapshot.encode()

        assertEquals(setOf("draft"), snapshot.unencodableStateNames)
        assertFalse("unsent" in text, "the codec-less state's value is not encoded: $text")
        assertTrue(text.endsWith(""""skipped":["draft"]}"""), text)
        val decoded = StoreSnapshot.decode(text)
        assertEquals(setOf("draft"), decoded.unencodableStateNames)
        assertTrue("draft" in decoded.stateNames, "a decoded snapshot still lists it")

        val fresh = CodecProfileStore()
        val report = fresh.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("", fresh.draft.value, "the unencoded state keeps its value")
        assertEquals(setOf("draft"), report.kept)
        assertEquals(emptyList(), report.issues)
    }

    @Test fun unknownNamesAreIgnoredAndReportedAndMissingNamesKeepTheirValue() {
        // R1 acceptance 3: tolerance by construction.
        val store = CodecProfileStore()
        store action { visits mutate 7 }
        val visitsSeen = mutableListOf<Int>()
        val sub = store.visits effect { visitsSeen += this }
        visitsSeen.clear()
        val text =
            """{"format":"holdfast.store","v":1,"schema":1,""" +
                """"states":{"ghost":"1","name":"restored"},"skipped":[]}"""

        val report = store.restore(StoreSnapshot.decode(text), RestorePolicy.IgnoreUnknown).getOrThrow()
        val frozen = store.restore(StoreSnapshot.decode(text))
        sub.dispose()

        assertEquals(listOf<RestoreIssue>(RestoreIssue.UnknownState("ghost")), report.issues)
        assertEquals(setOf("name"), report.restored)
        assertTrue("visits" in report.kept)
        assertIs<TransactionResult.Success<Unit>>(frozen, "the one-argument restore ignores unknown names too")
        assertEquals("restored", store.name.value)
        assertEquals(7, store.visits.value, "a missing name keeps its value")
        assertEquals(emptyList(), visitsSeen, "and its observer stays silent")
    }

    @Test fun anUnknownNameFailsAStrictRestoreWithNothingChanged() {
        val store = CodecProfileStore()
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"ghost":"1","name":"x"},"skipped":[]}"""

        val r = store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict)

        assertIs<TransactionResult.Error>(r)
        val rejected = assertIs<RestoreRejectedException>(r.exception)
        assertEquals(RestorePolicy.Strict, rejected.policy)
        assertEquals(listOf<RestoreIssue>(RestoreIssue.UnknownState("ghost")), rejected.issues)
        assertEquals("guest", store.name.value, "nothing was staged")
    }

    @Test fun eachPolicyDecidesWhichIssuesAbort() {
        // One unknown name, one undecodable leaf, one text for a codec-less state.
        val text =
            """{"format":"holdfast.store","v":1,"schema":1,""" +
                """"states":{"draft":"d","ghost":"g","name":"ok","visits":"not-a-number"},"skipped":[]}"""
        val strict = CodecProfileStore().restore(StoreSnapshot.decode(text), RestorePolicy.Strict)
        val ignoreUnknown = CodecProfileStore().restore(StoreSnapshot.decode(text), RestorePolicy.IgnoreUnknown)
        val bestEffortStore = CodecProfileStore()
        val bestEffort = bestEffortStore.restore(StoreSnapshot.decode(text), RestorePolicy.BestEffort)

        assertEquals(3, assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(strict).exception).issues.size)
        val ignored = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(ignoreUnknown).exception)
        assertEquals(setOf("draft", "visits"), ignored.issues.map { it.stateName }.toSet(), "only the unknown name is tolerated")

        val report = assertIs<TransactionResult.Success<RestoreReport>>(bestEffort).value
        assertEquals(setOf("name"), report.restored)
        assertEquals(
            setOf(
                RestoreIssue.NoCodec("draft"),
                RestoreIssue.UnknownState("ghost"),
                RestoreIssue.Undecodable("visits", "its codec threw NumberFormatException"),
            ),
            report.issues.toSet(),
        )
        assertEquals("ok", bestEffortStore.name.value)
        assertEquals(0, bestEffortStore.visits.value, "a skipped entry leaves its state alone")
    }

    @Test fun anEncryptedStateEncodesItsCiphertextAndRestoresWithoutDoubleEncryption() {
        val source = PinStore()
        source action { pin mutate "4921" }
        val captured = source.snapshot()
        val ciphertext = assertIs<String>(captured.rawValues["pin"])
        val text = captured.encode()

        assertNotEquals("4921", ciphertext)
        assertFalse("4921" in text, "the plaintext never reaches the encoding: $text")
        assertTrue(text.contains(buildString { appendJsonString(ciphertext) }), "the ciphertext is what is encoded")
        assertEquals("4921", captured[source.pin], "a typed read decrypts, as state.value does")

        val fresh = PinStore()
        fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals("4921", fresh.pin.value, "restored raw: decrypting once yields the plaintext")
        assertEquals(ciphertext, fresh.snapshot().rawValues["pin"], "and the stored ciphertext is not encrypted again")
    }

    @Test fun aCrossClassTypeMismatchFailsNamingTheState() {
        val profile = CodecProfileStore()
        profile action { visits mutate 3 }
        val labelled = LabelledStore()

        val r = labelled.restore(profile.snapshot())

        assertIs<TransactionResult.Error>(r)
        val rejected = assertIs<RestoreRejectedException>(r.exception)
        assertEquals(listOf<RestoreIssue>(RestoreIssue.TypeMismatch("visits", "Int", "String")), rejected.issues)
        assertTrue(rejected.message!!.contains("'visits'"), "the message names the state: ${rejected.message}")
        assertEquals("guest", labelled.name.value, "nothing was changed")

        val report = labelled.restore(profile.snapshot(), RestorePolicy.BestEffort).getOrThrow()
        assertEquals("many", labelled.visits.value, "best effort skips the mismatched value")
        assertEquals(setOf("name"), report.restored)
    }

    @Test fun theTypeWitnessAcceptsSubtypesSiblingsAndOtherCollectionClasses() {
        val a = ScreenStoreA()
        a action {
            screen mutate Screen.Loaded(items = 2)
            lines mutate listOf("one")
        }
        val b = ScreenStoreB() // another store class: the witness runs, and holds Loading and an empty list

        val r = b.restore(a.snapshot(), RestorePolicy.Strict)

        assertIs<TransactionResult.Success<RestoreReport>>(r)
        assertEquals(Screen.Loaded(items = 2), b.screen.value)
        assertEquals(listOf("one"), b.lines.value)
    }

    @Test fun theTypeWitnessRejectsAnotherBuiltInClassInUntrustedContent() {
        // Negative control for the exemptions below: a hand-made snapshot comes from no store class.
        val store = NumberStore()

        val r = store.restore(StoreSnapshot(mapOf("n" to 1.5)))

        assertIs<TransactionResult.Error>(r)
        val rejected = assertIs<RestoreRejectedException>(r.exception)
        assertEquals(listOf<RestoreIssue>(RestoreIssue.TypeMismatch("n", "Double", "Int")), rejected.issues)
    }

    @Test fun aSnapshotOfTheSameStoreClassSkipsTheTypeWitness() {
        val store = NumberStore()
        store action { n mutate 1.5 }
        val snap = store.snapshot()
        store action { n mutate 2 }

        assertIs<TransactionResult.Success<Unit>>(store.restore(snap), "the frozen one-argument undo")
        assertEquals(1.5, store.n.value)

        val fresh = NumberStore()
        fresh action { n mutate 2 }
        fresh.restore(snap, RestorePolicy.Strict).getOrThrow()
        assertEquals(1.5, fresh.n.value, "another instance of the same class is trusted too")

        val sub = SubNumberStore()
        sub action { n mutate 2 }
        sub.restore(snap, RestorePolicy.Strict).getOrThrow()
        assertEquals(1.5, sub.n.value, "a snapshot from a superclass instance is trusted")
    }

    @Test fun aDecodedValueSkipsTheTypeWitness() {
        val store = NumberStore()
        store action { m mutate 2 } // holds an Int; the codec decodes to a Long
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"m":"7"},"skipped":[]}"""

        store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals(7L, store.m.value)
    }

    @Test fun theTrustedSkipComparesTheClassNotItsTypeArguments() {
        // Documented limit: the class is erased, so a Box<Int> snapshot restores unchecked into a Box<String>.
        val strings = Box("a")

        val r = strings.restore(Box(1).snapshot(), RestorePolicy.Strict)

        val report = assertIs<TransactionResult.Success<RestoreReport>>(r).value
        assertEquals(emptyList(), report.issues)
        assertEquals<Any?>(1, strings.snapshot().rawValues["v"])
    }

    @Test fun equalityIgnoresCodecsSoEqualSnapshotsCanEncodeDifferently() {
        val withCodec = CodecCount().snapshot()
        val without = PlainCount().snapshot()

        assertEquals(withCodec, without)
        assertEquals(withCodec.hashCode(), without.hashCode())
        assertNotEquals(withCodec.encode(), without.encode())
        assertEquals(setOf("count"), without.unencodableStateNames)
    }

    @Test fun restorePlansBeforeItsActionOpens() {
        // Decoded: the never-read target's initializer, then its codec, before the action starts.
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"n":"7"},"skipped":[]}"""
        val store = PlanOrderStore()
        store.middlewares(StartLog())

        store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()

        assertEquals(listOf("init:txn=false", "decode:txn=false", "started:Restore"), store.events)
        assertEquals(7, store.n.value)

        // Captured: a never-read target of a fresh store is initialized before the action opens too.
        val fresh = PlanOrderStore()
        fresh.middlewares(StartLog())
        fresh.restore(store.snapshot()).getOrThrow()
        assertEquals(listOf("init:txn=false", "started:Restore"), fresh.events)
        assertEquals(7, fresh.n.value)
    }

    @Test fun restoreFromAnInitializerFailsFast() {
        // Refused before the plan: no other state's initializer runs, and no codec decodes.
        val decoded =
            StoreSnapshot.decode("""{"format":"holdfast.store","v":1,"schema":1,"states":{"n":"7"},"skipped":[]}""")
        val captured = PlanOrderStore().snapshot()
        val cases =
            listOf(
                "decoded, policy overload" to RestoringInitStore(decoded, RestorePolicy.BestEffort),
                "decoded, one-argument restore" to RestoringInitStore(decoded, null),
                "captured, one-argument restore" to RestoringInitStore(captured, null),
            )
        for ((case, store) in cases) {
            val e = assertFailsWith<IllegalStateException>(case) { store.r }
            assertContains(e.message.orEmpty(), "Cannot restore RestoringInitStore", message = case)
            assertContains(e.message.orEmpty(), "initializer of RestoringInitStore.r", message = case)
            assertEquals(emptyList(), store.events, "$case: neither n's initializer nor its codec ran")
        }
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun aTargetRemovedBetweenThePlanAndTheActionIsResolvedAgain() {
        // A declared state is materialized again, inside the action.
        val store = PlanOrderStore()
        store action { n mutate 3 }
        val snap = store.snapshot()
        store action { n mutate 4 }
        store.middlewares(StartLog(), RemoveOnRestore("n"))
        store.events.clear()

        store.restore(snap).getOrThrow()

        assertEquals(listOf("started:Restore", "init:txn=true"), store.events, "its initializer ran inside the action")
        assertEquals(3, store.n.value)

        // An internal state lost its declaration with it: the restore fails, and nothing is left behind.
        val internal = PlanOrderStore()
        internal.registerInternalState("__x", 1)
        val undo = internal.snapshot()
        internal.middlewares(RemoveOnRestore("__x"))

        val r = internal.restore(undo)

        assertIs<TransactionResult.Error>(r)
        assertTrue(r.exception.message!!.contains("'__x' was removed"), r.exception.message)
        assertFalse("__x" in internal.properties, "no state without a declaration")
        assertFalse("__x" in internal.snapshot().stateNames)
        assertEquals(2, internal.registerInternalState("__x", 2).value, "the name registers afresh")
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun aRemovedInternalStateIsNeverPublishedAgain() {
        // The plan's window: a declaration looked up before removeState dropped it cannot create its state.
        val store = PlanOrderStore()
        store.registerInternalState("__x", 1)
        val decl = assertNotNull(store.registry.declaration("__x"))
        store.removeState("__x")

        val failure = assertFailsWith<IllegalStateException> { materialize(decl) }

        assertTrue(failure.message!!.contains("no longer declares a state named '__x'"), failure.message)
        assertFalse("__x" in store.properties)
    }

    @Test fun typedReadsWorkAfterDisposeAndRefuseAForeignInstance() {
        val source = CodecProfileStore()
        source.visit()
        val visits = source.visits
        val draft = source.draft
        val captured = source.snapshot()
        val decoded = StoreSnapshot.decode(captured.encode())
        source.dispose()

        assertEquals(42, captured[visits], "a captured snapshot reads through its own states after dispose")
        assertEquals(SnapshotEntry.Present(42), captured.entry(visits))
        assertEquals(42, decoded[visits], "a decoded snapshot reads by name, through the state's codec")

        val other = CodecProfileStore()
        val foreign = assertFailsWith<IllegalArgumentException> { captured[other.visits] }
        assertTrue(foreign.message!!.contains("another CodecProfileStore instance"), foreign.message)
        assertEquals(42, decoded[other.visits], "decoded snapshots answer any store's state of that name")
        assertEquals(SnapshotEntry.Absent, decoded.entry(other.draft), "an unencoded state has no decoded value")
        assertEquals("unsent", captured[draft], "but a captured snapshot holds it in memory")
    }

    @Test fun typedReadsCoverDerivedStatesAndRefuseComputedOnes() {
        val store = CodecProfileStore()
        val (doubled, d) = store.derived(store.visits) { visits.value * 2 }
        try {
            store action { visits mutate 5 }
            val captured = store.snapshot()
            assertEquals(10, captured[doubled], "a captured snapshot reads a derived's state")
            assertEquals(SnapshotEntry.Absent, StoreSnapshot.decode(captured.encode()).entry(doubled))
            assertEquals(emptySet(), captured.unencodableStateNames - "draft", "derived backings are never listed")
            val computed = store.computed { visits.value + 1 }
            assertFailsWith<IllegalArgumentException> { captured[computed] }
        } finally {
            d.dispose()
        }
    }

    @Test fun aDecodedReadOfAStateWithoutACodecTeaches() {
        val labelled = LabelledStore()
        val decoded = StoreSnapshot.decode(CodecProfileStore().snapshot().encode())

        val failure = assertFailsWith<IllegalStateException> { decoded[labelled.name] }
        assertTrue(failure.message!!.contains("has no codec"), failure.message)
    }

    @Test fun restoringTwoStoresInsideAtomicRollsBothBackOnOneCorruptLeaf() {
        val first = CodecProfileStore()
        val second = CodecProfileStore()
        val source = CodecProfileStore()
        source.visit()
        val good = source.snapshot().encode()
        val corrupt = good.replace(""""visits":"42"""", """"visits":"forty-two"""")
        assertNotEquals(good, corrupt)

        var firstResult: TransactionResult<RestoreReport>? = null
        var nameInsideFrame: String? = null
        val r =
            atomic(first, second) {
                firstResult = first.restore(StoreSnapshot.decode(good), RestorePolicy.Strict)
                nameInsideFrame = first.name.value // read-your-own-writes: the restore staged
                second.restore(StoreSnapshot.decode(corrupt), RestorePolicy.Strict)
            }

        assertIs<TransactionResult.Success<RestoreReport>>(firstResult, "the good restore succeeded inside the frame")
        assertEquals("ada", nameInsideFrame, "the first restore staged its writes before the frame failed")
        assertIs<TransactionResult.Error>(r)
        val rejected = assertIs<RestoreRejectedException>(r.exception)
        val issue = assertIs<RestoreIssue.Undecodable>(rejected.issues.single())
        assertEquals("visits", issue.stateName, "the frame failed on the second store's corrupt leaf")
        assertEquals("guest", first.name.value, "the first store's restore rolled back with the frame")
        assertEquals(0, first.visits.value)
        assertEquals("guest", second.name.value, "the second store changed nothing")
    }

    @Test fun noExceptionChainContainsAStateValue() {
        val secret = "S3CR3T-7731"
        val store = CodecProfileStore()
        val header = """{"format":"holdfast.store","v":1,"schema":1,"states":{"""
        val failures =
            listOf(
                // Malformed text around a value.
                runCatching { StoreSnapshot.decode(header + """"name":"$secret""") }.exceptionOrNull(),
                runCatching { StoreSnapshot.decode(header + """"name":"$secret${'\u0001'}"},"skipped":[]}""") }
                    .exceptionOrNull(),
                runCatching { StoreSnapshot.decode(header + """"name":"$secret"},"skipped":[]} $secret""") }
                    .exceptionOrNull(),
                // A codec that cannot read the value (NumberFormatException quotes its input).
                store
                    .restore(StoreSnapshot.decode(header + """"visits":"$secret"},"skipped":[]}"""))
                    .let { (it as TransactionResult.Error).exception },
                runCatching { StoreSnapshot.decode(header + """"visits":"$secret"},"skipped":[]}""")[store.visits] }
                    .exceptionOrNull(),
                // A value of the wrong type.
                store.restore(StoreSnapshot(mapOf("visits" to secret))).let { (it as TransactionResult.Error).exception },
                // A codec that throws while encoding, quoting the value.
                runCatching { StoreSnapshot(CapturedContent(mapOf("x" to secret), codecs = mapOf("x" to Quoting))).encode() }
                    .exceptionOrNull(),
            )
        failures.forEachIndexed { i, failure ->
            val chain = generateSequence(assertNotNull(failure, "case $i threw nothing")) { it.cause }.toList()
            val texts = chain.flatMap { listOf(it.toString(), it.message.orEmpty()) + it.suppressedExceptions.map(Throwable::toString) }
            assertTrue(texts.none { secret in it }, "case $i leaks the value: $texts")
        }
        val report = store.restore(StoreSnapshot(mapOf("visits" to secret)), RestorePolicy.BestEffort).getOrThrow()
        assertFalse(secret in report.toString(), "a report holds names only: $report")
        assertFalse(secret in StoreSnapshot(mapOf("visits" to secret)).toString(), "toString never shows values")
    }

    @Test fun snapshotsCompareByValue() {
        val a = CodecProfileStore()
        val b = CodecProfileStore()
        assertEquals(a.snapshot(), b.snapshot(), "two fresh instances hold equal snapshots")
        assertEquals(a.snapshot().hashCode(), b.snapshot().hashCode())

        a.visit()
        val captured = a.snapshot()
        assertNotEquals(captured, b.snapshot())
        val decoded = StoreSnapshot.decode(captured.encode())
        assertEquals(decoded, StoreSnapshot.decode(captured.encode()), "decoded snapshots of one text are equal")
        assertNotEquals<Any>(captured, decoded, "a captured snapshot never equals a decoded one")
        assertTrue(decoded.equalsEncodable(captured), "but they are equal on the encodable projection")
        assertEquals(captured.encode(), decoded.encode(), "and encode to the same text")

        b.restore(decoded).getOrThrow()
        assertTrue(b.snapshot().equalsEncodable(captured))
        assertNotEquals(captured, b.snapshot(), "the codec-less draft did not travel, so the values differ")
    }

    @Test fun toStringNamesStatesAndRenderShowsValues() {
        val store = CodecProfileStore()
        store.visit()
        val captured = store.snapshot()

        assertEquals("StoreSnapshot(schema=1, states=[draft, lastSeen, name, tags, visits])", captured.toString())
        val rendered = captured.render()
        assertTrue(rendered.contains("visits = 42"), rendered)
        assertTrue(rendered.contains("draft = unsent"), rendered)
        val renderedDecoded = StoreSnapshot.decode(captured.encode()).render()
        assertTrue(renderedDecoded.contains("visits = \"42\""), renderedDecoded)
        assertTrue(renderedDecoded.contains("draft (not encoded)"), renderedDecoded)
        assertEquals(1, captured.schemaVersion)
    }

    @Test fun restoreRunsAsOneTransactionNamedRestore() {
        val store = CodecProfileStore()
        val ids = TransactionIds()
        store.middlewares(ids)
        store.restore(CodecProfileStore().snapshot(), RestorePolicy.Strict).getOrThrow()
        store.restore(StoreSnapshot(mapOf("ghost" to 1)))
        assertEquals(listOf("Restore", "Restore"), ids.ids)
    }

    @Test fun theExperimentalOverloadGatesADisposedStore() {
        val store = CodecProfileStore()
        store.dispose()
        val failure = assertFailsWith<IllegalStateException> { store.state(codec = IntCodec) { 0 } }
        assertTrue(failure.message!!.contains("disposed"), failure.message)
    }

    @Test fun aSameInstanceUndoThroughThePolicyOverloadRestoresTheDerived() {
        val store = CodecProfileStore()
        val (doubled, d) = store.derived(store.visits) { visits.value * 2 }
        try {
            store action { visits mutate 2 }
            val snap = store.snapshot()
            store action { visits mutate 9 }
            store.restore(snap, RestorePolicy.Strict).getOrThrow()
            assertEquals(4, doubled.value)
            assertNull(StoreSnapshot.decode(snap.encode())[doubled])
        } finally {
            d.dispose()
        }
    }

    @Test fun redactedIsOneObject() {
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"name":null},"skipped":[]}"""
        val decoded = StoreSnapshot.decode(text)
        assertEquals(text, decoded.encode(), "a withheld value is written back as null")
        val store = CodecProfileStore()
        assertSame(Redacted, decoded.entry(store.name))
        assertNull(decoded[store.name])
        val report = store.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertTrue("name" in report.kept, "a withheld value leaves its state as it is")
    }
}

/** A codec whose encode throws an exception quoting the value it was given. */
private object Quoting : StateCodec<Any> {
    override fun encode(value: Any): String = throw IllegalArgumentException("cannot encode $value")

    override fun decode(string: String): Any = string
}
