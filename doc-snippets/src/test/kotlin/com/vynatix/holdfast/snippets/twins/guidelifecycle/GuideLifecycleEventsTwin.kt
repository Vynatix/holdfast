// Twin of GUIDE.md §19 "Lifecycle & Events" example. The block declares
// classes, so it is embedded at top level; compile-only — nothing drives it
// (the subscriptions would run on the process default scope).
package com.vynatix.holdfast.snippets.twins.guidelifecycle

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.effect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// DOC-SNIPPET holdfast/GUIDE.md#92
sealed class CounterEvent {
    data class Saved(val at: Int) : CounterEvent()
    data class Failed(val cause: Throwable) : CounterEvent()
}

class CounterStore : EventfulStore<CounterStore, CounterEvent>() {
    val count by state { 0 }

    fun increment() = action {
        count update { it + 1 }
        emit(CounterEvent.Saved(count.value))   // staged with the write; discarded on rollback
    }
}

val v = CounterStore()
val order = mutableListOf<String>()
val states = v { count effect { order.add("state=$this") } }               // fires in the fanout
val events = v.events.onEach { order.add("event=$it") }.launchIn(v.scope)  // drains after it
// v.increment() → order gains "state=1" then "event=Saved(at=1)" — never the reverse
// DOC-SNIPPET-END
