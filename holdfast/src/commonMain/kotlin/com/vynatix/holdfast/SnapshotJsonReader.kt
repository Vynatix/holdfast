package com.vynatix.holdfast

// The structure level of the encoded-snapshot reader (issue #20, R1): a pull
// reader over one JSON document, in the style of a streaming JSON reader, with
// just what the store envelope needs — and the whole RFC 8259 grammar for
// skipping what it does not know, so a newer writer's extra fields read fine.

/** How deep containers may nest in an encoded snapshot. Deeper documents are rejected, never recursed into. */
internal const val MAX_JSON_DEPTH = 64

/** The kind of value at a [SnapshotJsonReader]'s position. */
internal enum class JsonKind { Object, Array, String, Number, Boolean, Null }

/**
 * Pulls the values of one JSON document out of [text], in order. Containers
 * nest at most [MAX_JSON_DEPTH] deep: opening one deeper fails, so no caller
 * — [skipValue] included — ever recurses further than that. Every failure is
 * a [SnapshotFormatException] naming an offset (see [SnapshotJsonScanner]).
 */
internal class SnapshotJsonReader(
    val text: String,
) {
    private val scanner = SnapshotJsonScanner(text)

    /** Per open container (index = depth): whether it is an object, and whether its next member is its first. */
    private val isObject = BooleanArray(MAX_JSON_DEPTH + 1)
    private val atFirst = BooleanArray(MAX_JSON_DEPTH + 1)
    private var depth = 0

    /** Offset of the next unread character. */
    val position: Int
        get() = scanner.pos

    /** The kind of the next value, without consuming it. */
    fun peek(): JsonKind {
        scanner.skipWhitespace()
        return when (scanner.peekChar()) {
            '{' -> JsonKind.Object
            '[' -> JsonKind.Array
            '"' -> JsonKind.String
            't', 'f' -> JsonKind.Boolean
            'n' -> JsonKind.Null
            '-', in '0'..'9' -> JsonKind.Number
            else -> scanner.fail("expected a value")
        }
    }

    /** Consume the `{` that opens an object. */
    fun beginObject() {
        scanner.skipWhitespace()
        scanner.expect('{', "'{'")
        push(obj = true)
    }

    /** Consume the `[` that opens an array. */
    fun beginArray() {
        scanner.skipWhitespace()
        scanner.expect('[', "'['")
        push(obj = false)
    }

    /**
     * Whether the open container has another member (object) or element
     * (array), consuming the comma before it; `false` at its closing bracket,
     * which [endContainer] then consumes.
     */
    fun hasNext(): Boolean {
        scanner.skipWhitespace()
        val close = if (isObject[depth]) '}' else ']'
        if (scanner.peekChar() == close) return false
        if (atFirst[depth]) atFirst[depth] = false else scanner.expect(',', "',' or '$close'")
        return true
    }

    /** Consume the bracket that closes the open container. */
    fun endContainer() {
        scanner.skipWhitespace()
        scanner.expect(if (isObject[depth]) '}' else ']', if (isObject[depth]) "'}'" else "']'")
        depth--
    }

    /** The name of the object member [hasNext] found, and its colon. */
    fun nextName(): String {
        scanner.skipWhitespace()
        val name = scanner.readString()
        scanner.skipWhitespace()
        scanner.expect(':', "':'")
        return name
    }

    /** A string value, or `null` for a JSON `null`; anything else fails naming [what] was expected. */
    fun nextStringOrNull(what: String): String? =
        when (peek()) {
            JsonKind.String -> scanner.readString()
            JsonKind.Null -> null.also { scanner.readLiteral("null") }
            else -> scanner.fail("expected $what")
        }

    /** An integer value within `Int` range, for [what]. */
    fun nextInt(what: String): Int {
        if (peek() != JsonKind.Number) scanner.fail("expected $what")
        val start = scanner.pos
        return scanner.readNumber().toIntOrNull() ?: scanner.fail("expected $what", start)
    }

    /** Consume a string, number, boolean or null value; [peek] says which is next. */
    fun skipScalar() {
        when (peek()) {
            JsonKind.String -> scanner.readString()
            JsonKind.Number -> scanner.readNumber()
            JsonKind.Boolean -> scanner.readLiteral(if (scanner.peekChar() == 't') "true" else "false")
            JsonKind.Null -> scanner.readLiteral("null")
            JsonKind.Object, JsonKind.Array -> scanner.fail("expected a scalar value")
        }
    }

    /** Require that nothing but whitespace follows the document. */
    fun endDocument() {
        scanner.skipWhitespace()
        if (scanner.pos != text.length) scanner.fail("unexpected content after the snapshot")
    }

    private fun push(obj: Boolean) {
        if (depth == MAX_JSON_DEPTH) {
            scanner.fail("containers nested deeper than $MAX_JSON_DEPTH levels", scanner.pos - 1)
        }
        depth++
        isObject[depth] = obj
        atFirst[depth] = true
    }
}

/**
 * Consume the next value, whatever it is. Recursion is bounded: a container
 * opened deeper than [MAX_JSON_DEPTH] fails before its contents are read.
 */
internal fun SnapshotJsonReader.skipValue() {
    when (peek()) {
        JsonKind.Object -> {
            beginObject()
            while (hasNext()) {
                nextName()
                skipValue()
            }
            endContainer()
        }
        JsonKind.Array -> {
            beginArray()
            while (hasNext()) skipValue()
            endContainer()
        }
        else -> skipScalar()
    }
}

/** Consume the next value and return its text exactly as written. */
internal fun SnapshotJsonReader.captureValue(): String {
    peek()
    val start = position
    skipValue()
    return text.substring(start, position)
}
