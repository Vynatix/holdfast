package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals

private class UpperCaseTransformer : Transformer<String> {
    override fun set(value: String): String = value.uppercase()
    override fun get(value: String): String = value.lowercase()
}

private class PrefixTransformer(private val prefix: String) : Transformer<String> {
    override fun set(value: String): String = "$prefix$value"
    override fun get(value: String): String = value.removePrefix(prefix)
}

private class ChainedVault : Store<ChainedVault>() {
    val a by state(transformer = UpperCaseTransformer().then(PrefixTransformer("v:"))) { "init" }
}

class TransformerThenTest {
    @Test
    fun setAppliesInOrder() {
        val v = ChainedVault()
        v action { a mutate "hello" }
        // Stored: PrefixTransformer.set(UpperCaseTransformer.set("hello")) = "v:HELLO"
        // Read: UpperCaseTransformer.get(PrefixTransformer.get("v:HELLO")) = "hello"
        assertEquals("hello", v.a.value)
    }

    @Test
    fun chainedRoundTripIsIdentityForRoundTripTransformers() {
        val pair = UpperCaseTransformer().then(PrefixTransformer("X-"))
        val out = pair.set("alpha")
        // set: "alpha" -> "ALPHA" -> "X-ALPHA"
        assertEquals("X-ALPHA", out)
        // get: "X-ALPHA" -> "ALPHA" -> "alpha"
        assertEquals("alpha", pair.get(out))
    }

    @Test
    fun chainedShouldTransformIsLogicalOr() {
        val never = object : Transformer<String> {
            override fun set(value: String): String = error("must not be called")
            override fun get(value: String): String = error("must not be called")
            override fun shouldTransform(value: String): Boolean = false
        }
        val always = object : Transformer<String> {
            override fun set(value: String): String = "$value!"
            override fun get(value: String): String = value.removeSuffix("!")
        }
        val combo = never.then(always)
        assertEquals(true, combo.shouldTransform("anything"))
        // never's shouldTransform returns false, so it's skipped on set; always still applies.
        assertEquals("hi!", combo.set("hi"))
    }
}
