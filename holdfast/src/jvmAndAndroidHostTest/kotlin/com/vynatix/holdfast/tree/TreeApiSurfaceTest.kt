package com.vynatix.holdfast.tree

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T7.2, the single gate: no tree member takes a `String` except `decode`
 * (the wire text) and the `named` pins of the declarations — `store`,
 * `group`, `keyed` and the group lambda's `GroupScope.named` — so nodes and
 * states address everything else; checked by reading the committed JVM API
 * dump, so a new string-taking overload has to be added to this test's
 * allow list on purpose. Exemptions are by function NAME (any overload of
 * an allowed name passes, `store$default` included), so keep the list
 * short.
 */
class TreeApiSurfaceTest {
    private val allowed = setOf("decode", "store", "group", "keyed", "named", "<init>", "valueOf")

    @Test
    fun noTreeMemberTakesAStringExceptDecodeAndTheNamedPins() {
        val dump = apiDump()
        var owner: String? = null
        val offenders = mutableListOf<String>()
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("public") && line.endsWith("{") ->
                    owner = line.takeIf { "com/vynatix/holdfast/tree/" in it }
                line == "}" -> owner = null
                owner != null && line.startsWith("public") && "fun " in line -> {
                    val name = line.substringAfter("fun ").substringBefore(" (").substringBefore("$")
                    val params = line.substringAfter("(").substringBefore(")")
                    if ("Ljava/lang/String;" in params && name !in allowed) offenders += "$owner: $line"
                }
            }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n", prefix = "string-taking tree members:\n"))
    }

    private fun apiDump(): String {
        val candidates = listOf(File("api/jvm/holdfast.api"), File("holdfast/api/jvm/holdfast.api"))
        val file = candidates.firstOrNull { it.isFile } ?: fail("holdfast.api not found from ${File(".").absolutePath}")
        val text = file.readText()
        assertTrue("com/vynatix/holdfast/tree/StoreTree" in text, "the dump lists the tree package")
        return text
    }
}
