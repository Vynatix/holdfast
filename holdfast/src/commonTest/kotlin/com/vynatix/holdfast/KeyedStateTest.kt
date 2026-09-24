@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.middleware.ProfilingMiddleware
import com.vynatix.holdfast.middleware.TransactionSample
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #20, R7 acceptance (identity and participation): a keyed entry is the
// same State on every get while it lives, and takes part in actions,
// rollbacks, frames, effect and derived states like any declared state.

private class KsDocs : Store<KsDocs>() {
    var created = mutableListOf<String>()
    val prefix by state { "doc" }
    val docs by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { id ->
        created += id
        "${prefix.value}:$id"
    }
    val counts by keyedState<String, Int> { 0 }
}

private class KsTagged : Store<KsTagged>() {
    val plain by state { 0 }
    val secrets by keyedState<String, String>(tags = setOf(StateTag.Secret)) { "" }
    val drafts by keyedState<Int, String>(tags = setOf(StateTag.UserAuthored)) { "" }
}

private class KsTrimming : Store<KsTrimming>() {
    val names by keyedState<Int, String>(transformer = KsTrim) { " initial " }
}

private object KsTrim : Transformer<String> {
    override fun set(value: String): String = value.trim()

    override fun get(value: String): String = value
}

private class KsCyclic : Store<KsCyclic>() {
    val nodes: KeyedState<Int, Int> by keyedState { n -> if (n == 0) 0 else nodes[n - 1].value + 1 }
    val loop: KeyedState<Int, Int> by keyedState { n -> loop[n].value }
}

private class KsWritingInit : Store<KsWritingInit>() {
    val other by state { 0 }
    var fail = false
    val bad by keyedState<Int, Int> { n ->
        check(!fail) { "initializer fails for $n" }
        this@KsWritingInit { other mutate n }
        n
    }
}

private class KsClashing : Store<KsClashing>() {
    val taken by state { 0 }
    val family by keyedState<Int, Int> { 0 }
}

/** Declares a family over [store] under a name the store already declares as a state. */
private class KsFamilyOverState(
    store: KsClashing,
) {
    val taken by store.keyedState<Int, Int> { 0 }
}

/** Declares a state over [store] under a name the store already declares as a family. */
private class KsStateOverFamily(
    store: KsClashing,
) {
    val family by store.state { 0 }
}

/** Declares a family over [store] under a name the store already declares as a family. */
private class KsFamilyOverFamily(
    store: KsClashing,
) {
    val family by store.keyedState<Int, Int> { 0 }
}

/** A member family declared over [store]: two instances run the same declaration. */
private class KsSharedFamily(
    store: KsCounter,
) {
    val shared by store.keyedState<String, Int> { 7 }
}

private class KsCounter : Store<KsCounter>() {
    val n by state { 0 }
}

class KeyedStateTest {
    @Test fun getReturnsTheSameStateEveryTimeWhileTheEntryLives() {
        val store = KsDocs()
        val first = store.docs["a"]

        assertSame(first, store.docs["a"], "one State per live entry")
        assertSame(first, store.docs.getOrNull("a"))
        assertEquals("doc:a", first.value)
        assertEquals(listOf("a"), store.created, "the initializer runs once per entry, given its key")
        assertEquals(setOf("a"), store.docs.entries.keys)
    }

    @Test fun readsNeverCreateAnEntry() {
        val store = KsDocs()

        assertNull(store.docs.getOrNull("a"))
        assertFalse("a" in store.docs)
        assertTrue(store.docs.entries.isEmpty())
        assertEquals(emptyList(), store.created)
    }

    @Test fun entriesAreListedInCreationOrder() {
        val store = KsDocs()
        listOf("c", "a", "b").forEach { store.docs[it] }

        val entries = store.docs.entries
        assertEquals(listOf("c", "a", "b"), entries.keys.toList())
        assertEquals(listOf("doc:c", "doc:a", "doc:b"), entries.values.map { it.value })
    }

    @Test fun entriesTakePartInActionsAndCommitOnce() {
        val store = KsDocs()
        val seen = mutableListOf<String>()
        store.docs["a"] effect { seen += this }

        val r =
            store action {
                docs["a"] mutate "one"
                docs["a"] update { "$it+two" }
                docs["b"] mutate "b!"
                docs["a"].value
            }

        assertEquals("one+two", r.getOrThrow(), "read-your-own-writes inside the action")
        assertEquals(listOf("doc:a", "one+two"), seen, "the observer fires once, at commit")
        assertEquals("b!", store.docs["b"].value)
        val txn = assertIs<TransactionResult.Success<String>>(r).transaction
        assertEquals(TransactionStatus.Committed, txn.status)
    }

