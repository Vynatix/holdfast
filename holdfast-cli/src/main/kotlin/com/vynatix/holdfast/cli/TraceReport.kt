package com.vynatix.holdfast.cli

/** Render a parsed trace as a human-readable transaction tree. */
internal fun renderTrace(trace: TraceFile): String =
    buildString {
        val rolledBack = trace.txns.count { it.status == "rolled-back" }
        appendLine(
            "trace: ${trace.totalLines} lines" +
                (if (trace.malformedLines > 0) " (${trace.malformedLines} malformed skipped)" else "") +
                ", ${trace.txns.size} transactions, $rolledBack rolled back",
        )
        val childrenByParent = trace.txns.filter { it.savepoint }.groupBy { it.parent }
        for ((label, roots) in trace.txns.filterNot { it.savepoint }.groupBy { it.label }) {
            appendLine(label)
            roots.forEach { root -> appendTxn(root, childrenByParent, indent = "  ") }
        }
        val frames = trace.txns.filter { it.frame != null }.groupBy { it.frame }
        if (frames.isNotEmpty()) {
            appendLine("frames (cross-store):")
            frames.forEach { (frame, participants) ->
                appendLine("  $frame: ${participants.joinToString(", ") { "${it.label}/${it.id}" }}")
            }
        }
        val orphans = trace.txns.filterNot { it.ended }
        if (orphans.isNotEmpty()) {
            appendLine("orphans (started, never ended — process death, or attribution lost to a thread hop):")
            orphans.forEach { appendLine("  ${it.id} (${it.label})") }
        }
    }

private fun StringBuilder.appendTxn(
    txn: TraceTxn,
    childrenByParent: Map<String?, List<TraceTxn>>,
    indent: String,
) {
    val mark =
        when (txn.status) {
            "committed" -> "✓"
            "rolled-back" -> "✗"
            else -> "…"
        }
    val duration = txn.durNs?.let { " (${formatNanos(it)})" } ?: ""
    val states =
        if (txn.states.isEmpty()) {
            ""
        } else {
            " ${txn.states.joinToString(",")}"
        }
    val frame = txn.frame?.let { " ⧉$it" } ?: ""
    val error = txn.err?.let { "\n$indent  ${it.replace("\n", " ⏎ ")}" } ?: ""
    appendLine("$indent$mark ${txn.id}$duration$states$frame$error")
    childrenByParent[txn.id]?.forEach { child -> appendTxn(child, childrenByParent, "$indent  ") }
}

/** Aggregate a parsed trace: counts, durations, slowest, rollback rate. */
internal fun statsTrace(trace: TraceFile): String =
    buildString {
        val ended = trace.txns.filter { it.ended }
        val committed = ended.count { it.status == "committed" }
        val rolledBack = ended.count { it.status == "rolled-back" }
        val savepoints = trace.txns.count { it.savepoint }
        val labels = trace.txns.map { it.label }.distinct()
        appendLine("stores: ${labels.size} (${labels.joinToString(", ")})")
        appendLine(
            "transactions: ${trace.txns.size} ($committed committed, $rolledBack rolled back, $savepoints savepoints)",
        )
        val durations = ended.mapNotNull { it.durNs }
        if (durations.isNotEmpty()) {
            appendDurationStats(ended, durations)
            if (rolledBack > 0) {
                val rate = rolledBack * HUNDRED / ended.size
                appendLine("rollback rate: $rate%")
            }
        }
        if (trace.malformedLines > 0) appendLine("malformed lines: ${trace.malformedLines}")
    }

private fun StringBuilder.appendDurationStats(
    ended: List<TraceTxn>,
    durations: List<Long>,
) {
    val total = durations.sum()
    appendLine(
        "duration: total ${formatNanos(total)}, avg ${formatNanos(total / durations.size)}, " +
            "max ${formatNanos(durations.maxOrNull() ?: 0L)}",
    )
    appendLine("slowest:")
    ended
        .mapNotNull { txn -> txn.durNs?.let { dur -> txn to dur } }
        .sortedByDescending { (_, dur) -> dur }
        .take(SLOWEST_SHOWN)
        .forEach { (txn, dur) ->
            appendLine("  ${formatNanos(dur)} ${txn.label}/${txn.id} (${txn.status})")
        }
}

private const val NANOS_PER_MICROSECOND = 1_000.0
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val WHOLE_NUMBER_ABOVE = 100.0
private const val HUNDRED = 100
private const val SLOWEST_SHOWN = 5

internal fun formatNanos(ns: Long): String =
    when {
        ns < NANOS_PER_MICROSECOND -> "${ns}ns"
        ns < NANOS_PER_MILLISECOND -> "${round1(ns / NANOS_PER_MICROSECOND)}µs"
        ns < NANOS_PER_SECOND -> "${round1(ns / NANOS_PER_MILLISECOND)}ms"
        else -> "${round1(ns / NANOS_PER_SECOND)}s"
    }

private fun round1(value: Double): String =
    if (value >= WHOLE_NUMBER_ABOVE) {
        value.toLong().toString()
    } else {
        value.toString()
    }
