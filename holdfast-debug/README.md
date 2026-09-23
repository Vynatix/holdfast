# holdfast-debug

On-device debugging console for [Store](../holdfast/)s: inspect and change
state, read a transaction journal (including what rolled-back transactions
*tried* to write), take in-process checkpoints and rewind to them, quarantine
a misbehaving store — from **adb** on Android, or from any line of Kotlin/Swift
on the other targets. Pulls in `:holdfast` only.

**Status: `@ExperimentalStoreApi`.** Every symbol needs the opt-in; names and
output formats may change in any 0.x release. The ROADMAP lists DevTools as a
post-1.0 item, so this module is shipped for feedback, not stability.

## 60-second tour (Android)

```kotlin
// Application.onCreate (debug builds)
val cart = CartStore()
HoldfastDebug.register(cart, "cart", redact = setOf("paymentToken"))
HoldfastDebug.verb("clearCart", "empty the cart") { cart action { items mutate emptyList() } }
```

```
$ P=com.example.app/com.vynatix.holdfast.debug.HoldfastDebugProvider
$ adb shell dumpsys activity provider $P stores
NAME  CLASS      STATES  JOURNAL  FLAGS
cart  CartStore  3       12       redacts:1

$ adb shell dumpsys activity provider $P dump cart
cart (CartStore) — 3 state(s)
  items         = [sku-1, sku-2]   last:AddItem
  paymentToken  = <redacted>
  total         = 42.0             bridge last:AddItem

$ adb shell dumpsys activity provider $P set cart total 10.5
OK ConsoleWrite: total 42.0 -> 10.5

$ adb shell dumpsys activity provider $P autopsy cart
cart: 1 of 1 entries
#11 12:03:44.120 txn Checkout ROLLED BACK 0.8ms thread:2
    error: IllegalStateException: card declined
    total: 42.0 -> 0.0  (discarded)
    items: [sku-1, sku-2] -> []  (discarded)

$ adb shell "dumpsys activity provider $P mark cart; run clearCart; diff cart"
marked cart at #12
OK clearCart (ClearCart)
cart: 1 state(s) changed since mark #12 (now #13)
  items  [sku-1, sku-2] -> []  by ClearCart
```

No manifest editing: the module's manifest declares the provider and the
framework instantiates it at process start. Nothing beyond `register(...)` is
needed in app code.

## Commands

Several commands can share one line, separated by `;`.

| Command | What it does |
|---|---|
| `stores` | Registered stores: class, state count, journal size, flags (`quarantined`, `active-txn:<id>`, `redacts:<n>`). |
| `dump <store>` | Every registered state and its value, whether a bridge is attached, and the id of the transaction (or `inbound`) that last wrote it. |
| `get <store> <state>` | One value. |
| `set <store> <state> <literal>` | Write through a **real transaction**: `Transformer.set`, every middleware (validation included), observers and bridges all run. Supported literal types: `Boolean`, `Int`, `Long`, `Short`, `Byte`, `Double`, `Float`, `Char`, `String`, enums (JVM/Android). Anything else is refused — register a verb. |
| `batch <store> k=v [k=v …]` | Several writes as **one** transaction: all land or none. |
| `journal <store> [n]` | Last *n* (default 20) transactions — id, outcome, duration, savepoint depth, frame id, thread — with each write as `state: before -> after`, plus bridge inbound updates. |
| `autopsy <store> [n]` | Only rolled-back / vetoed transactions, with the error and the writes they discarded. |
| `stats <store>` | Counts (committed / rolled back / savepoints / inbound), average and slowest duration, most-written states. |
| `mark <store>` / `diff <store>` | Stamp a position; later list only the states whose committed value changed since, each with its last writer. Reproduce a bug between the two and read the delta instead of a 200-state dump. |
| `checkpoint <store>` / `checkpoints <store>` | `Store.snapshot()` under the transaction lock, kept **in-process** (never serialized, never leaves the device). Bounded per store. |
| `rewind <store> <id>` | `Store.restore()` of that checkpoint — atomic, raw values round-trip without re-running `Transformer.set` (ciphertext stays ciphertext). Lists states registered after the checkpoint, which are not touched. |
| `quarantine <store> on\|off\|status` | While on, every action on the store rolls back with `StoreQuarantinedException` before its body runs — stop a store from corrupting persisted data without killing the process that holds the evidence. |
| `clear <store>` | Drop the store's journal. |
| `verbs` / `run <verb> [args…]` | App-registered actions (`HoldfastDebug.verb("name") { args -> … }`). The verb's return value is printed; a `TransactionResult` is rendered as OK/FAILED. |
| `help` | This list. |

An **empty command prints exactly one line** — the number of registered
stores — and never a state name or value. `adb bugreport` runs argument-less
`dumpsys` over every provider, and a bugreport attached to a public ticket
must not carry store contents.

## Transports

### Android: `dumpsys` (primary)

```
adb shell dumpsys activity provider <applicationId>/com.vynatix.holdfast.debug.HoldfastDebugProvider <command…>
```

