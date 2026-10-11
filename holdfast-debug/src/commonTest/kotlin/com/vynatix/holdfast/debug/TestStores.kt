package com.vynatix.holdfast.debug

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transformer

enum class Mode { FAST, SLOW }

class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val total by state { 10L }
    val ratio by state { 1.5 }
    val flag by state { false }
    val label by state { "hello" }
    val mode by state { Mode.FAST }
    val secret by state { "s3cret-token" }
    val items by state { listOf("a") }
    val shout by state(transformer = Uppercase) { "quiet" }

    /** Touch every delegate so the store's `properties` is complete. */
    fun touchAll() {
        count
        total
        ratio
        flag
        label
        mode
        secret
        items
        shout
    }

    private object Uppercase : Transformer<String> {
        override fun set(value: String): String = value.uppercase()

        override fun get(value: String): String = value
    }
}

/** A store whose sensitive state is declared with core's Secret tag instead of a redact list. */
class VaultStore : Store<VaultStore>() {
    val pin by state(tags = setOf(StateTag.Secret)) { "1234" }
    val owner by state { "ada" }
}

/** Rejects any transaction that leaves `count` negative — runs before commit. */
class NonNegativeCount : Middleware<CounterStore>() {
    override fun onTransactionCompleted(context: MiddlewareContext<CounterStore>) {
        check(context.store.count.value >= 0) { "count must not be negative" }
    }
}

fun newConsole(options: DebugOptions = DebugOptions()): Pair<StoreRegistry, DebugConsole> {
    val registry = StoreRegistry(options)
    return registry to DebugConsole(registry)
}
