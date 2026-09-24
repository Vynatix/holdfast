@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import com.vynatix.holdfast.testing.matcher.shouldMatchSnapshotOf
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #20, R4: reset() puts every declared state back to what its retained
// initializer computes, as one transaction.

private val resetCipher = XorCipher("reset-test-seed".encodeToByteArray())

/** Upper-cases on write and counts how often it did: `set` must never run for a reset. */
private class ShoutingTransformer : Transformer<String> {
    var sets = 0

    override fun set(value: String): String {
        sets++
        return value.uppercase()
    }

    override fun get(value: String): String = value
}

/**
 * Every shape acceptance 1 covers: a plain state, one reading another declared
 * before it, one reading a state declared after it, a transformed and an
 * encrypted state (initial values are raw, so the initializers return the
 * stored form), one reading the encrypted state (so through `Transformer.get`),
 * and states the tests leave never-read or remove.
 */
private class ProfileStore : Store<ProfileStore>() {
    val shouting = ShoutingTransformer()
    val name by state { "anon" }
    val greeting by state { "hello, ${name.value}" }
    val total by state { base.value + 1 }
    val base by state { 41 }
    val nickname by state(shouting) { "quiet" }
    val token by state(EncryptingTransformer(resetCipher)) { resetCipher.encrypt("seed-token") }
    val tokenHint by state { token.value.take(4) }
    val tags by state { listOf("a") }
    val nameLength by state { name.value.length }
}

private class Panel : Store<Panel>() {
    val count by state { 0 }
    val label by state { "idle" }
    val limit by state { 10 }
    val step by state { 1 }
}

/** `fragile`'s initializer fails while [failInit] is set. */
private class FragileStore : Store<FragileStore>() {
    var failInit = false
    val count by state { 0 }
    val fragile by state {
        check(!failInit) { "initializer fails" }
        "ok"
    }
    val after by state { "after" }
}

/** `untouched`'s initializer logs each run into [log], the list a [TransactionLog] writes too. */
private class InitOrderStore(
    val log: MutableList<String>,
) : Store<InitOrderStore>() {
    val seen by state { 0 }
    val untouched by state {
        log += "init:untouched"
        0
    }
}

/** `shadow`/`peek` read a derived and an internal state of this same store once their refs are set. */
private class ShadowPanel : Store<ShadowPanel>() {
    var tripledRef: State<Int>? = null
    var hiddenRef: State<Int>? = null
    val count by state { 0 }
    val shadow by state { tripledRef?.value ?: -1 }
    val peek by state { hiddenRef?.value ?: -1 }
}

/** `x` reads the derived [tenARef] (10 × `a`) once it is set; `y` reads 10 × `a` through `computed`. */
private class DerivedReaderStore : Store<DerivedReaderStore>() {
    var tenARef: State<Int>? = null
    val a by state { 1 }
    val tenA: State<Int> = computed { a.value * 10 }
    val x by state { (tenARef?.value ?: -1) + 1 }
    val y by state { tenA.value + 1 }
}

/** `x` reads `y` only once [cyclic] is set, and `y` always reads `x`. */
private class CycleProneStore : Store<CycleProneStore>() {
    var cyclic = false
    val x: State<Int> by state { if (cyclic) y.value else 0 }
    val y: State<Int> by state { x.value + 1 }
}

/** `mirror`'s initializer reads another store's state. */
private class MirrorStore(
    private val source: Panel,
) : Store<MirrorStore>() {
    val mirror by state { source.count.value + 100 }
}

/**
 * `a` swallows `b`'s failure. `b` fails while [failB] is set, and on its next
 * run only while [failOnce] is set.
 */
private class SwallowingStore : Store<SwallowingStore>() {
    var failB = false
    var failOnce = false
    val a by state { runCatching { b.value }.getOrDefault(-1) }
    val b by state {
        check(!failB) { "b fails" }
        if (failOnce) {
            failOnce = false
            error("b fails once")
        }
        5
    }
}