`dumpsys` calls `ContentProvider.dump(fd, writer, args)` in the app process
and streams the writer back to your terminal. Only the shell and system hold
`android.permission.DUMP`, so the OS authenticates the caller; the library
adds no socket, no exported receiver, no port forward. The short form
`dumpsys activity provider HoldfastDebugProvider …` matches by class name and
reaches every running app that embeds the module.

`dump()` runs on the app's **main thread**, so mutating commands run their
observers where UI code expects them — and the console cannot answer while
the main thread is wedged. Use the second transport for that case.

Arguments pass through two shells (yours and the device's). A value with
spaces needs the whole command quoted once more:
`adb shell "dumpsys activity provider $P set cart label 'Two words'"`.

### Android: `content call` (binder thread)

```
adb shell content call --uri content://<applicationId>.holdfast-debug --method exec --arg "dump cart"
```

Same interpreter, arriving on a binder thread — it works while the main
thread is blocked. Requires the provider to be exported, which the module's
manifest does, guarded by `android.permission.DUMP`. Mutating commands hop to
the main thread through `MainThreadMutationRunner` (5 s timeout; on timeout
the command is cancelled if it has not started, and the answer says which).

### Everywhere else

`HoldfastDebug.execute("dump cart")` returns the same text. Wire it to
whatever you have: a stdin loop on desktop
(`HoldfastDebug.console.repl(::readLine, ::print)`), a hidden debug screen,
an lldb `po` on iOS, a test assertion. The interpreter has no platform
dependencies and is tested once in `commonTest`.

## Keeping it out of release builds

The provider ships in whatever build includes the artifact. Options, from
lightest to strictest:

1. **`implementation` + register only in debug builds.** The provider exists
   in release but sees zero stores; every command answers "unknown store".
   The DUMP guard means only adb can reach it anyway.
2. **`debugImplementation("com.vynatix:holdfast-debug")`** and put the
   `register`/`verb` calls behind a debug-only interface.
3. **Strip the provider** from a specific build in the app manifest:
   ```xml
   <provider android:name="com.vynatix.holdfast.debug.HoldfastDebugProvider" tools:node="remove" />
   ```

## What to know before trusting the output

- **Redaction is by name.** `State.value` is the post-`transformer.get` view,
  so a state behind an `EncryptingTransformer` prints its plaintext unless
  you list it in `register(..., redact = setOf(...))`. Redacted states cannot
  be `set` either.
- **Lazy registration.** A `by state` delegate that was never read is not in
  `properties`, so it is absent from `dump`, `set`, checkpoints and the
  journal until first use. `dump` says so when a store has no states yet.
- **Writes after a dispatcher hop.** A `suspendAction` body that resumed on
  another thread makes `Transaction.modifiedStates` unreadable; the journal
  records the transaction with `(writes unavailable …)` rather than a wrong
  value.
- **Inbound attribution.** A bridge update that lands while another thread's
  transaction is active on the same store is attributed to that transaction
  — an inherent ambiguity of core's unsynchronised bridge path.
- **`set`/`rewind` re-publish to bridges** exactly like an app write does: a
  `KvBridge` persists the debug value.
- **Middleware is never removed.** Core has no `removeMiddleware`, so
  unregistering leaves the journal and quarantine middlewares attached but
  inert.
- **Outcome is live.** An entry recorded at the `completed` hook shows
  `VETOED` if an outer middleware or an `atomic` peer rolled the commit back
  afterwards.

## Surface

```kotlin
object HoldfastDebug {
    val registry: StoreRegistry
    val console: DebugConsole
    fun <S : Store<S>> register(store: S, name: String = store::class.simpleName, redact: Set<String> = emptySet()): Disposable
    fun unregister(name: String): Boolean
    fun verb(name: String, description: String = "", body: (List<String>) -> Any?): Disposable
    fun execute(line: String): String
    fun execute(args: List<String>): String
    var mutationRunner: MutationRunner
}

class StoreRegistry(options: DebugOptions = DebugOptions())   // instantiable for tests
class DebugConsole(registry: StoreRegistry) { var mutationRunner; fun execute(line); fun execute(args) }
fun DebugConsole.repl(readLine: () -> String?, write: (String) -> Unit, prompt: String = "holdfast> ")
data class DebugOptions(journalCapacity = 128, valueMaxChars = 200, checkpointCapacity = 8)
fun interface MutationRunner { fun run(block: () -> String): String }   // MutationRunner.Inline

class JournalMiddleware<V : Store<V>> : Middleware<V>       // entries(), lastSeen(), mark(), diff(), stats(), clear()
sealed interface JournalEntry; data class TransactionEntry; data class InboundEntry; data class JournalWrite
class QuarantineMiddleware<V : Store<V>> : Middleware<V>    // var enabled
class StoreQuarantinedException : IllegalStateException

// androidMain
class HoldfastDebugProvider : ContentProvider              // dumpsys + `content call --method exec`
class MainThreadMutationRunner(timeoutMillis: Long = 5_000) : MutationRunner
```
