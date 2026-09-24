@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.reflect.KProperty
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #20, R5: every state is declared when its property is delegated, and
// materialized (its initializer run) only when first needed.

private class EagerStore(
    private val onInit: (String) -> Unit = {},
) : Store<EagerStore>() {
    val count by state {
        onInit("count")
        0
    }
    val label by state {
        onInit("label")
        "init"
    }
    val tags by state {
        onInit("tags")
        setOf("a")
    }
}

/** `total` reads `base`, which is declared after it. */
private class ForwardStore : Store<ForwardStore>() {
    var baseRuns = 0
    val total by state { base.value + 1 }
    val base by state {
        baseRuns++
        41
    }
}

private class TypedStore : Store<TypedStore>() {
    val count: State<Int> by state { 0 }
    val names: State<List<String>> by state { emptyList() }
    val scores: State<Map<String, Int>> by state(distinct = true) { emptyMap() }
}

private class WritingInitStore : Store<WritingInitStore>() {
    var writeOnInit = true
    val target by state { 0 }
    val writer by state {
        if (writeOnInit) target mutate 1
        2
    }
    val actor by state {
        action { target mutate 3 }
        4
    }
    val framer by state {
        atomic(this) { }
        5
    }
}

private class EmittingInitStore : EventfulStore<EmittingInitStore, String>() {
    val announced by state {
        emit("hello")
        0
    }
}

private class CycleStore : Store<CycleStore>() {
    val x: State<Int> by state { y.value + 1 }
    val y: State<Int> by state { x.value + 1 }
    val calm by state { 0 }
}

private class FlakyStore : Store<FlakyStore>() {
    var attempts = 0
    val calm by state { "ok" }
    val flaky by state {
        attempts++
        if (attempts == 1) error("first attempt fails")
        attempts
    }
}

private abstract class ShadowBase<S : ShadowBase<S>> : Store<S>() {
    open val shared: State<Int> by state { 1 }
}

private class ShadowSub : ShadowBase<ShadowSub>() {
    override val shared: State<Int> by state { 2 }
}

private class LocalProbeStore : Store<LocalProbeStore>() {
    val member by state { 0 }
}

/** A helper object, not a store, that declares a state on the store it is given. */
private class DraftSection(
    store: LocalProbeStore,
    seed: String,
) {
    val draft: State<String> by store.state { seed }
}

/** A different helper class declaring the same name on the same store. */
private class OtherDraftSection(
    store: LocalProbeStore,
) {
    val draft: State<String> by store.state { "other" }
}

/** `b`'s initializer reads `a`. */
private class DependentStore : Store<DependentStore>() {
    val a by state { 0 }
    val b by state { a.value + 1 }
}

/** An initializer that disposes its own store before returning. */
private class SelfDisposingStore : Store<SelfDisposingStore>() {
    val doomed by state {
        dispose()
        1
    }
}

/** A wrapper that forwards only `getValue` — as code compiled before `provideDelegate` existed does. */
private class GetValueOnlyDelegate<T : Any>(
    private val inner: StateDelegate<T>,
) {
    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): State<T> = inner.getValue(thisRef, property)
}

private class LegacyDelegateStore : Store<LegacyDelegateStore>() {
    val wrapped by GetValueOnlyDelegate(state { 3 })
    val plain by state { 4 }
}

/** Lazily reads its store's declarations — the shape of a #21 attachment. */
private class DeclarationView(
    private val store: Store<*>,
) {
    val names: List<String> get() = store.declarations().map { it.name }
}

/** A base class that captures `this` while it is being constructed (#21 T3). */
private abstract class AttachingBase<S : AttachingBase<S>> : Store<S>() {
    val baseState by state { "base" }
    val view: DeclarationView = DeclarationView(this)
    val namesDuringBaseInit: List<String> = view.names
}

private class AttachingSub : AttachingBase<AttachingSub>() {
    val first by state { 1 }
    val second by state { 2 }
}

/** Initializers that read across two stores without forming a cycle. */
private class LeftStore : Store<LeftStore>() {
    lateinit var right: RightStore
    val z by state { 1 }
    val x by state { right.y.value + z.value }
}

private class RightStore : Store<RightStore>() {
    lateinit var left: LeftStore
    val y by state { left.z.value + 10 }
    val loop by state { left.x.value }
}

