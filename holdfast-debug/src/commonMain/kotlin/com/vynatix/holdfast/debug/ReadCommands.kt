package com.vynatix.holdfast.debug

import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.State
import com.vynatix.holdfast.TransactionStatus

/** Read-only console commands; they take per-state locks only and run on the caller's thread. */
internal class ReadCommands(
    private val registry: StoreRegistry,
) {
    fun stores(): String {
        val entries = registry.entries()
        if (entries.isEmpty()) return "no stores registered — call HoldfastDebug.register(store, \"name\")"
        val rows =
            entries.map { e ->
                val flags =
                    listOfNotNull(
                        "quarantined".takeIf { e.isQuarantined },
                        e.store.activeTransaction?.let { "active-txn:${it.id}" },
                        "redacts:${e.redact.size}".takeIf { e.redact.isNotEmpty() },
                    ).joinToString(",")
                val states =
                    e.store.properties.size
                        .toString()
                listOf(
                    e.name,
                    e.className,
                    states,
                    e.journal
                        .entries()
                        .size
                        .toString(),
                    flags,
                )
            }
        return ConsoleFormat.table(listOf("NAME", "CLASS", "STATES", "JOURNAL", "FLAGS"), rows)
    }

    fun dump(entry: StoreRegistry.Entry): String {
        val store = entry.store
        val properties = store.properties.entries.sortedBy { it.key }
        val writers = entry.journal.lastWriter()
        val header =
            buildString {
                append("${entry.name} (${entry.className}) — ${properties.size} state(s)")
                store.activeTransaction?.let { append(", active txn ${it.id}") }
                if (entry.isQuarantined) append(", QUARANTINED")
            }
        if (properties.isEmpty()) return "$header\n  (no states registered yet — they register on first delegate read)"
        val rows =
            properties.map { (name, state) ->
                val flags =
                    listOfNotNull(
                        "bridge".takeIf { (state as? MutableState<*>)?.bridge != null },
                        writers[name]?.let { "last:$it" },
                    ).joinToString(" ")
                listOf(name, "= ${renderValue(entry, name, state)}", flags)
            }
        return header + "\n" + ConsoleFormat.table(null, rows, indent = "  ")
    }

    fun get(
        entry: StoreRegistry.Entry,
        args: List<String>,
    ): String {
        val name = args.firstOrNull() ?: fail("usage: get <store> <state>")
        val state = resolveState(entry, name)
        return "${entry.name}.$name = ${renderValue(entry, name, state)}"
    }

    fun journal(
        entry: StoreRegistry.Entry,
        args: List<String>,
        onlyFailed: Boolean,
    ): String {
        val n = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_JOURNAL_ROWS
        val all = entry.journal.entries()
        val selected =
            if (onlyFailed) {
                all
                    .filterIsInstance<TransactionEntry>()
                    .filter { it.bodyThrew || it.status != TransactionStatus.Committed }
            } else {
                all
            }
        if (selected.isEmpty()) {
            return if (onlyFailed) {
                "no rolled-back transactions in ${entry.name}'s journal"
            } else {
                "journal of ${entry.name} is empty"
            }
        }
        val shown = selected.takeLast(n)
        val evicted = entry.journal.evicted
        val evictedNote = if (evicted > 0) " ($evicted evicted)" else ""
        val head = "${entry.name}: ${shown.size} of ${selected.size} entries$evictedNote"
        return head + "\n" + shown.asReversed().joinToString("\n") { formatEntry(it) }
    }

    fun stats(entry: StoreRegistry.Entry): String {
        val s = entry.journal.stats()
        if (s.entries == 0) return "journal of ${entry.name} is empty"
        val avg = if (s.transactions == 0) 0L else s.totalMicros / s.transactions
        val hot =
            s.writeCounts.entries
                .sortedByDescending { it.value }
                .take(HOT_STATES)
                .joinToString(", ") { "${it.key}×${it.value}" }
        val slowest = s.slowestTransactionId?.let { " ($it)" } ?: ""
        return """
            |${entry.name}: ${s.entries} entries (${entry.journal.evicted} evicted)
            |  transactions: ${s.transactions}  committed: ${s.committed}  rolled back: ${s.rolledBack}  savepoints: ${s.savepoints}  inbound: ${s.inbound}
            |  duration: avg ${ConsoleFormat.micros(avg)}  slowest ${ConsoleFormat.micros(s.slowestMicros)}$slowest
            |  most written: ${hot.ifEmpty { "-" }}
            """.trimMargin()
    }

    fun diff(entry: StoreRegistry.Entry): String {
        val d = entry.journal.diff() ?: fail("no mark on ${entry.name}; run `mark ${entry.name}` first")
        if (d.changes.isEmpty()) return "no changes in ${entry.name} since mark #${d.markSeq} (now #${d.currentSeq})"
        val rows = d.changes.map { c -> listOf(c.state, "${c.atMark ?: UNOBSERVED} -> ${c.now}", "by ${c.lastWriter}") }
        return "${entry.name}: ${d.changes.size} state(s) changed since mark #${d.markSeq} (now #${d.currentSeq})\n" +
            ConsoleFormat.table(null, rows, indent = "  ")
    }

    private fun formatEntry(entry: JournalEntry): String {
        val time = ConsoleFormat.clock(entry.timestamp)
        return when (entry) {
            is InboundEntry -> "#${entry.seq} $time inbound\n    ${formatWrite(entry.write, discarded = false)}"
            is TransactionEntry -> formatTransaction(entry, time)
        }
    }

    private fun formatTransaction(
        entry: TransactionEntry,
        time: String,
    ): String =
        buildString {
            val status =
                when {
                    entry.bodyThrew -> "ROLLED BACK"
                    entry.status == TransactionStatus.Committed -> "committed"
                    entry.status == TransactionStatus.Active -> "committing"
                    else -> "VETOED (${entry.status.name.lowercase()})"
                }
            append("#${entry.seq} $time txn ${entry.transactionId} $status ")
            append(ConsoleFormat.micros(entry.durationMicros))
            if (entry.depth > 0) append(" savepoint(d${entry.depth})")
            entry.frameId?.let { append(" frame:$it") }
            append(" thread:${entry.threadId}")
            entry.error?.let { append("\n    error: $it") }
            val discarded = entry.status != TransactionStatus.Committed
            when {
                !entry.writesAvailable -> append("\n    (writes unavailable: completed off the owner thread)")
                entry.writes.isEmpty() -> append("\n    (no writes)")
                else -> entry.writes.forEach { append("\n    ").append(formatWrite(it, discarded)) }
            }
        }

    private fun formatWrite(
        write: JournalWrite,
        discarded: Boolean,
    ): String {
        val suffix = if (discarded) "  (discarded)" else ""
        return "${write.state}: ${write.before ?: UNOBSERVED} -> ${write.after}$suffix"
    }

    companion object {
        const val UNOBSERVED = "<unobserved>"
        private const val DEFAULT_JOURNAL_ROWS = 20
        private const val HOT_STATES = 5

        fun resolveState(
            entry: StoreRegistry.Entry,
            name: String,
        ): State<*> =
            entry.store.getState(name) ?: run {
                val names =
                    entry.store.properties.keys
                        .sorted()
                fail(
                    "unknown state '$name' on ${entry.name} (registered: ${names.joinToString(", ")}). " +
                        "States register lazily on first read — a never-read `by state` delegate is invisible here.",
                )
            }

        fun renderValue(
            entry: StoreRegistry.Entry,
            name: String,
            state: State<*>,
        ): String =
            runCatching { state.value }
                .fold(
                    onSuccess = { entry.renderer.render(name, it) },
                    onFailure = { "<read failed: ${it::class.simpleName}: ${it.message}>" },
                )
    }
}
