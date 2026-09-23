package com.vynatix.holdfast.debug

import com.vynatix.holdfast.ExperimentalStoreApi

/**
 * Where the console runs commands that mutate a store (`set`, `batch`,
 * `rewind`, `run`). Holdfast runs an action's whole commit fanout —
 * observers, bridge publishes, event drain — synchronously on the calling
 * thread, so this decides the thread every observer sees. The default runs
 * inline on the caller's thread; the Android provider installs a runner that
 * hops to the main thread with a timeout, so UI-bound observers are safe.
 */
@ExperimentalStoreApi
fun interface MutationRunner {
    fun run(block: () -> String): String

    companion object {
        /** Run on the calling thread. */
        val Inline: MutationRunner = MutationRunner { it() }
    }
}

/**
 * The platform-neutral command interpreter behind every transport (Android
 * `dumpsys`/`content call`, a JVM stdin loop, a Swift debug menu, a test).
 * One line in, one text block out; it never throws — every failure is
 * rendered as an `error:` / `REJECTED` line so a `dump()` callback can never
 * take the process down.
 *
 * Read commands (`stores`, `dump`, `get`, `journal`, …) run on the calling
 * thread and take only per-state locks. Mutating commands go through
 * [mutationRunner].
 *
 * **An empty command prints exactly one summary line and no state**, so a
 * transport that runs argument-less (Android bugreports run `dumpsys` over
 * every provider) never captures values.
 */
@ExperimentalStoreApi
class DebugConsole(
    val registry: StoreRegistry,
) {
    var mutationRunner: MutationRunner = MutationRunner.Inline

    private val reads = ReadCommands(registry)
    private val mutations = MutationCommands { mutationRunner }

    /** Tokenize [line] shell-style (quotes, `;` separators) and execute. */
    fun execute(line: String): String = execute(Tokenizer.tokenize(line))

    /** Execute already-split argv; standalone `;` tokens separate commands. */
    fun execute(args: List<String>): String {
        val commands = Tokenizer.splitCommands(args)
        if (commands.isEmpty()) return summary()
        return commands.joinToString("\n") { argv ->
            runCatching { dispatch(argv) }
                .getOrElse { failure ->
                    when (failure) {
                        is CommandFailure -> failure.message.orEmpty()
                        else -> "error: ${failure::class.simpleName}: ${failure.message}"
                    }
                }
        }
    }

    private fun summary(): String {
        val count = registry.entries().size
        return "holdfast-debug: $count store(s) registered. Pass a command; `help` lists them."
    }

    @Suppress("CyclomaticComplexMethod")
    private fun dispatch(argv: List<String>): String {
        val cmd = argv.first().lowercase()
        val rest = argv.drop(1)
        return when (cmd) {
            "help", "-h", "--help", "?" -> HELP
            "stores", "ls" -> reads.stores()
            "dump", "show" -> withStore(rest, "dump <store>") { e, _ -> reads.dump(e) }
            "get" -> withStore(rest, "get <store> <state>") { e, a -> reads.get(e, a) }
            "set" -> withStore(rest, "set <store> <state> <literal>") { e, a -> mutations.set(e, a) }
            "batch" -> withStore(rest, "batch <store> <state>=<literal> …") { e, a -> mutations.batch(e, a) }
            "journal", "log" -> withStore(rest, "journal <store> [n]") { e, a -> reads.journal(e, a, false) }
            "autopsy" -> withStore(rest, "autopsy <store> [n]") { e, a -> reads.journal(e, a, true) }
            "stats" -> withStore(rest, "stats <store>") { e, _ -> reads.stats(e) }
            "mark" -> withStore(rest, "mark <store>") { e, _ -> "marked ${e.name} at #${e.journal.mark()}" }
            "diff" -> withStore(rest, "diff <store>") { e, _ -> reads.diff(e) }
            "checkpoint", "cp" -> withStore(rest, "checkpoint <store>") { e, _ -> mutations.checkpoint(e) }
            "checkpoints", "cps" -> withStore(rest, "checkpoints <store>") { e, _ -> mutations.checkpoints(e) }
            "rewind" -> withStore(rest, "rewind <store> <checkpoint-id>") { e, a -> mutations.rewind(e, a) }
            "quarantine" -> withStore(rest, "quarantine <store> on|off|status") { e, a -> mutations.quarantine(e, a) }
            "clear" -> withStore(rest, "clear <store>") { e, _ -> clearJournal(e) }
            "verbs" -> verbs()
            "run" -> mutations.run(registry, rest)
            else -> "unknown command '${argv.first()}'; `help` lists commands"
        }
    }

    private inline fun withStore(
        args: List<String>,
        usage: String,
        body: (StoreRegistry.Entry, List<String>) -> String,
    ): String {
        val name = args.firstOrNull() ?: fail("usage: $usage")
        val entry = registry.find(name) ?: fail(unknownStore(name))
        if (entry.store.isDisposed) {
            registry.unregister(entry.name)
            fail("store '${entry.name}' is disposed")
        }
        return body(entry, args.drop(1))
    }

    private fun unknownStore(name: String): String {
        val names = registry.entries().map { it.name }
        val known = if (names.isEmpty()) "none registered" else "registered: ${names.joinToString(", ")}"
        return "unknown store '$name' ($known)"
    }

    private fun clearJournal(entry: StoreRegistry.Entry): String {
        entry.journal.clear()
        return "cleared journal of ${entry.name}"
    }

    private fun verbs(): String {
        val verbs = registry.verbs()
        if (verbs.isEmpty()) return "no verbs registered — HoldfastDebug.verb(\"name\") { args -> … }"
        return ConsoleFormat.table(listOf("VERB", "DESCRIPTION"), verbs.map { listOf(it.name, it.description) })
    }

    private companion object {
        val HELP =
            """
            |holdfast-debug commands (several per line, separated by `;`):
            |  stores                            registered stores
            |  dump <store>                      every registered state and its value
            |  get <store> <state>               one value
            |  set <store> <state> <literal>     write through a real transaction (primitives, String, enums)
            |  batch <store> k=v [k=v …]         several writes as ONE transaction (all or nothing)
            |  journal <store> [n]               last n transactions and inbound bridge updates (default 20)
            |  autopsy <store> [n]               only rolled-back transactions, with the writes they discarded
            |  stats <store>                     counts and durations over the journal
            |  mark <store> / diff <store>       stamp a position; list states changed since it
            |  checkpoint <store>                snapshot in-process (raw values, never leaves the device)
            |  checkpoints <store>               list checkpoints
            |  rewind <store> <id>               restore a checkpoint atomically
            |  quarantine <store> on|off|status  reject every action on the store until switched off
            |  clear <store>                     drop the store's journal
            |  verbs / run <verb> [args…]        app-registered debug actions
            |  help
            """.trimMargin()
    }
}
