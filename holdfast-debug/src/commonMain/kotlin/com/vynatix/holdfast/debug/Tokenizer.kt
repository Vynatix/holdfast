package com.vynatix.holdfast.debug

/**
 * Splits one console line into argv, shell-style: whitespace separates,
 * `"…"`/`'…'` group (with `\"` and `\\` escapes inside double quotes), and an
 * unquoted `;` becomes its own token so several commands can share a line.
 */
internal object Tokenizer {
    const val SEPARATOR = ";"

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        quote = null
                    } else if (c == '\\' && quote == '"' && i + 1 < line.length) {
                        current.append(line[i + 1])
                        i++
                    } else {
                        current.append(c)
                    }
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    hasToken = true
                }
                c == ';' -> {
                    if (hasToken) out += current.toString()
                    current.clear()
                    hasToken = false
                    out += SEPARATOR
                }
                c.isWhitespace() -> {
                    if (hasToken) out += current.toString()
                    current.clear()
                    hasToken = false
                }
                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
            i++
        }
        if (hasToken) out += current.toString()
        return out
    }

    /** Split argv into commands at standalone [SEPARATOR] tokens, dropping empties. */
    fun splitCommands(args: List<String>): List<List<String>> {
        val commands = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (arg in args) {
            if (arg == SEPARATOR) {
                if (current.isNotEmpty()) commands += current
                current = mutableListOf()
            } else if (arg.isNotBlank()) {
                current += arg
            }
        }
        if (current.isNotEmpty()) commands += current
        return commands
    }
}
