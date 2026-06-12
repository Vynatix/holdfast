// Twins of GUIDE.md §2 "The Shape of a Store" and §4.1 `state` declarations.
// Compile-only: the blocks declare store classes and are never executed.
package com.vynatix.holdfast.snippets.twins.guideshape

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transformer

// Scaffold: the guide references an EmailNormalizer transformer by name only.
private class EmailNormalizer : Transformer<String> {
    override fun set(value: String): String = value.trim().lowercase()

    override fun get(value: String): String = value
}

// DOC-SNIPPET holdfast/GUIDE.md#0
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val label by state { "initial" }
    val email by state(EmailNormalizer()) { "" }   // with transformer
}
// DOC-SNIPPET-END

// DOC-SNIPPET holdfast/GUIDE.md#2
class Profile : Store<Profile>() {
    val name by state { "anon" }                    // identity transformer
    val email by state(EmailNormalizer()) { "" }    // applies on set/get
    val tags by state { emptySet<String>() }        // any T : Any
}
// DOC-SNIPPET-END
