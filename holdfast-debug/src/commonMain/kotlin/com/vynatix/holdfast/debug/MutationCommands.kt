package com.vynatix.holdfast.debug

import com.vynatix.holdfast.State
import com.vynatix.holdfast.TransactionResult

/**
 * Console commands that change a store. Each one parses and validates on the
 * caller's thread, then runs the actual transaction through the
 * [MutationRunner] the console was configured with.
 */
internal class MutationCommands(
    private val runner: () -> MutationRunner,
) {
    fun set(
        entry: StoreRegistry.Entry,
        args: List<String>,
    ): String {
        if (args.size < 2) fail("usage: set <store> <state> <literal>")
        val name = args[0]
        val state = ReadCommands.resolveState(entry, name)
        val value = parseFor(entry, name, state, args.drop(1).joinToString(" "))
        return runner().run {
            val before = ReadCommands.renderValue(entry, name, state)
            when (val result = applyWrites(entry.store, listOf(state to value))) {
                is TransactionResult.Success ->
                    "OK ${result.transaction.id}: $name $before -> ${ReadCommands.renderValue(entry, name, state)}"
                is TransactionResult.Error -> rejected(result)
            }
        }
    }

    fun batch(
        entry: StoreRegistry.Entry,
        args: List<String>,
    ): String {
        if (args.isEmpty()) fail("usage: batch <store> <state>=<literal> [<state>=<literal> …]")
        val writes =
            args.map { arg ->
                val eq = arg.indexOf('=')
                if (eq <= 0) fail("REJECTED (parse) '$arg': expected <state>=<literal>")
                val name = arg.substring(0, eq)
                val state = ReadCommands.resolveState(entry, name)
                Triple(name, state, parseFor(entry, name, state, arg.substring(eq + 1)))
            }
        return runner().run {
            when (val result = applyWrites(entry.store, writes.map { (_, state, value) -> state to value })) {
                is TransactionResult.Success ->
                    "OK ${result.transaction.id}: " +
                        writes.joinToString(", ") { (name, state, _) ->
                            "$name=${ReadCommands.renderValue(entry, name, state)}"
                        }
                is TransactionResult.Error -> rejected(result)
            }
        }
    }

    fun checkpoint(entry: StoreRegistry.Entry): String {
        val cp = entry.checkpoint()
        return "checkpoint ${cp.id} taken for ${entry.name} (${cp.snapshot.size} state(s), kept in-process)"
    }

    fun checkpoints(entry: StoreRegistry.Entry): String {
        val cps = entry.checkpoints()
        if (cps.isEmpty()) return "no checkpoints for ${entry.name}"
        val rows =
            cps.map {
                listOf(
                    "#${it.id}",
                    ConsoleFormat.clock(it.takenAt),
                    "${it.snapshot.size} state(s)",
                    it.snapshot.stateNames
                        .sorted()
                        .joinToString(","),
                )
            }
        return ConsoleFormat.table(listOf("ID", "TIME", "SIZE", "STATES"), rows)
    }

    fun rewind(
        entry: StoreRegistry.Entry,
        args: List<String>,
    ): String {
        val id = args.firstOrNull()?.removePrefix("#")?.toIntOrNull() ?: fail("usage: rewind <store> <checkpoint-id>")
        val cp =
            entry.checkpoint(id)
                ?: fail("no checkpoint #$id for ${entry.name} (`checkpoints ${entry.name}` lists them)")
        return runner().run {
            when (val result = entry.rewind(cp)) {
                is TransactionResult.Success -> {
                    val missing = entry.store.properties.keys - cp.snapshot.stateNames
                    buildString {
                        append("OK ${result.transaction.id}: ${entry.name} rewound to checkpoint #$id")
                        append(" (${cp.snapshot.size} state(s))")
                        if (missing.isNotEmpty()) {
                            append("\n  not rewound (registered after the checkpoint): ")
                            append(missing.sorted().joinToString(", "))
                        }
                        append("\n  note: attached bridges re-published the restored values")
                    }
                }
                is TransactionResult.Error -> rejected(result)
            }
        }
    }

    fun quarantine(
        entry: StoreRegistry.Entry,
        args: List<String>,
    ): String =
        when (args.firstOrNull()?.lowercase()) {
            "on", "true" -> {
                entry.setQuarantined(true)
                "${entry.name} quarantined: every action now rolls back with StoreQuarantinedException"
            }
            "off", "false" -> {
                entry.setQuarantined(false)
                "${entry.name} released from quarantine"
            }
            null, "status" -> "${entry.name} is ${if (entry.isQuarantined) "QUARANTINED" else "not quarantined"}"
            else -> fail("usage: quarantine <store> on|off|status")
        }

    fun run(
        registry: StoreRegistry,
        args: List<String>,
    ): String {
        val name = args.firstOrNull() ?: fail("usage: run <verb> [args…]")
        val verb = registry.verb(name) ?: fail("unknown verb '$name' (`verbs` lists them)")
        return runner().run {
            runCatching { verb.body(args.drop(1)) }
                .fold(
                    onSuccess = { result -> formatVerbResult(name, result) },
                    onFailure = { "FAILED $name: ${it::class.simpleName}: ${it.message}" },
                )
        }
    }

    private fun formatVerbResult(
        verb: String,
        result: Any?,
    ): String =
        when (result) {
            null, Unit -> "OK $verb"
            is TransactionResult.Success<*> -> {
                val value = result.value
                "OK $verb (${result.transaction.id})" + if (value == null || value == Unit) "" else ": $value"
            }
            is TransactionResult.Error -> "FAILED $verb: ${rejected(result)}"
            else -> "OK $verb: $result"
        }

    /** Parse [literal] into the runtime class of the state's current value, or [fail]. */
    private fun parseFor(
        entry: StoreRegistry.Entry,
        name: String,
        state: State<*>,
        literal: String,
    ): Any {
        if (name in entry.redact) {
            fail("REJECTED (redacted) $name: writes are refused to keep its value out of the console")
        }
        val current =
            runCatching { state.value }.getOrElse {
                fail("REJECTED (read) $name: cannot read current value to infer its type: ${it.message}")
            }
        return when (val parsed = LiteralParser.parseLike(current, literal)) {
            is LiteralParser.Outcome.Ok -> parsed.value
            is LiteralParser.Outcome.Fail -> fail("REJECTED (parse) $name: ${parsed.message}")
        }
    }

    private fun rejected(result: TransactionResult.Error): String {
        val e = result.exception
        val stage = if (e is StoreQuarantinedException) "quarantined" else "transaction"
        return "REJECTED ($stage) ${result.transaction.id}: ${e::class.simpleName}: ${e.message}"
    }
}
