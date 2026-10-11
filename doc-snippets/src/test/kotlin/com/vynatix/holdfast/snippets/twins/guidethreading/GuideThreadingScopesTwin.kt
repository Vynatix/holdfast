// Twins of GUIDE.md §18 "Threading & Scopes" examples. Compile-only — the
// defaultScope assignment must never execute (CAS-settable once per process).
package com.vynatix.holdfast.snippets.twins.guidethreading

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Scaffold: the store the slow-observer example exercises.
private class FeedsStore : Store<FeedsStore>() {
    val feeds by state { emptyList<String>() }
}

private fun digest(feeds: List<String>) {}

@Suppress("unused", "UNUSED_VARIABLE")
private fun slowObserversOffload() {
    // DOC-SNIPPET holdfast/GUIDE.md#90
    val v = FeedsStore()
    val sub = v {
        feeds effect { v.scope.launch { digest(this@effect) } }   // this@effect: the committed list
    }
    // DOC-SNIPPET-END
}

@Suppress("unused", "UNUSED_VARIABLE")
private fun scopeResolution() {
    // DOC-SNIPPET holdfast/GUIDE.md#91
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    Store.defaultScope = appScope            // 4. process default — CAS-settable ONCE per process

    class PanelStore : Store<PanelStore>() {
        override val scope: CoroutineScope   // 2. per-store override — beats 3 and 4
            get() = appScope
    }

    val v = PanelStore()
    v.bindToScope(appScope)                  // 3. rebindable binding — you own the lifetime
    // 1. per-call scopes (hydrate(scope = …), asStateFlow(scope = …)) beat them all
    // DOC-SNIPPET-END
}