    @Test fun aRollbackDiscardsEntryWritesButCreatingAnEntryIsNotAWrite() {
        val store = KsDocs()
        store.docs["a"]

        val r =
            store action {
                docs["a"] mutate "lost"
                docs["fresh"] mutate "lost too"
                error("roll back")
            }

        assertIs<TransactionResult.Error>(r)
        assertEquals("doc:a", store.docs["a"].value)
        assertEquals("doc:fresh", store.docs.getOrNull("fresh")?.value, "the entry stays, at its initial value")
    }

    @Test fun aSavepointRollbackKeepsTheOuterWrites() {
        val store = KsDocs()

        store action {
            docs["a"] mutate "outer"
            val inner =
                this action {
                    docs["a"] mutate "inner"
                    error("inner fails")
                }
            assertIs<TransactionResult.Error>(inner)
        }

        assertEquals("outer", store.docs["a"].value)
    }

    @Test fun entriesOfTwoStoresCommitAndRollBackWithAnAtomicFrame() {
        val left = KsDocs()
        val right = KsDocs()

        atomic(left, right) {
            left { docs["x"] mutate "L" }
            right { counts["x"] mutate 7 }
        }.getOrThrow()
        assertEquals("L", left.docs["x"].value)
        assertEquals(7, right.counts["x"].value)

        val failed =
            atomic(left, right) {
                left { docs["x"] mutate "lost" }
                right { counts["x"] mutate 99 }
                error("the frame fails")
            }
        assertIs<TransactionResult.Error>(failed)
        assertEquals("L", left.docs["x"].value)
        assertEquals(7, right.counts["x"].value)
    }

    @Test fun effectObservesAnEntryLikeAnyState() {
        val store = KsDocs()
        val seen = mutableListOf<Int>()
        val sub = store.counts["k"] effect { seen += this }

        store action { counts["k"] mutate 1 }
        store action { counts["other"] mutate 5 }
        store action { counts["k"] update { it + 1 } }
        sub.dispose()
        store action { counts["k"] mutate 10 }

        assertEquals(listOf(0, 1, 2), seen, "only its own key's commits, until disposed")
    }

    @Test fun derivedAndDerivedStateFollowEntries() {
        val store = KsDocs()
        val a = store.counts["a"]
        val b = store.counts["b"]
        val (sumState, _) = store.derived(a, b) { a.value + b.value }
        val total = store.derivedState(a, b) { a.value + b.value }

        store action {
            counts["a"] mutate 2
            counts["b"] mutate 3
        }

        assertEquals(5, sumState.value)
        assertEquals(5, total.value)
        total.dispose()
    }

    @Test fun aTransformerAppliesToEveryEntry() {
        val store = KsTrimming()

        assertEquals(" initial ", store.names[1].value, "an initial value is stored raw, as a state's is")
        store action { names[1] mutate "  padded  " }
        assertEquals("padded", store.names[1].value)
    }

    @Test fun anEntryInitializerMayReadStatesButNotWrite() {
        val store = KsWritingInit()

        val refused = assertFailsWith<IllegalStateException> { store.bad[3] }
        assertContains(refused.message.orEmpty(), "initializer of KsWritingInit.bad[*]")
        assertNull(store.bad.getOrNull(3), "a failed initializer creates no entry")
        assertEquals(0, store.other.value)
    }

    @Test fun aThrowingInitializerCreatesNothingAndRunsAgainNextTime() {
        val store = KsWritingInit()
        store.fail = true
        assertFailsWith<IllegalStateException> { store.bad[1] }
        assertFalse(1 in store.bad)
        store.fail = false

        // Now the initializer runs again — and is refused its write, like any.
        val second = assertFailsWith<IllegalStateException> { store.bad[1] }
        assertContains(second.message.orEmpty(), "Cannot write KsWritingInit.other")
    }

    @Test fun entryInitializersResolveOtherEntriesAndCyclesThrow() {
        val store = KsCyclic()

        assertEquals(3, store.nodes[3].value)
        assertEquals(setOf(3, 2, 1, 0), store.nodes.entries.keys)
        val cycle = assertFailsWith<IllegalStateException> { store.loop[1] }
        assertContains(cycle.message.orEmpty(), "State initializer cycle")
        assertFalse(1 in store.loop, "the cycle created no entry")
    }

    @Test fun familiesAndStatesShareOneSetOfNames() {
        val store = KsClashing()

        val onState = assertFailsWith<IllegalStateException> { KsFamilyOverState(store) }
        assertContains(onState.message.orEmpty(), "already declares 'taken' as a state")
        val onFamily = assertFailsWith<IllegalStateException> { KsStateOverFamily(store) }
        assertContains(onFamily.message.orEmpty(), "already declares 'family' as a keyed state family")
        val familyOnFamily = assertFailsWith<IllegalStateException> { KsFamilyOverFamily(store) }
        assertContains(familyOnFamily.message.orEmpty(), "already declares 'family' as a keyed state family")
        assertEquals(0, store.family[1].value, "the family the store declared is untouched")
    }

