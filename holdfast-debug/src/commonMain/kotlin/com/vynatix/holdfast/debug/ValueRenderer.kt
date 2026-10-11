package com.vynatix.holdfast.debug

import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.tags

/**
 * Renders state values for the console and the journal. Values are arbitrary
 * Kotlin objects and `toString()` is all the library can rely on, so the
 * renderer only truncates and redacts.
 *
 * A value is withheld when its state carries [StateTag.Secret] — the same
 * rule core's renders and logs and `:holdfast-testing`'s timelines follow —
 * or when its name is in [redact], for sensitive states declared without
 * tags (anything an `EncryptingTransformer` decrypts on read: `State.value`
 * is the post-`transformer.get` plaintext view).
 */
internal class ValueRenderer(
    private val redact: Set<String>,
    private val maxChars: Int,
) {
    /** Whether [state]'s values must never appear in console output. */
    fun isRedacted(
        stateName: String,
        state: State<*>,
    ): Boolean = stateName in redact || StateTag.Secret in state.tags

    fun render(
        stateName: String,
        state: State<*>,
        value: Any?,
    ): String {
        if (isRedacted(stateName, state)) return REDACTED
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
