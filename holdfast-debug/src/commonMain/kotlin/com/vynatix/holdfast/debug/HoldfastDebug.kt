package com.vynatix.holdfast.debug

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store

/**
 * Process-wide entry point: the default [StoreRegistry] and [DebugConsole]
 * every transport talks to.
 *
 * ```kotlin
 * val cart = CartStore()
 * HoldfastDebug.register(cart, "cart", redact = setOf("paymentToken"))
 * HoldfastDebug.verb("clearCart", "empty the cart") { cart action { items mutate emptyList() } }
 * ```
 *
 * Then, on Android:
 * ```
 * adb shell dumpsys activity provider <applicationId>/com.vynatix.holdfast.debug.HoldfastDebugProvider dump cart
 * ```
 * or on any platform, from code: `HoldfastDebug.execute("dump cart")`.
 *
 * Tests should construct their own [StoreRegistry] + [DebugConsole] instead of
 * sharing this singleton.
 */
@ExperimentalStoreApi
object HoldfastDebug {
    val registry: StoreRegistry = StoreRegistry()
    val console: DebugConsole = DebugConsole(registry)

    /** See [StoreRegistry.register]. */
    fun <S : Store<S>> register(
        store: S,
        name: String = store::class.simpleName ?: "Store",
        redact: Set<String> = emptySet(),
    ): Disposable = registry.register(store, name, redact)

    /** See [StoreRegistry.unregister]. */
    fun unregister(name: String): Boolean = registry.unregister(name)

    /** See [StoreRegistry.verb]. */
    fun verb(
        name: String,
        description: String = "",
        body: (List<String>) -> Any?,
    ): Disposable = registry.verb(name, description, body)

    /** Run one console line (`;` separates commands) and return its output. */
    fun execute(line: String): String = console.execute(line)

    /** Run already-split argv (what `dump(fd, writer, args)` receives). */
    fun execute(args: List<String>): String = console.execute(args)

    /** Thread policy for mutating commands; see [MutationRunner]. */
    var mutationRunner: MutationRunner
        get() = console.mutationRunner
        set(value) {
            console.mutationRunner = value
        }
}

/**
 * Minimal read-eval-print loop over any line source/sink — stdin/stdout on a
 * desktop JVM app, a socket, a test. Returns when [readLine] yields `null` or
 * the user types `exit`/`quit`.
 */
@ExperimentalStoreApi
fun DebugConsole.repl(
    readLine: () -> String?,
    write: (String) -> Unit,
    prompt: String = "holdfast> ",
) {
    while (true) {
        write(prompt)
        val line = readLine() ?: return
        if (line.trim().lowercase() in setOf("exit", "quit")) return
        if (line.isBlank()) continue
        write(execute(line) + "\n")
    }
}