/** `a` swallows the cycle `b` closes through it; a fresh store ends with a = 0, b = 1. */
private class CaughtCycleStore : Store<CaughtCycleStore>() {
    val a: State<Int> by state { runCatching { b.value }.getOrDefault(0) }
    val b: State<Int> by state { a.value + 1 }
}

/** `echo` reads [ResetProbe.count]: materialized for the first time inside the probe's reset. */
private class ResetEcho(
    private val probe: ResetProbe,
) : Store<ResetEcho>() {
    val echo by state { probe.count.value + 1000 }
}

/**
 * `probe` reads [echo]'s state once [reach] is set; `boom`, declared after it,
 * fails while [failInit] is set.
 */
private class ResetProbe : Store<ResetProbe>() {
    lateinit var echo: ResetEcho
    var reach = false
    var failInit = false
    val count by state { 0 }
    val probe by state { if (reach) echo.echo.value else 0 }
    val boom by state {
        check(!failInit) { "boom" }
        "ok"
    }
}

/** A probe with its echo: `probe` and `boom` read, `echo` never read, `count` committed at 5, [ResetProbe.reach] set. */
private fun armedProbe(): ResetProbe {
    val probe = ResetProbe().also { it.echo = ResetEcho(it) }
    assertEquals(0, probe.probe.value)
    assertEquals("ok", probe.boom.value)
    probe action { count mutate 5 }
    probe.reach = true
    return probe
}

/** Tries to reset its own store from an initializer. */
private class ResettingInitStore : Store<ResettingInitStore>() {
    val plain by state { 0 }
    val resetter by state {
        reset()
        1
    }
}

private class PublishLog<T : Any> : Bridge<T> {
    val published = mutableListOf<T>()

    override fun observe(observer: (T) -> Unit): Disposable = Disposable { }

    override fun publish(value: T): Boolean {
        published += value
        return true
    }
}

private class TransactionLog<V : Store<V>>(
    private val log: MutableList<String>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "started:${context.transaction.id}"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        val written = context.transaction.modifiedStates.size
        log += "completed:${context.transaction.id}:$written"
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        log += "error:${context.transaction.id}:${error.message}"
    }
}

/** Subscribe to every state of [states] and record each commit-time fire (the initial fire is dropped). */
private fun recordFires(vararg states: Pair<String, State<*>>): Pair<Map<String, MutableList<Any>>, Disposable> {
    val fires = states.associate { (name, _) -> name to mutableListOf<Any>() }
    val subs =
        states.map { (name, state) ->
            @Suppress("UNCHECKED_CAST")
            (state as State<Any>).effect { fires.getValue(name) += this }
        }
    fires.values.forEach { it.clear() }
    return fires to Disposable { subs.forEach { it.dispose() } }
}

class ResetTest {
    @Test fun resetMakesTheSnapshotEqualAFreshStoresSnapshot() {
        val store = ProfileStore()
        val (doubled, d) = store.derived(store.base) { base.value * 2 }
        val fresh = ProfileStore()
        val (freshDoubled, fd) = fresh.derived(fresh.base) { base.value * 2 }
        try {
            store action {
                name mutate "ada"
                greeting mutate "custom"
                total mutate 7
                base mutate 1
                nickname mutate "loud"
                token mutate "secret"
                tokenHint mutate "zzzz"
                tags mutate listOf("x", "y")
            }
            store.removeState("tags")
            // `nameLength` is never read: materialized by reset() from the
            // committed "ada" (3), then reset from the reset "anon" (4).
            assertEquals(2, doubled.value)

            assertIs<TransactionResult.Success<Unit>>(store.reset())

            assertEquals(fresh.snapshot().rawValues, store.snapshot().rawValues, "reset == a fresh store")
            assertEquals(fresh.snapshot(), store.snapshot(), "R4 acceptance, literally: the snapshots compare equal")
            assertEquals(4, store.snapshot().rawValues["nameLength"], "the never-read state read the reset name")
            assertEquals("hello, anon", store.greeting.value, "a cross reference reads the reset value")
            assertEquals(42, store.total.value, "a forward reference reads the reset value")
            assertEquals(
                resetCipher.encrypt("seed-token"),
                store.snapshot().rawValues["token"],
                "the ciphertext equals the fresh store's: encrypted once, by the initializer, never again by set",
            )
            assertEquals("seed-token", store.token.value)
            assertEquals(
                "seed",
                store.tokenHint.value,
                "a cross reference to an encrypted state reads it decrypted, as a fresh store's first read does",
            )
            assertEquals("quiet", store.nickname.value, "the raw initial value, never upper-cased by set")
            assertEquals(freshDoubled.value, doubled.value, "the derived recomputed from its reset source")
            assertEquals(82, doubled.value)
        } finally {
            d.dispose()
            fd.dispose()
        }
    }

