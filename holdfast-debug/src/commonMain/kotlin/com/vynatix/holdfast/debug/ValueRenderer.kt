package com.vynatix.holdfast.debug

/**
 * Renders state values for the console and the journal. Values are arbitrary
 * Kotlin objects and `toString()` is all the library can rely on, so the
 * renderer only truncates and redacts.
 *
 * [redact] lists state names whose values must never appear in output
 * (tokens, PINs, anything an `EncryptingTransformer` decrypts on read —
 * `State.value` is the post-`transformer.get` plaintext view). Redaction is
 * name-based because core exposes no "is this state transformed" hook; the
 * registry owner decides what is sensitive.
 */
internal class ValueRenderer(
    private val redact: Set<String>,
    private val maxChars: Int,
) {
    fun render(
        stateName: String,
        value: Any?,
    ): String {
        if (stateName in redact) return REDACTED
        val text = runCatching { value.toString() }.getOrElse { "<toString failed: ${it::class.simpleName}>" }
        return truncate(text, maxChars)
    }

    companion object {
        const val REDACTED = "<redacted>"

        fun truncate(
            text: String,
            maxChars: Int,
        ): String {
            if (maxChars <= 0 || text.length <= maxChars) return text
            return text.take(maxChars) + "…(+${text.length - maxChars})"
        }
    }
}
