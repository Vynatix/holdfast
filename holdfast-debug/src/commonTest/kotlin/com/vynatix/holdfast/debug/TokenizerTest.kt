package com.vynatix.holdfast.debug

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenizerTest {
    @Test
    fun splitsOnWhitespaceAndHonoursQuotes() {
        assertEquals(
            listOf("set", "cart", "label", "Two words", "it's"),
            Tokenizer.tokenize("""set cart label "Two words" 'it'"'"'s'"""),
        )
    }

    @Test
    fun escapesInsideDoubleQuotes() {
        assertEquals(listOf("say", "a \"b\" c"), Tokenizer.tokenize("""say "a \"b\" c" """))
    }

    @Test
    fun semicolonSeparatesCommands() {
        val argv = Tokenizer.tokenize("mark cart; set cart count 3 ;diff cart")
        assertEquals(listOf("mark", "cart", ";", "set", "cart", "count", "3", ";", "diff", "cart"), argv)
        assertEquals(
            listOf(listOf("mark", "cart"), listOf("set", "cart", "count", "3"), listOf("diff", "cart")),
            Tokenizer.splitCommands(argv),
        )
    }

    @Test
    fun quotedSemicolonIsNotASeparator() {
        assertEquals(listOf("set", "s", "label", "a;b"), Tokenizer.tokenize("set s label 'a;b'"))
    }

    @Test
    fun emptyQuotesProduceEmptyToken() {
        assertEquals(listOf("set", "s", "label", ""), Tokenizer.tokenize("set s label \"\""))
    }
}
