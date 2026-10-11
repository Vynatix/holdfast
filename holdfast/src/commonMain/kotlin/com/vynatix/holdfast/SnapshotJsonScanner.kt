package com.vynatix.holdfast

// The token level of the encoded-snapshot reader (issue #20, R1): whitespace,
// punctuation, strings, numbers and literals, exactly as RFC 8259 defines them.
// Hand-rolled so the core stays free of any serialization library. Failures
// are SnapshotFormatExceptions that name an offset and never quote the text.

/**
 * Reads the tokens of [text] from [pos] on. Strings must be valid Unicode: an
 * unpaired surrogate written raw into the text is rejected, while an escaped
 * one (`\ud800`) is accepted — the writer escapes every unpaired surrogate,
 * so what it writes reads back unchanged.
 */
internal class SnapshotJsonScanner(
    private val text: String,
) {
    /** Offset of the next character to read. */
    var pos: Int = 0

    /** Advance past RFC 8259 whitespace: space, tab, line feed, carriage return. */
    fun skipWhitespace() {
        while (peekChar()?.isJsonWhitespace() == true) pos++
    }

    /** The character at [pos], or `null` at the end of the text. */
    fun peekChar(): Char? = text.getOrNull(pos)

    /** Consume [expected] at [pos], or fail naming [what] was expected there. */
    fun expect(
        expected: Char,
        what: String,
    ) {
        if (peekChar() != expected) fail("expected $what")
        pos++
    }

    /** Read a string token, quotes included, and return its unescaped content. */
    fun readString(): String {
        val start = pos
        expect('"', "a string")
        val out = StringBuilder()
        while (true) {
            val c = peekChar() ?: fail("unterminated string", start)
            if (c == '"') break
            when {
                c == '\\' -> readEscape(out)
                c < ' ' -> fail("unescaped control character in a string")
                c.isSurrogate() -> readSurrogatePair(out)
                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
        pos++
        return out.toString()
    }

    /** Read a number token with RFC 8259's grammar and return it as written. */
    fun readNumber(): String {
        val start = pos
        if (peekChar() == '-') pos++
        when (peekChar()) {
            '0' -> pos++
            in '1'..'9' -> skipDigits()
            else -> fail("malformed number", start)
        }
        if (peekChar() == '.') {
            pos++
            skipDigits()
        }
        if (peekChar() == 'e' || peekChar() == 'E') {
            pos++
            if (peekChar() == '+' || peekChar() == '-') pos++
            skipDigits()
        }
        return text.substring(start, pos)
    }

    /** Consume the literal [word] (`true`, `false` or `null`). */
    fun readLiteral(word: String) {
        if (!text.startsWith(word, pos)) fail("expected a value")
        pos += word.length
    }

    /** Fail at [at]: [problem] says what is wrong, never what the text holds there. */
    fun fail(
        problem: String,
        at: Int = pos,
    ): Nothing = snapshotFormatError(problem, at)

    /** One or more digits, required at [pos]. */
    private fun skipDigits() {
        if (peekChar()?.isAsciiDigit() != true) fail("malformed number")
        while (peekChar()?.isAsciiDigit() == true) pos++
    }

    /** The escape sequence at [pos] (just past its backslash), unescaped into [out]. */
    private fun readEscape(out: StringBuilder) {
        pos++
        val c = peekChar() ?: fail("unterminated string")
        pos++
        val unescaped =
            when (c) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readHex4()
                else -> fail("invalid escape sequence", pos - 2)
            }
        out.append(unescaped)
    }

    /** The four hex digits of a `\u` escape: one UTF-16 code unit, which may be a lone surrogate. */
    private fun readHex4(): Char {
        val start = pos
        var code = 0
        repeat(HEX_ESCAPE_DIGITS) {
            val digit = peekChar()?.asciiHexValue() ?: fail("invalid \\u escape", start - 2)
            code = code * HEX_RADIX + digit
            pos++
        }
        return code.toChar()
    }

    /** A raw surrogate at [pos]: accepted only as a well-formed pair, appended to [out]. */
    private fun readSurrogatePair(out: StringBuilder) {
        val high = text[pos]
        val low = text.getOrNull(pos + 1)
        if (!high.isHighSurrogate() || low == null || !low.isLowSurrogate()) fail("unpaired surrogate in a string")
        out.append(high).append(low)
        pos += 2
    }
}

private const val HEX_ESCAPE_DIGITS = 4

private const val HEX_RADIX = 16

private const val HEX_LETTER_BASE = 10

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** This hex digit's value — ASCII only, as RFC 8259 requires — or `null`. */
private fun Char.asciiHexValue(): Int? =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + HEX_LETTER_BASE
        in 'A'..'F' -> this - 'A' + HEX_LETTER_BASE
        else -> null
    }

private fun Char.isJsonWhitespace(): Boolean =
    when (this) {
        ' ', '\t', '\n', '\r' -> true
        else -> false
    }
