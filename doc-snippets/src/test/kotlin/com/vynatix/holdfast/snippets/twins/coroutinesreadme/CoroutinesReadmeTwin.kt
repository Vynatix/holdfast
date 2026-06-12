// Twins of holdfast-coroutines/README.md examples. Compile-only.
package com.vynatix.holdfast.snippets.twins.coroutinesreadme

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.asFlow
import com.vynatix.holdfast.coroutines.asStateFlow
import com.vynatix.holdfast.coroutines.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Scaffold: the README's sample stores, named but not defined by the doc
// (pre-rename names; issue #10 will rename them in the doc and here).
class CounterHoldfast : Store<CounterHoldfast>() {
    val count by state { 0 }
}

enum class AccountStatus { Active, Frozen }

class AccountHoldfast : Store<AccountHoldfast>() {
    val status by state { AccountStatus.Frozen }
}

// Scaffold: minimal stand-ins for the androidx.lifecycle surface the examples
// assume; only the shape the snippets compile against.
open class ViewModel {
    protected val viewModelScope: CoroutineScope = MainScope()
}

private fun log(message: String) {}

@Suppress("unused")
private fun coldFlowOverAState() {
    val viewModelScope = CoroutineScope(SupervisorJob())
    val holdfast = CounterHoldfast()
    // DOC-SNIPPET holdfast-coroutines/README.md#1
    viewModelScope.launch {
        holdfast.count.asFlow().collect { value ->
            log("count = $value")
        }
    }
    // DOC-SNIPPET-END
}

// DOC-SNIPPET holdfast-coroutines/README.md#2
class CounterViewModel : ViewModel() {
    val holdfast = CounterHoldfast()
    val count: StateFlow<Int> = holdfast.count.asStateFlow(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
    )
}
// DOC-SNIPPET-END

// DOC-SNIPPET holdfast-coroutines/README.md#3
suspend fun waitForReady(holdfast: AccountHoldfast) {
    holdfast.status.first { it == AccountStatus.Active }
}
// DOC-SNIPPET-END
