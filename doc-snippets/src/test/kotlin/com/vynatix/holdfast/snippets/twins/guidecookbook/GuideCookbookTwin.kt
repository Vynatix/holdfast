// Twins of GUIDE.md §9 cookbook recipes 9.1–9.4. Compile-only.
package com.vynatix.holdfast.snippets.twins.guidecookbook

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Middleware.MiddlewareContext
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.Codec
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock

// Scaffold store for the recipes that attach middleware / bridges.
private class CookbookStore : Store<CookbookStore>() {
    val items by state { emptyList<String>() }
}

// Scaffold: §9.2's store; the recipe only declares the middleware.
class AccountStore : Store<AccountStore>() {
    val balance by state { 0L }
}

// Scaffold: §9.3's collaborators, named by the recipe but not defined there.
interface Api {
    suspend fun send(text: String)
}

private object NoError : Throwable()

// Scaffold stand-ins for helpers the recipes name but do not define.
private fun log(message: String) {}

private object TodoCodec : Codec<List<String>> {
    override fun encode(value: List<String>): String = value.joinToString("\n")

    override fun decode(string: String): List<String> = string.lines()
}

@Suppress("unused")
private fun loggingEveryTransaction() {
    val holdfast = CookbookStore()
    // DOC-SNIPPET holdfast/GUIDE.md#10
    class Logger<V : Store<V>>(private val tag: String) : Middleware<V>() {
        override fun onTransactionStarted(c: MiddlewareContext<V>) {
            c.metadata["start"] = Clock.System.now().toEpochMilliseconds()
            println("$tag → ${c.transaction.id}")
        }
        override fun onTransactionCompleted(c: MiddlewareContext<V>) {
            val ms = Clock.System.now().toEpochMilliseconds() - (c.metadata["start"] as Long)
            println("$tag ✓ ${c.transaction.id} (${ms}ms)")
        }
        override fun onTransactionError(c: MiddlewareContext<V>, e: Throwable) {
            println("$tag ✗ ${c.transaction.id} → $e")
        }
    }
    holdfast.middlewares(Logger("Counter"))
    // DOC-SNIPPET-END
}

// DOC-SNIPPET holdfast/GUIDE.md#11
class NonNegativeBalance : Middleware<AccountStore>() {
    override fun onTransactionCompleted(c: MiddlewareContext<AccountStore>) {
        // Pending writes already buffered; check against current view.
        if (c.store.balance.value < 0)
            error("Balance cannot go negative")
    }
}
// DOC-SNIPPET-END

// DOC-SNIPPET holdfast/GUIDE.md#12
class Composer : Store<Composer>() {
    val text by state { "" }
    val sending by state { false }
    val lastError by state<Throwable> { NoError }
}

suspend fun send(holdfast: Composer, api: Api) {
    val draft = holdfast { text.value }
    holdfast action {
        sending mutate true
        text mutate ""           // optimistic clear
    }
    runCatching { api.send(draft) }
        .onSuccess { holdfast action { sending mutate false } }
        .onFailure { e ->
            holdfast action {
                sending mutate false
                text mutate draft     // restore
                lastError mutate e
            }
        }
}
// DOC-SNIPPET-END

@Suppress("unused")
private fun persistenceViaBridge() {
    val holdfast = CookbookStore()
    // DOC-SNIPPET holdfast/GUIDE.md#13
    class JsonFileBridge<T : Any>(
        private val file: Path,
        private val codec: Codec<T>,
    ) : Bridge<T> {
        private val observers = mutableListOf<(T) -> Unit>()
        override fun observe(observer: (T) -> Unit): Disposable {
            observers.add(observer)
            readFromDisk()?.let(observer)        // fire latest persisted on subscribe
            return Disposable { observers.remove(observer) }
        }
        override fun publish(value: T): Boolean {
            file.writeText(codec.encode(value))
            return true
        }
        private fun readFromDisk(): T? = runCatching {
            codec.decode(file.readText())
        }.getOrNull()
    }

    holdfast { items bridge JsonFileBridge(Path("todos.json"), TodoCodec) }
    // DOC-SNIPPET-END
}