class EagerRegistrationTest {
    @Test fun untouchedStoreSnapshotHasEveryDeclaredState() {
        val store = EagerStore()
        val snap = store.snapshot()
        assertEquals(setOf("count", "label", "tags"), snap.stateNames)
        assertEquals(0, snap.rawValues["count"])
        assertEquals("init", snap.rawValues["label"])
        assertEquals(setOf("a"), snap.rawValues["tags"])
    }

    @Test fun declaringRunsNoInitializer() {
        val ran = mutableListOf<String>()
        val store = EagerStore { ran += it }
        assertEquals(emptyList(), ran, "constructing the store declares its states without running initializers")
        assertEquals(listOf("count", "label", "tags"), store.declarations().map { it.name }, "in declaration order")
        assertTrue(store.declarations().all { it.materialized == null })
        assertEquals(emptyMap(), store.properties, "nothing is materialized yet")

        store.label
        assertEquals(listOf("label"), ran, "a read materializes only the state it reads")
        store.snapshot()
        assertEquals(listOf("label", "count", "tags"), ran, "snapshot() materializes the rest, once each")
    }

    @Test fun forwardReferencesAreSafe() {
        val store = ForwardStore()
        val snap = store.snapshot()
        assertEquals(42, snap.rawValues["total"], "total's initializer materialized base, declared after it")
        assertEquals(41, snap.rawValues["base"])
        assertEquals(1, store.baseRuns, "base's initializer ran once, not again for its own turn")
    }

    @Test fun explicitPropertyTypesStillInfer() {
        val store = TypedStore()
        assertEquals(0, store.count.value)
        assertEquals(emptyList(), store.names.value)
        store action { names mutate listOf("a") }
        assertEquals(listOf("a"), store.names.value)
        assertEquals(setOf("count", "names", "scores"), store.snapshot().stateNames)
    }

    @Test fun anInitializerThatWritesFailsFast() {
        val store = WritingInitStore()
        val write = assertFailsWith<IllegalStateException> { store.writer }
        assertContains(write.message.orEmpty(), "write WritingInitStore.target")
        assertContains(write.message.orEmpty(), "initializer of WritingInitStore.writer")
        assertEquals(0, store.target.value, "the refused write never landed")

        val action = assertFailsWith<IllegalStateException> { store.actor }
        assertContains(action.message.orEmpty(), "open an action on WritingInitStore")
        assertContains(action.message.orEmpty(), "initializer of WritingInitStore.actor")

        val frame = assertFailsWith<IllegalStateException> { store.framer }
        assertContains(frame.message.orEmpty(), "open an atomic(...) frame")
        assertEquals(0, store.target.value)

        // Nothing was published; the next read runs the initializer again.
        store.writeOnInit = false
        assertEquals(2, store.writer.value)
    }

    @Test fun anInitializerThatEmitsFailsFast() {
        val store = EmittingInitStore()
        // Read inside an action, so an emit would otherwise stage into it.
        val r = store action { announced.value }
        assertIs<TransactionResult.Error>(r)
        assertIs<IllegalStateException>(r.exception)
        assertContains(r.exception.message.orEmpty(), "emit an event on EmittingInitStore")
        assertContains(r.exception.message.orEmpty(), "initializer of EmittingInitStore.announced")

        // Read outside any action: the initializer refusal, not "emit outside an action".
        val outside = assertFailsWith<IllegalStateException> { store.announced }
        assertContains(outside.message.orEmpty(), "emit an event on EmittingInitStore")
        assertContains(outside.message.orEmpty(), "initializer of EmittingInitStore.announced")
    }