    @Test fun theSameLocalDeclarationRunningAgainBindsToTheFamily() {
        val store = KsCounter()

        fun declare(): KeyedState<String, Int> {
            val shared: KeyedState<String, Int> by store.keyedState { 1 }
            return shared
        }

        val first = declare()
        first["k"]
        assertSame(first, declare())
        assertTrue("k" in declare())
    }

    @Test fun theSameMemberDeclarationRunningAgainBindsToTheFamily() {
        val store = KsCounter()
        val first = KsSharedFamily(store)
        val second = KsSharedFamily(store)

        assertSame(first.shared, second.shared)
        assertSame(first.shared["k"], second.shared["k"])
    }

    @Test fun anEntryCarriesItsFamilysTags() {
        val store = KsTagged()
        val secret = store.secrets["s"]
        val draft = store.drafts[1]

        assertEquals(setOf<StateTag>(StateTag.Secret), secret.tags)
        assertEquals(setOf<StateTag>(StateTag.UserAuthored), draft.tags)
        assertEquals(listOf<State<*>>(secret), store.taggedStates(StateTag.Secret))
        assertEquals(listOf<State<*>>(draft), store.taggedStates(StateTag.UserAuthored))
        assertEquals(Redacted, secret.displayValue("hunter2"), "a Secret family's entry is redacted where it is shown")
    }

    @Test fun aRefusedTagCombinationFailsTheFamilysDeclaration() {
        val store = KsCounter()
        val refused =
            assertFailsWith<IllegalArgumentException> {
                val both: KeyedState<Int, Int> by store.keyedState(tags = setOf(StateTag.Secret, StateTag.UserAuthored)) { 0 }
                both[1]
            }
        assertContains(refused.message.orEmpty(), "KsCounter.both cannot be tagged both Secret and UserAuthored")
    }

    @Test fun entriesAreNotPropertiesAndNamesNeverShowAKey() {
        val store = KsDocs()
        val entry = store.docs["user@example.com"]

        assertEquals(setOf("prefix"), store.properties.keys, "an entry is not a property")
        assertFalse(store.hasState("docs"))
        assertEquals("MutableState(KsDocs.docs[*])", entry.toString())
        assertEquals("KeyedState(KsDocs.docs)", store.docs.toString())
        assertFalse("user@example.com" in store.snapshot().toString())
    }

    @Test fun profilingNamesAnEntryAfterItsFamily() {
        val store = KsDocs()
        val samples = mutableListOf<TransactionSample>()
        val profiler = ProfilingMiddleware<KsDocs> { samples += it }
        store.middlewares(profiler)

        store action {
            docs["a"] mutate "x"
            docs["b"] mutate "y"
            prefix mutate "p"
        }

        assertEquals(setOf("docs[*]", "prefix"), samples.single().modifiedStates)
        assertEquals(mapOf("docs[*]" to 1L, "prefix" to 1L), profiler.profile().stateWriteCounts)
    }

    @Test fun internalAddressNamesTheFamilyAndKey() {
        val store = KsDocs()
        val entry = store.docs["a"]

        assertEquals(KeyedAddress("docs", "a"), store.internalKeyedAddress(entry))
        assertNull(store.internalKeyedAddress(store.prefix), "a declared state has no keyed address")
        assertNull(KsDocs().internalKeyedAddress(entry), "another store's entry has none here")
        assertEquals("KeyedAddress(docs[*])", KeyedAddress("docs", "secret key").toString())

        store.docs.evict("a")
        assertEquals(KeyedAddress("docs", "a"), store.internalKeyedAddress(entry), "a stale handle keeps its address")
        assertEquals(store.internalKeyedAddress(store.docs["a"]), store.internalKeyedAddress(entry), "an address names a slot")
    }

    @Test fun theMembershipListenerHearsEveryEntryAddedAndEvicted() {
        val store = KsDocs()
        val heard = mutableListOf<String>()
        val listening =
            store.internalObserveKeyedMembership(
                object : KeyedMembershipListener {
                    override fun onEntryAdded(
                        family: String,
                        key: Any,
                        entry: State<*>,
                    ) {
                        heard += "+$family[$key]=${entry.value}"
                    }

                    override fun onEntryEvicted(
                        family: String,
                        key: Any,
                        entry: State<*>,
                    ) {
                        heard += "-$family[$key]"
                    }
                },
            )

        store.docs["a"]
        store.docs["a"]
        store.counts["n"]
        store.docs.evict("a")
        listening.dispose()
        store.docs["b"]

        assertEquals(listOf("+docs[a]=doc:a", "+counts[n]=0", "-docs[a]"), heard)
    }

    @Test fun aCodecFamilyAcceptsIntCodecValues() {
        val store = KsCounter()
        val ints: KeyedState<Int, Int> by store.keyedState(codec = IntCodec, keyCodec = IntCodec) { it * 10 }
        assertEquals(30, ints[3].value)
    }
}
