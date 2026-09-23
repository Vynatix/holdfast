package com.vynatix.holdfast.debug

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Observable
import com.vynatix.holdfast.TransactionResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DebugConsoleTest {
    private fun registered(
        options: DebugOptions = DebugOptions(),
        redact: Set<String> = setOf("secret"),
    ): Triple<StoreRegistry, DebugConsole, CounterStore> {
        val (registry, console) = newConsole(options)
        val store = CounterStore().also { it.touchAll() }
        registry.register(store, "counter", redact = redact)
        return Triple(registry, console, store)
    }

    // ---- summary / discovery ---------------------------------------------------------

    @Test
    fun emptyCommandPrintsOneLineWithoutStateNamesOrValues() {
        val (_, console, _) = registered()
        val out = console.execute(emptyList())
        assertEquals(1, out.lines().size)
        assertContains(out, "1 store(s)")
        assertFalse(out.contains("counter"))
        assertFalse(out.contains("hello"))
        assertEquals(out, console.execute("   "))
    }

    @Test
    fun storesListsRegisteredStores() {
        val (_, console, _) = registered()
        val out = console.execute("stores")
        assertContains(out, "counter")
        assertContains(out, "CounterStore")
        assertContains(out, "redacts:1")
    }

    @Test
    fun unknownCommandAndStoreAreReportedNotThrown() {
        val (_, console, _) = registered()
        assertContains(console.execute("frobnicate"), "unknown command")
        assertContains(console.execute("dump nope"), "unknown store 'nope'")
        assertContains(console.execute("dump nope"), "counter")
        assertContains(console.execute("get counter nope"), "unknown state 'nope'")
    }

    @Test
    fun helpListsEveryCommand() {
        val (_, console, _) = registered()
        val help = console.execute("help")
        listOf(
            "stores",
            "dump",
            "get",
            "set",
            "batch",
            "journal",
            "autopsy",
            "stats",
            "mark",
            "diff",
            "checkpoint",
            "rewind",
            "quarantine",
            "verbs",
            "run",
        ).forEach { assertContains(help, it) }
    }

    @Test
    fun storeNamesResolveCaseInsensitivelyWhenUnique() {
        val (_, console, _) = registered()
        assertContains(console.execute("get COUNTER count"), "counter.count = 0")
    }

    // ---- reads ---------------------------------------------------------------------

    @Test
    fun dumpShowsEveryStateAndRedactsSecrets() {
        val (_, console, store) = registered()
        store.action { count mutate 7 }
        val out = console.execute("dump counter")
        assertContains(out, "count")
        assertContains(out, "= 7")
        assertContains(out, "hello")
        assertContains(out, ValueRenderer.REDACTED)
        assertFalse(out.contains("s3cret"))
        assertContains(out, "last:")
    }

    @Test
    fun getReadsOneValueAndTruncatesLongOnes() {
        val (_, console, store) = registered(options = DebugOptions(valueMaxChars = 5))
        store.action { label mutate "abcdefghij" }
        assertEquals("counter.label = abcde…(+5)", console.execute("get counter label"))
    }

    // ---- set / batch -----------------------------------------------------------------

    @Test
    fun setWritesPrimitivesThroughARealTransaction() {
        val (_, console, store) = registered()
        assertContains(console.execute("set counter count 42"), "OK ConsoleWrite: count 0 -> 42")
        assertEquals(42, store.count.value)
        console.execute("set counter total 9")
        assertEquals(9L, store.total.value)
        console.execute("set counter ratio 0.25")
        assertEquals(0.25, store.ratio.value)
        console.execute("set counter flag true")
        assertEquals(true, store.flag.value)
        assertContains(console.execute("set counter label Two words here"), "hello -> Two words here")
        assertEquals("Two words here", store.label.value)
    }

    @Test
    fun setRunsTheTransformer() {
        val (_, console, store) = registered()
        console.execute("set counter shout whisper")
        assertEquals("WHISPER", store.shout.value)
    }

    @Test
    fun setRejectsBadLiteralsAndUnsupportedTypesWithoutTouchingState() {
        val (_, console, store) = registered()
        assertContains(console.execute("set counter count abc"), "REJECTED (parse)")
        assertEquals(0, store.count.value)
        assertContains(console.execute("set counter items x"), "unsupported type")
        assertContains(console.execute("set counter secret x"), "redacted")
        assertContains(console.execute("set counter"), "usage")
    }

    @Test
    fun setIsRolledBackByMiddlewareValidation() {
        val (_, console, store) = registered()
        store.middlewares(NonNegativeCount())
        val out = console.execute("set counter count -1")
        assertContains(out, "REJECTED (transaction)")
        assertContains(out, "count must not be negative")
        assertEquals(0, store.count.value)
    }

    @Test
    fun batchIsAllOrNothing() {
        val (_, console, store) = registered()
        store.middlewares(NonNegativeCount())
        assertContains(console.execute("batch counter count=5 label=five"), "OK")
        assertEquals(5, store.count.value)
        assertEquals("five", store.label.value)

        assertContains(console.execute("batch counter label=six count=-6"), "REJECTED")
        assertEquals(5, store.count.value)
        assertEquals("five", store.label.value)

        assertContains(console.execute("batch counter label=seven count=zzz"), "REJECTED (parse)")
        assertEquals("five", store.label.value)
        assertContains(console.execute("batch counter nonsense"), "expected <state>=<literal>")
    }

    @Test
    fun mutationsGoThroughTheRunner() {
        val (_, console, store) = registered()
        var calls = 0
        console.mutationRunner =
            MutationRunner { block ->
                calls++
                block()
            }
        console.execute("set counter count 1")
        console.execute("batch counter count=2")
        console.execute("get counter count")
        assertEquals(2, calls)
        assertEquals(2, store.count.value)
    }

    // ---- journal -------------------------------------------------------------------

    @Test
    fun journalRecordsCommitsWithBeforeAndAfter() {
        val (_, console, store) = registered()
        store.action { count mutate 1 }
        store.action {
            count mutate 2
            label mutate "two"
        }
        val out = console.execute("journal counter")
        assertContains(out, "committed")
        assertContains(out, "count: 1 -> 2")
        assertContains(out, "label: hello -> two")
        assertContains(out, "count: 0 -> 1")
    }

    @Test
    fun autopsyShowsDiscardedWritesAndTheError() {
        val (_, console, store) = registered()
        store.action { count mutate 1 }
        val result =
            store.action {
                count mutate 99
                error("boom")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(1, store.count.value)

        val out = console.execute("autopsy counter")
        assertContains(out, "ROLLED BACK")
        assertContains(out, "IllegalStateException: boom")
        assertContains(out, "count: 1 -> 99  (discarded)")
        assertFalse(out.contains("count: 0 -> 1"), "autopsy must not list committed transactions")

        // The full journal still has both, and the current value is untouched.
        assertContains(console.execute("journal counter"), "count: 0 -> 1")
        assertEquals("counter.count = 1", console.execute("get counter count"))
    }

    @Test
    fun savepointsAreRecordedWithDepth() {
        val (_, console, store) = registered()
        store.action {
            count mutate 1
            action { count mutate 2 }
        }
        val entries =
            console.registry
                .find("counter")!!
                .journal
                .entries()
                .filterIsInstance<TransactionEntry>()
        assertEquals(listOf(1, 0), entries.map { it.depth })
        assertContains(console.execute("journal counter"), "savepoint(d1)")
        assertEquals(2, store.count.value)
    }

    @Test
    fun inboundBridgeUpdatesAreJournaled() {
        val (_, console, store) = registered()
        var push: ((Int) -> Unit)? = null
        val source =
            Observable<Int> { observer ->
                push = observer
                Disposable { push = null }
            }
        store { count observeFrom source }
        push!!(77)
        assertEquals(77, store.count.value)
        val out = console.execute("journal counter")
        assertContains(out, "inbound")
        assertContains(out, "count: 0 -> 77")
        assertContains(console.execute("dump counter"), "last:inbound")
    }

    @Test
    fun ringBufferEvictsOldestEntries() {
        val (_, console, store) = registered(options = DebugOptions(journalCapacity = 3))
        repeat(5) { i -> store.action { count mutate i + 1 } }
        val entries =
            console.registry
                .find("counter")!!
                .journal
                .entries()
        assertEquals(3, entries.size)
        assertEquals(listOf(3L, 4L, 5L), entries.map { it.seq })
        assertContains(console.execute("journal counter"), "2 evicted")
        assertContains(console.execute("journal counter 1"), "1 of 3 entries")
    }

    @Test
    fun statsAggregateTheRing() {
        val (_, console, store) = registered()
        store.action { count mutate 1 }
        store.action { count mutate 2 }
        store.action {
            label mutate "x"
            error("nope")
        }
        val out = console.execute("stats counter")
        assertContains(out, "transactions: 3")
        assertContains(out, "committed: 2")
        assertContains(out, "rolled back: 1")
        assertContains(out, "count×2")
    }

    @Test
    fun clearEmptiesTheJournal() {
        val (_, console, store) = registered()
        store.action { count mutate 1 }
        console.execute("clear counter")
        assertContains(console.execute("journal counter"), "empty")
    }

    // ---- mark / diff -------------------------------------------------------------------

    @Test
    fun diffListsOnlyStatesChangedSinceMark() {
        val (_, console, store) = registered()
        store.action { count mutate 1 }
        assertContains(console.execute("diff counter"), "no mark")
        console.execute("mark counter")
        assertContains(console.execute("diff counter"), "no changes")
        store.action { label mutate "changed" }
        store.action {
            count mutate 5
            error("rolled back, must not show")
        }
        val out = console.execute("diff counter")
        assertContains(out, "1 state(s) changed")
        assertContains(out, "label")
        assertContains(out, "hello -> changed")
        assertFalse(out.lines().drop(1).any { it.contains("count") }, "rolled-back write must not show: $out")
    }

    @Test
    fun severalCommandsPerLine() {
        val (_, console, _) = registered()
        val out = console.execute("mark counter; set counter count 3; diff counter")
        assertContains(out, "marked counter")
        assertContains(out, "OK")
        assertContains(out, "0 -> 3")
        // argv form, as dump(fd, writer, args) delivers it
        val argv = console.execute(listOf("get", "counter", "count", ";", "get", "counter", "flag"))
        assertEquals("counter.count = 3\ncounter.flag = false", argv)
    }

    // ---- checkpoints -----------------------------------------------------------------

    @Test
    fun checkpointAndRewindRoundTripRawValues() {
        val (_, console, store) = registered()
        store.action {
            count mutate 1
            shout mutate "loud"
        }
        assertContains(console.execute("checkpoint counter"), "checkpoint 1 taken")
        store.action {
            count mutate 2
            shout mutate "louder"
        }
        assertEquals("LOUDER", store.shout.value)

        assertContains(console.execute("checkpoints counter"), "#1")
        val out = console.execute("rewind counter 1")
        assertContains(out, "OK")
        assertContains(out, "checkpoint #1")
        assertEquals(1, store.count.value)
        // restore bypasses Transformer.set: the raw uppercase value is not re-transformed
        assertEquals("LOUD", store.shout.value)
        assertContains(console.execute("rewind counter 9"), "no checkpoint #9")
    }

    @Test
    fun checkpointsAreBounded() {
        val (_, console, _) = registered(options = DebugOptions(checkpointCapacity = 2))
        repeat(3) { console.execute("checkpoint counter") }
        val out = console.execute("checkpoints counter")
        assertFalse(out.contains("#1 "))
        assertContains(out, "#2")
        assertContains(out, "#3")
    }

    // ---- quarantine ------------------------------------------------------------------

    @Test
    fun quarantineRejectsEveryActionUntilReleased() {
        val (_, console, store) = registered()
        assertContains(console.execute("quarantine counter on"), "quarantined")
        val result = store.action { count mutate 1 }
        assertIs<TransactionResult.Error>(result)
        assertIs<StoreQuarantinedException>(result.exception)
        assertEquals(0, store.count.value)
        assertContains(console.execute("set counter count 5"), "REJECTED (quarantined)")
        assertContains(console.execute("stores"), "quarantined")
        assertContains(console.execute("autopsy counter"), "StoreQuarantinedException")

        assertContains(console.execute("quarantine counter off"), "released")
        assertIs<TransactionResult.Success<*>>(store.action { count mutate 1 })
        assertContains(console.execute("quarantine counter"), "not quarantined")
    }

    // ---- verbs -----------------------------------------------------------------------

    @Test
    fun verbsRunAppDefinedActions() {
        val (registry, console, store) = registered()
        assertContains(console.execute("verbs"), "no verbs")
        registry.verb("bump", "add n to count") { args ->
            val n = args.firstOrNull()?.toInt() ?: 1
            store.action { count update { it + n } }
        }
        registry.verb("noop") { }
        registry.verb("explode") { error("kaboom") }
        assertContains(console.execute("verbs"), "add n to count")
        assertContains(console.execute("run bump 5"), "OK bump")
        assertEquals(5, store.count.value)
        assertEquals("OK noop", console.execute("run noop"))
        assertContains(console.execute("run explode"), "FAILED explode: IllegalStateException: kaboom")
        assertContains(console.execute("run missing"), "unknown verb")
    }

    // ---- registry lifecycle ----------------------------------------------------------

    @Test
    fun unregisterAndDisposeRemoveStores() {
        val (registry, console) = newConsole()
        val a = CounterStore().also { it.touchAll() }
        val b = CounterStore().also { it.touchAll() }
        val handle = registry.register(a, "a")
        registry.register(b, "b")
        assertEquals(listOf("a", "b"), registry.entries().map { it.name })

        handle.dispose()
        assertEquals(listOf("b"), registry.entries().map { it.name })
        assertContains(console.execute("dump a"), "unknown store")
        // the inert journal no longer records
        a.action { count mutate 1 }
        assertTrue(registry.find("a") == null)

        b.dispose()
        assertTrue(registry.entries().isEmpty())
        assertContains(console.execute(emptyList()), "0 store(s)")
    }

    @Test
    fun reRegisteringANameReplacesTheEntry() {
        val (registry, console) = newConsole()
        val a = CounterStore().also { it.touchAll() }
        val b = CounterStore().also { it.touchAll() }
        registry.register(a, "s")
        registry.register(b, "s")
        assertEquals(1, registry.entries().size)
        b.action { count mutate 3 }
        assertEquals("s.count = 3", console.execute("get s count"))
    }

    @Test
    fun defaultNameIsTheClassName() {
        val (registry, _) = newConsole()
        registry.register(CounterStore())
        assertEquals("CounterStore", registry.entries().single().name)
    }

    @Test
    fun lazyStatesAppearOnceRegistered() {
        val (registry, console) = newConsole()
        val store = CounterStore()
        registry.register(store, "lazy")
        assertContains(console.execute("dump lazy"), "no states registered yet")
        store.action { count mutate 1 }
        assertContains(console.execute("dump lazy"), "count")
        assertContains(console.execute("journal lazy"), "count: <unobserved> -> 1")
    }
}
