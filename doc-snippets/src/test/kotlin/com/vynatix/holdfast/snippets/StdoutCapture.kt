package com.vynatix.holdfast.snippets

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs [block] with `System.out` swapped for an in-memory sink and returns the
 * non-empty printed lines. Used by the quick-start twins to assert the output
 * the READMEs claim.
 */
fun capturePrintln(block: () -> Unit): List<String> {
    val original = System.out
    val buffer = ByteArrayOutputStream()
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.out.flush()
        System.setOut(original)
    }
    return buffer
        .toString(Charsets.UTF_8)
        .lines()
        .map { it.trimEnd('\r') }
        .filter { it.isNotEmpty() }
}