    @Test fun aResetStoreMatchesANewStoreThroughTheHarness() =
        storeTest {
            val store = ProfileStore()
            store action {
                name mutate "ada"
                base mutate 1
                token mutate "secret"
                tokenHint mutate "zzzz"
            }
            assertIs<TransactionResult.Success<Unit>>(store.reset())
            track(store) shouldMatchSnapshotOf ProfileStore()
        }

    @Test fun observersFireOnceForEachChangedStateAndNeverForAnUnchangedOne() {
        val store = Panel()
        store action {
            count mutate 5
            label mutate "busy"
            step mutate 3
        }
        store action { step mutate 1 } // back at its initial value
        val (fires, subs) =
            recordFires("count" to store.count, "label" to store.label, "limit" to store.limit, "step" to store.step)
        try {
            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertEquals(listOf<Any>(0), fires.getValue("count"))
            assertEquals(listOf<Any>("idle"), fires.getValue("label"))
            assertEquals(emptyList(), fires.getValue("limit"), "never written: unchanged, silent (distinct = false)")
            assertEquals(emptyList(), fires.getValue("step"), "written back to its initial value: unchanged, silent")

            fires.values.forEach { it.clear() }
            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertTrue(fires.values.all { it.isEmpty() }, "resetting a store at its initial values fires nothing")
        } finally {
            subs.dispose()
        }
    }

    @Test fun theInitializersOutputIsStagedRaw() {
        val store = ProfileStore()
        store action { nickname mutate "loud" }
        assertEquals("LOUD", store.nickname.value)
        val setsBefore = store.shouting.sets

        assertIs<TransactionResult.Success<Unit>>(store.reset())
        assertEquals(setsBefore, store.shouting.sets, "Transformer.set never ran for the reset")
        assertEquals("quiet", store.snapshot().rawValues["nickname"])
    }

