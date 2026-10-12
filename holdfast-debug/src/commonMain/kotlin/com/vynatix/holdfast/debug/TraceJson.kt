package com.vynatix.holdfast.debug

/**
 * One JSONL trace line: `{"v":1,<fields…>}` with [Any]/[Boolean]/[Long]/
 * [Int] literals, quoted-and-escaped strings (including `null`), string
 * lists as arrays. Deliberately tiny — this is the trace format's own
 * writer, not a snapshot codec; the CLI's reader is tested against it.
 */
internal fun traceLine(vararg fields: Pair<String, Any?>): String =
    buildString {
        append("{\"v\":1")
        for ((name, value) in fields) {
            append(',')
            appendJsonString(name)
            append(':')
            appendJsonValue(value)
        }
        append('}')
    }

private fun StringBuilder.appendJsonValue(value: Any?) {
    when (value) {
        null -> append("null")
        is String -> appendJsonString(value)
        is Boolean, is Long, is Int -> append(value.toString())
        is List<*> -> {
            append('[')
            value.forEachIndexed { index, element ->
                if (index > 0) append(',')
                appendJsonValue(element)
            }
            append(']')
        }
        else -> appendJsonString(value.toString())
    }
}

private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_WIDTH = 4

private fun StringBuilder.appendJsonString(text: String) {
    append('"')
    for (char in text) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' ->
                append("\\u")
                    .append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
            else -> append(char)
        }
    }
    append('"')
}
