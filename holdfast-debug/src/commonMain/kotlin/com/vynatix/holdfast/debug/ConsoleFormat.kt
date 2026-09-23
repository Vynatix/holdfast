package com.vynatix.holdfast.debug

/** Plain-text helpers shared by the console's command handlers. */
internal object ConsoleFormat {
    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MICROS_PER_MILLI = 1000L
    private const val TENTHS = 100L
    private const val MILLIS_DIGITS = 3

    /** Column-aligned rows; the last column is never padded. */
    fun table(
        header: List<String>?,
        rows: List<List<String>>,
        indent: String = "",
    ): String {
        val all = if (header != null) listOf(header) + rows else rows
        val widths = IntArray(all.maxOf { it.size }) { col -> all.maxOf { it.getOrNull(col)?.length ?: 0 } }
        return all.joinToString("\n") { row ->
            val cells = row.mapIndexed { i, cell -> if (i == row.lastIndex) cell else cell.padEnd(widths[i]) }
            (indent + cells.joinToString("  ")).trimEnd()
        }
    }

    /** `HH:mm:ss.SSS` in UTC — no datetime dependency; adb sessions are short enough not to care about the day. */
    fun clock(epochMillis: Long): String {
        val secondsOfDay = (epochMillis / MILLIS_PER_SECOND) % SECONDS_PER_DAY
        val h = secondsOfDay / SECONDS_PER_HOUR
        val m = (secondsOfDay % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val s = secondsOfDay % SECONDS_PER_MINUTE
        val ms = epochMillis % MILLIS_PER_SECOND
        return "${two(h)}:${two(m)}:${two(s)}.${ms.toString().padStart(MILLIS_DIGITS, '0')}"
    }

    fun micros(micros: Long): String =
        when {
            micros < MICROS_PER_MILLI -> "${micros}µs"
            micros < MICROS_PER_MILLI * MILLIS_PER_SECOND -> {
                val tenths = (micros % MICROS_PER_MILLI) / TENTHS
                "${micros / MICROS_PER_MILLI}.${tenths}ms"
            }
            else -> "${micros / (MICROS_PER_MILLI * MILLIS_PER_SECOND)}s"
        }

    private fun two(n: Long) = n.toString().padStart(2, '0')
}

/**
 * A command that cannot proceed (bad usage, unknown store, parse failure).
 * Thrown by handlers and rendered by [DebugConsole.execute] as its message —
 * the one exception type that is *expected* to escape a handler.
 */
internal class CommandFailure(
    message: String,
) : RuntimeException(message)

internal fun fail(message: String): Nothing = throw CommandFailure(message)