    @Test fun resetInsideAnActionIsASavepoint() {
        val store = Panel()
        store action { count mutate 5 }
        val rolledBack =
            store action {
                label mutate "outer"
                assertIs<TransactionResult.Success<Unit>>(reset())
                assertEquals(0, count.value, "the action reads the reset value of its own savepoint")
                assertEquals("idle", label.value, "the reset overrides the action's pending write")
                error("abort")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(5, store.count.value, "the outer rollback discarded the reset")
        assertEquals("idle", store.label.value)

        val committed =
            store action {
                label mutate "outer"
                reset().getOrThrow()
                step mutate 9
            }
        assertIs<TransactionResult.Success<Unit>>(committed)
        assertEquals(0, store.count.value)
        assertEquals("idle", store.label.value)
        assertEquals(9, store.step.value, "a write after the reset stands")
    }

    @Test fun aNestedResetOverridesAPendingWriteEvenWhenTheCommittedValueIsInitial() {
        val store = Panel()
        val (fires, subs) = recordFires("count" to store.count)
        try {
            store action {
                count mutate 7
                reset().getOrThrow()
            }
            assertEquals(0, store.count.value, "compared with the pending 7, not the committed 0")
            assertEquals(listOf<Any>(0), fires.getValue("count"), "one commit; the action wrote count")
        } finally {
            subs.dispose()
        }
    }

    @Test fun anUnchangedEncryptedStateStaysSilent() {
        val store = ProfileStore()
        val tokenBridge = PublishLog<String>()
        store { token bridge tokenBridge }
        val (fires, subs) = recordFires("token" to store.token)
        try {
            store action { name mutate "ada" }
            tokenBridge.published.clear()
            fires.values.forEach { it.clear() }

            assertIs<TransactionResult.Success<Unit>>(store.reset())
            val silent = "an unchanged encrypted state stays silent: raw is compared with raw"
            assertEquals(emptyList(), fires.getValue("token"), silent)
            assertEquals(emptyList(), tokenBridge.published, silent)
            assertEquals("anon", store.name.value)
        } finally {
            subs.dispose()
        }
    }

    @Test fun derivedBackingsAndInternalStatesAreNotReset() {
        val store = Panel()
        store action { count mutate 3 }
        val (tripled, d) = store.derived(store.count) { count.value * 3 } // backing registered at 9
        store action { count mutate 0 } // recompute: 0
        assertEquals(0, tripled.value)
        val hidden = store.registerInternalState("__reset_probe", 0)
        store action { hidden mutate 5 }
        val (fires, subs) = recordFires("tripled" to tripled)
        try {
            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertEquals(0, tripled.value, "the backing was not staged back to its creation-time 9")
            assertEquals(emptyList(), fires.getValue("tripled"), "no source changed, so the derived stays silent")
            assertEquals(5, hidden.value, "an internal state has no initializer to reset from")
        } finally {
            subs.dispose()
            d.dispose()
        }
    }

    @Test fun aReRunInitializerReadsThisStoresDerivedAndInternalStatesAtTheirCommittedValues() {
        val store = ShadowPanel()
        store action { count mutate 3 }
        val (tripled, d) = store.derived(store.count) { count.value * 3 } // backing registered at 9
        store action { count mutate 0 } // recompute: 0
        val hidden = store.registerInternalState("__reset_shadow", 0)
        store action { hidden mutate 5 }
        store.tripledRef = tripled
        store.hiddenRef = hidden
        store action {
            shadow mutate 42
            peek mutate 42
        }
        try {
            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertEquals(0, store.shadow.value, "read the derived at its committed 0, not its creation-time 9")
            assertEquals(0, tripled.value, "reading the backing did not stage its creation-time value")
            assertEquals(5, store.peek.value, "read the internal state at its committed 5, not its registered 0")
            assertEquals(5, hidden.value, "reading the internal state did not reset it")
        } finally {
            d.dispose()
        }
    }

    @Test fun anInitializerReadsADerivedAtItsPreResetValueAndAComputedAtItsResetValue() {
        val store = DerivedReaderStore()
        val (tenA, d) = store.derived(store.a) { a.value * 10 }
        store.tenARef = tenA
        try {
            assertEquals(11, store.x.value)
            assertEquals(11, store.y.value)
            store action { a mutate 5 }
            assertEquals(50, tenA.value)

            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertEquals(1, store.a.value)
            assertEquals(10, tenA.value, "the derived recomputed once the reset committed")
            assertEquals(51, store.x.value, "x read the derived at its pre-reset 50: unlike a fresh store's 11")
            assertEquals(11, store.y.value, "y read a through computed, at its reset value, as a fresh store does")
        } finally {
            d.dispose()
        }
    }

    @Test fun bridgesReceiveThePublishedValues() {
        val store = Panel()
        val countBridge = PublishLog<Int>()
        val limitBridge = PublishLog<Int>()
        store {
            count bridge countBridge
            limit bridge limitBridge
        }
        store action { count mutate 5 }
        countBridge.published.clear()

        assertIs<TransactionResult.Success<Unit>>(store.reset())
        assertEquals(listOf(0), countBridge.published, "the reset value, published once")
        assertEquals(emptyList(), limitBridge.published, "an unchanged state publishes nothing")
    }

    @Test fun aThrowingInitializerRollsTheWholeResetBack() {
        val store = FragileStore()
        store.fragile
        store action {
            count mutate 5
            after mutate "changed"
        }
        val (fires, subs) = recordFires("count" to store.count, "after" to store.after)
        try {
            store.failInit = true
            val r = store.reset()
            assertIs<TransactionResult.Error>(r)
            assertEquals("initializer fails", r.exception.message)
            assertEquals(5, store.count.value, "count's reset, staged before fragile threw, rolled back")
            assertEquals("changed", store.after.value)
            assertTrue(fires.values.all { it.isEmpty() }, "no observer saw a partial reset")

            store.failInit = false
            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertEquals(0, store.count.value)
            assertEquals("after", store.after.value)
        } finally {
            subs.dispose()
        }
    }

    @Test fun aNeverReadStateWhoseInitializerThrowsFailsTheResetAsAnError() {
        val store = FragileStore()
        store action { count mutate 5 }
        store.failInit = true
        val log = mutableListOf<String>()
        store.middlewares(TransactionLog(log))

        val r = store.reset()
        assertIs<TransactionResult.Error>(r, "materializing it failed before the transaction opened")
        assertEquals("initializer fails", r.exception.message)
        assertEquals(listOf("started:Reset", "error:Reset:initializer fails"), log)
        assertEquals(5, store.count.value)
    }

    @Test fun neverReadAndRemovedStatesAreMaterializedBeforeTheResetsTransactionOpens() {
        val store = FragileStore()
        store.count
        store.fragile
        store.removeState("after") // declared after fragile
        store.failInit = true

        val r = store.reset()
        assertIs<TransactionResult.Error>(r)
        assertEquals("initializer fails", r.exception.message)
        assertTrue(store.hasState("after"), "after was materialized before the transaction opened, so it stays live")
    }

    @Test fun aNeverReadStatesInitializerRunsBeforeTheResetsTransactionOpens() {
        val log = mutableListOf<String>()
        val store = InitOrderStore(log)
        store.seen
        store.middlewares(TransactionLog(log))

        assertIs<TransactionResult.Success<Unit>>(store.reset())
        assertEquals(
            listOf("init:untouched", "started:Reset", "init:untouched", "completed:Reset:0"),
            log,
            "materialized outside the reset's transaction and locks, then re-run inside it",
        )
    }

    @Test fun anInitializerCycleDuringResetFailsTheResetAsAnError() {
        val store = CycleProneStore()
        assertEquals(1, store.y.value)
        store action { x mutate 5 }
        store.cyclic = true

        val r = store.reset()
        assertIs<TransactionResult.Error>(r)
        assertIs<IllegalStateException>(r.exception)
        assertContains(
            r.exception.message.orEmpty(),
            "State initializer cycle: CycleProneStore.x → CycleProneStore.y → CycleProneStore.x",
        )
        assertEquals(5, store.x.value, "nothing was reset")
    }

    @Test fun middlewareSeesTheResetAsOneTransaction() {
        val store = Panel()
        store action {
            count mutate 5
            label mutate "busy"
        }
        val log = mutableListOf<String>()
        store.middlewares(TransactionLog(log))

        assertIs<TransactionResult.Success<Unit>>(store.reset())
        assertEquals(listOf("started:Reset", "completed:Reset:2"), log, "one transaction writing the two changed states")
    }

    @Test fun anInitializerReadsAnotherStoreAtItsCommittedValue() {
        val source = Panel()
        val store = MirrorStore(source)
        store action { mirror mutate 0 }
        source action { count mutate 7 }

        var freshMirror = -1
        val r =
            source action {
                count mutate 9
                store.reset().getOrThrow()
                freshMirror = MirrorStore(source).mirror.value
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(107, store.mirror.value, "the committed 7, not the pending 9")
        assertEquals(freshMirror, store.mirror.value, "exactly what a fresh store's first read saw at that moment")
    }

    @Test fun aStateWhoseFailureAnotherInitializerCatchesStillFailsTheReset() {
        val store = SwallowingStore()
        assertEquals(5, store.a.value)
        store action { b mutate 9 }
        store.failB = true

        val r = store.reset()
        assertIs<TransactionResult.Error>(r, "b is not dropped from the reset because a caught its failure")
        assertEquals("b fails", r.exception.message)
        assertEquals(5, store.a.value, "a's reset rolled back")
        assertEquals(9, store.b.value, "b keeps its pre-reset value")
    }

    @Test fun aCaughtTransientFailureRunsAgainAsInAFreshStore() {
        val store = SwallowingStore()
        assertEquals(5, store.a.value)
        store action { b mutate 9 }
        store.failOnce = true

        assertIs<TransactionResult.Success<Unit>>(store.reset())
        assertEquals(-1, store.a.value, "a caught b's first failure")
        assertEquals(5, store.b.value, "b's initializer ran again, and its reset stands")
        val fresh = SwallowingStore().apply { failOnce = true }
        assertEquals(fresh.snapshot().rawValues, store.snapshot().rawValues, "what a fresh store holds")
    }

    @Test fun aCaughtCycleLeavesItsStateToBeResetInItsTurn() {
        val store = CaughtCycleStore()
        assertEquals(0, store.a.value)
        assertEquals(1, store.b.value)
        store action { b mutate 9 }

        assertIs<TransactionResult.Success<Unit>>(store.reset())
        assertEquals(0, store.a.value)
        assertEquals(1, store.b.value, "b's cycle through a was caught; b was reset from the reset a")
        assertEquals(CaughtCycleStore().snapshot().rawValues, store.snapshot().rawValues, "what a fresh store holds")
    }

    @Test fun aStateMaterializedInsideTheResetReadsCommittedValues() {
        val probe = armedProbe()
        assertIs<TransactionResult.Success<Unit>>(probe.reset())
        assertEquals(
            1005,
            probe.echo.echo.value,
            "echo, materialized inside the reset, read the committed count 5, not the reset's pending 0",
        )
        assertEquals(1005, probe.probe.value)
        assertEquals(0, probe.count.value)
    }

    @Test fun aStateMaterializedInsideARolledBackResetKeepsWhatItReadCommitted() {
        val aborted = armedProbe()
        val r =
            aborted action {
                reset().getOrThrow()
                error("abort")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(5, aborted.count.value)
        assertEquals(0, aborted.probe.value)
        assertEquals(1005, aborted.echo.echo.value, "committed at once, from committed values: nothing to roll back")

        val failed = armedProbe()
        failed.failInit = true
        val f = failed.reset()
        assertIs<TransactionResult.Error>(f)
        assertEquals("boom", f.exception.message)
        assertEquals(5, failed.count.value, "count's reset rolled back")
        assertEquals(0, failed.probe.value, "probe's reset rolled back")
        assertEquals(1005, failed.echo.echo.value, "echo read the committed count 5, not the rolled-back reset 0")
        assertEquals(ResetEcho(failed).echo.value, failed.echo.echo.value, "what a fresh store reads now")
    }

    @Test fun resetFromAnInitializerFailsFast() {
        val store = ResettingInitStore()
        val e = assertFailsWith<IllegalStateException> { store.resetter }
        assertContains(e.message.orEmpty(), "Cannot reset ResettingInitStore")
        assertContains(e.message.orEmpty(), "initializer of ResettingInitStore.resetter")
    }
}
