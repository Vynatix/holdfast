package com.vynatix.holdfast

// The writer half of the encoded-snapshot format (issue #20, R1). It writes
// canonical JSON — no whitespace, and one fixed escaping for every string — so
// equal bodies encode to equal text on every platform.

/**
 * Builds one JSON document: [beginObject]/[name]/[value]/[endObject] and the
 * array equivalents, with commas and colons placed for the caller. Strings
 * are escaped by [appendJsonString].
 */
internal class SnapshotJsonWriter {
    private val out = StringBuilder()

    /** Per open container (index = depth): whether its next member is its first. */
    private val atFirst = BooleanArray(MAX_JSON_DEPTH + 1)
    private var depth = 0

    /** Set by [name]: the next value is that member's, and takes no comma. */
    private var afterName = false

    fun beginObject() {
        separate()
        out.append('{')
        push()
    }

    fun endObject() {
        out.append('}')
        depth--
    }

    fun beginArray() {
        separate()
        out.append('[')
        push()
    }

    fun endArray() {
        out.append(']')
        depth--
    }

    /** The name of the next object member; its value follows. */
    fun name(name: String) {
        separate()
        out.appendJsonString(name)
        out.append(':')
        afterName = true
    }

    /** A string value, or JSON `null` for `null`. */
    fun value(text: String?) {
        separate()
        if (text == null) out.append("null") else out.appendJsonString(text)
    }

    fun value(number: Int) {
        separate()
        out.append(number)
    }

    /** The document written so far. */
    override fun toString(): String = out.toString()

    private fun separate() {
        when {
            afterName -> afterName = false
            depth == 0 -> Unit
            atFirst[depth] -> atFirst[depth] = false
            else -> out.append(',')
        }
    }

    private fun push() {
        check(depth < MAX_JSON_DEPTH) { "the snapshot writer nests at most $MAX_JSON_DEPTH levels" }
        depth++
        atFirst[depth] = true
    }
}

/**
 * Append [text] as a JSON string literal, canonically: `"` and `\` escaped,
 * the control characters as `\b \f \n \r \t` or else `\u00xx` (lowercase hex),
 * and an unpaired surrogate as its `\uxxxx` escape — so the document stays
 * valid Unicode, and a reader that follows RFC 8259 gets the same UTF-16 code
 * units back. Everything else, surrogate pairs included, is written as is.
 */
internal fun StringBuilder.appendJsonString(text: String) {
    append('"')
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val pair = c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() == true
        when {
            pair -> {
                append(c).append(text[i + 1])
                i++
            }
            c.isSurrogate() || c < ' ' -> append(shortEscape(c) ?: unicodeEscape(c))
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            else -> append(c)
        }
        i++
    }
    append('"')
}

/** The two-character escape RFC 8259 defines for control character [c], if any. */
private fun shortEscape(c: Char): String? =
    when (c) {
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> null
    }

private const val HEX_RADIX = 16

private const val UNICODE_ESCAPE_DIGITS = 4

private fun unicodeEscape(c: Char): String = "\\u" + c.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0')