    @Test fun aFirstReadInsideAnActionSeedsFromCommittedValues() {
        val store = DependentStore()
        store.a // a is live; b has never been read
        val rolledBack =
            store action {
                a mutate 5
                assertEquals(5, a.value, "outside an initializer, the action still reads its own write")
                val seen = b.value
                error("abort after b = $seen")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals("abort after b = 1", rolledBack.exception.message, "b's initializer read the committed a, not 5")
        assertEquals(0, store.a.value)
        assertEquals(1, store.b.value, "the rolled-back write never leaked into b's committed initial value")

        // A committing action seeds b the same way: as a fresh store would.
        val other = DependentStore()
        val committed =
            other action {
                a mutate 5
                b.value
            }
        assertIs<TransactionResult.Success<Int>>(committed)
        assertEquals(1, committed.value)
        assertEquals(5, other.a.value)
        assertEquals(1, other.b.value)
    }

    @Test fun anInitializerCycleOnOneThreadThrowsInsteadOfOverflowing() {
        val store = CycleStore()
        val e = assertFailsWith<IllegalStateException> { store.x }
        assertContains(e.message.orEmpty(), "State initializer cycle: CycleStore.x → CycleStore.y → CycleStore.x")

        // snapshot() meets the same cycle, and fails the same way.
        assertFailsWith<IllegalStateException> { store.snapshot() }
        // The cycle left nothing half-materialized; unrelated states still work.
        assertEquals(0, store.calm.value)
        assertNull(store.getState("x"))
        assertNull(store.getState("y"))
    }

    @Test fun aThrowingInitializerStaysRetryable() {
        val store = FlakyStore()
        val e = assertFailsWith<IllegalStateException> { store.snapshot() }
        assertEquals("first attempt fails", e.message)
        val snap = store.snapshot()
        assertEquals(mapOf("calm" to "ok", "flaky" to 2), snap.rawValues)
        assertEquals(2, store.attempts)
    }

    @Test fun removedStatesKeepTheirDeclaration() {
        val store = EagerStore()
        store action { count mutate 9 }
        store.removeState("count")
        assertNull(store.getState("count"))
        assertEquals(0, store.snapshot().rawValues["count"], "re-materialized from the retained initializer")

        store action { count mutate 5 }
        store.clearStates()
        assertEquals(setOf("count", "label", "tags"), store.snapshot().stateNames)
        assertEquals(0, store.count.value)
    }

    @Test fun disposeReleasesDeclarations() {
        val store = EagerStore()
        store.snapshot()
        val declared = store.declarations()
        assertEquals(3, declared.size)

        store.dispose()
        assertEquals(emptyList(), store.declarations())
        assertTrue(declared.all { it.materialized == null }, "no declaration keeps a disposed store's states alive")
        assertFailsWith<IllegalStateException> { store.count }
        assertFailsWith<IllegalStateException> { store.snapshot() }
    }

    @Test fun anInitializerThatOutlivesDisposePublishesNothing() {
        val store = SelfDisposingStore()
        val e = assertFailsWith<IllegalStateException> { store.doomed }
        assertEquals("store disposed", e.message)
        assertTrue(store.registry.states.isEmpty(), "nothing was published on the disposed store")
        assertEquals(emptyList(), store.declarations())
    }

    @Test fun anAttachmentFromBaseInitSeesEveryDeclarationAfterConstruction() {
        val store = AttachingSub()
        assertEquals(listOf("baseState"), store.namesDuringBaseInit, "subclass properties are not declared yet")
        assertEquals(listOf("baseState", "first", "second"), store.view.names, "resolved lazily, it sees them all")
    }

    @Test fun aSubclassRedeclaringABaseStateFailsFast() {
        val e = assertFailsWith<IllegalStateException> { ShadowSub() }
        assertContains(e.message.orEmpty(), "ShadowSub already declares a state named 'shared'")
    }

    @Test fun aLocalDelegateRunAgainBindsToTheSameState() {
        val store = LocalProbeStore()
        val seen = mutableListOf<State<Int>>()
        repeat(2) {
            val local: State<Int> by store.state { 7 }
            seen += local
        }
        assertSame(seen[0], seen[1], "the same local declaration evaluated twice shares one state")
        assertEquals(listOf("member", "local"), store.declarations().map { it.name })

        val e =
            assertFailsWith<IllegalStateException> {
                val member: State<Int> by store.state { 1 }
                member.value
            }
        assertContains(e.message.orEmpty(), "already declares a state named 'member'")
    }

    @Test fun aHelperClassInstantiatedTwiceBindsToTheSameState() {
        val store = LocalProbeStore()
        val first = DraftSection(store, "first")
        val second = DraftSection(store, "second") // same member property: must not throw
        assertEquals(listOf("member", "draft"), store.declarations().map { it.name }, "one declaration for both")
        // Read through the second instance first: the first declaration's initializer still wins.
        assertEquals("first", second.draft.value)
        assertSame(first.draft, second.draft, "both instances share one state")
        assertEquals(mapOf("member" to 0, "draft" to "first"), store.snapshot().rawValues)

        // A different class's property with the same name is another declaration site: it fails fast.
        val e = assertFailsWith<IllegalStateException> { OtherDraftSection(store) }
        assertContains(e.message.orEmpty(), "already declares a state named 'draft'")
        assertEquals(listOf("member", "draft"), store.declarations().map { it.name })
    }

    @Test fun aDelegateReadWithoutProvideDelegateDeclaresOnFirstRead() {
        val store = LegacyDelegateStore()
        assertEquals(listOf("plain"), store.declarations().map { it.name }, "the wrapper never declared its state")

        val first = store.wrapped
        assertSame(first, store.wrapped, "later reads reuse the declaration made by the first")
        assertEquals(listOf("plain", "wrapped"), store.declarations().map { it.name })
        assertEquals(mapOf("plain" to 4, "wrapped" to 3), store.snapshot().rawValues)
    }

    @Test fun registerInternalStateFetchesAnExistingInternalState() {
        val store = EagerStore()
        val first = store.registerInternalState("__x", 0)
        val again = store.registerInternalState("__x", 9)
        assertSame(first, again, "create-or-fetch: the second call returns the first state")
        assertSame<State<*>?>(first, store.getState("__x"))
        assertEquals(0, again.value, "the first registration's value stands")

        // Removing it drops its declaration too: the next registration starts afresh.
        store.removeState("__x")
        val fresh = store.registerInternalState("__x", 5)
        assertTrue(fresh !== first, "a removed internal state is not revived")
        assertEquals(5, fresh.value)
    }

    @Test fun registerInternalStateOverADeclaredNameFailsFast() {
        val store = EagerStore()
        val beforeRead = assertFailsWith<IllegalStateException> { store.registerInternalState("count", 7) }
        assertContains(beforeRead.message.orEmpty(), "already declares a state named 'count'")

        assertEquals(0, store.count.value, "the declared state keeps its own initializer")
        val afterRead = assertFailsWith<IllegalStateException> { store.registerInternalState("count", 7) }
        assertContains(afterRead.message.orEmpty(), "already declares a state named 'count'")
        assertEquals(0, store.count.value)
        assertEquals(listOf("count", "label", "tags"), store.declarations().map { it.name })
    }

    @Test fun registerDerivedBackingStateNeverSharesAName() {
        val store = EagerStore()
        assertFailsWith<IllegalStateException> { store.registerDerivedBackingState("count", 0, emptyList()) }

        store.registerDerivedBackingState("__d", 0, emptyList())
        assertFailsWith<IllegalStateException> { store.registerDerivedBackingState("__d", 1, emptyList()) }
        assertFailsWith<IllegalStateException> { store.registerInternalState("__d", 1) }

        store.registerInternalState("__i", 0)
        assertFailsWith<IllegalStateException> { store.registerDerivedBackingState("__i", 1, emptyList()) }
    }

    @Test fun aLocalDelegateNamedLikeAnInternalStateFailsFast() {
        val store = LocalProbeStore()
        store.registerInternalState("local", 0)
        val e =
            assertFailsWith<IllegalStateException> {
                val local: State<Int> by store.state { 1 }
                local.value
            }
        assertContains(e.message.orEmpty(), "already declares a state named 'local'")
    }

    @Test fun aSharedThreadIdModelNeitherInventsCyclesNorDeadlocks() {
        // wasmJs reports thread id 0 for every caller. Model that here: both
        // stores share one graph whose every thread is 0, and all use stays on
        // this one thread — as it must on that single-threaded target.
        val graph = InitializerGraph { 0L }
        val left = LeftStore()
        val right = RightStore()
        left.right = right
        right.left = left
        left.initializerGraph = graph
        right.initializerGraph = graph

        // Nested, cross-store, non-cyclic: x → y → z. No false cycle.
        assertEquals(12, left.x.value)
        assertEquals(11, right.y.value)
        assertEquals(12, right.loop.value, "a state reading an already-live state of the other store")
        assertEquals(0, graph.waitingCount, "a single thread never registers as waiting")

        // A genuine cycle on the one thread still throws instead of hanging.
        val cycle = CycleStore()
        cycle.initializerGraph = graph
        assertFailsWith<IllegalStateException> { cycle.snapshot() }
        assertEquals(0, cycle.calm.value)
        assertEquals(0, graph.waitingCount)
    }
}
