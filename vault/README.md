# Vault — Transactional State for Kotlin Multiplatform

A KMP state-management library whose unit of consistency is a **transaction**.
Mutations buffer; observers see only committed values; failed transactions
never leak. No Compose dependency; no coroutines dependency in core; runs on
Android (JVM) and iOS (Native).

## Quick start

```kotlin
class CounterVault : Vault<CounterVault>() {
    val count by state { 0 }
    val label by state { "init" }
}

val vault = CounterVault()

// Subscribe.
val sub = vault { count effect { println("count=$this") } }   // count=0

// Atomic multi-state action — body's value flows into Success.
val result = vault action {
    count update { it + 1 }
    label mutate "ready"
    "transitioned to ${count.value}"
}
when (result) {
    is TransactionResult.Success -> println(result.value)      // "transitioned to 1"
    is TransactionResult.Error   -> handle(result.exception)
}

// Failed transactions roll back atomically — observer never fires.
vault action {
    count mutate 99
    error("simulated")
}

sub.dispose()
```

## Modules

| Artifact | Role |
|---|---|
| `com.vynatix:vault` | Core library — transactions, state, middleware, bridges |
| `com.vynatix:vault-coroutines` | `Flow`/`StateFlow`/`first`/`awaitValue` adapters |
| `com.vynatix:vault-compose` | `@Composable` `collectAsState` / `rememberDisposable` |

## Documentation

- **[GUIDE.md](GUIDE.md)** — full tutorial with mental model, decision charts, feature differentiation tables, technique cookbook, and API reference.
- **[BankingDemo.kt](src/commonTest/kotlin/com/vynatix/vault/demo/BankingDemo.kt)** — single-file end-to-end demo exercising every public API across a banking domain. Reads as a tutorial; runs as a test.
- **[CHANGELOG.md](CHANGELOG.md)** — release history.

## Standard library (in-tree)

The core module ships drop-in helpers under `com.vynatix.vault.middleware` and
`com.vynatix.vault.bridge`:

| Helper | Purpose |
|---|---|
| `LoggingMiddleware<V>(tag, log)` | Trace every txn's lifecycle |
| `TimingMiddleware<V>(onResult)` | Wall-clock duration per transaction |
| `ValidationMiddleware<V>(check)` | Post-body invariant check (throws → rollback) |
| `KvBridge<T>(kv, key, codec)` | Save-on-commit + load-on-attach via any `KvStore` |
| `Codec<T>` (`StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`) | Trivial encoders for common types |
| `InMemoryKvStore` | Trivial KV impl for tests + dev |

## Concurrency model

- All vault writes serialize through a per-vault reentrant lock.
- Transactions are thread-confined: only the action's owner thread sees pending
  writes. Cross-thread reads see committed values.
- `mutate` from a non-owner thread auto-wraps in a one-shot transaction —
  middleware fires; observers see only committed values.

## Building

```
./gradlew :vault:allTests              # 195+ unit tests on iOS sim + Android JVM
./gradlew :vault:detekt :vault:ktlintCheck
./gradlew :vault:apiCheck              # ABI binary-compat check
./gradlew :vault:dokkaHtml             # API doc site at build/dokka/html
./gradlew :vault:publishToMavenLocal   # publish 0.1.0 to ~/.m2/repository/com/vynatix
```
