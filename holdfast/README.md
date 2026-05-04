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
| `com.vynatix:vault` | Core library — transactions, state, middleware, bridges, snapshot/restore, derived state, cross-vault `atomic`, encryption transformer, file-system store |
| `com.vynatix:vault-coroutines` | `Flow` / `StateFlow` / `first` / `awaitValue` adapters + `suspendAction { … }` for async transactional bodies |
| `com.vynatix:vault-compose` | `@Composable` `collectAsState` / `rememberDisposable` |
| `com.vynatix:validation` | Standalone KMP boundary-validation library — `Boxed` / `Rule` / `Validator` / composite DSL / `ValidationResult` with multi-error accumulation. No Vault dep |
| `com.vynatix:validation-coroutines` | Suspend extension — `SuspendRule` / `SuspendValidator` / `suspendValidator { }` DSL for async (DB-lookup, remote-feature-gate) validation |
| `com.vynatix:vault-validation` | Vault adapter — `ValidatingTransformer` / `Vault.boxed { }` state factory / `BoxedCodec` for KvBridge persistence / `BoxedHandle` |
| `com.vynatix:vault-validation-coroutines` | Suspend-side Vault adapter — `Vault.suspendValidateAndMutate` integrating `SuspendValidator` with `suspendAction` |

## Documentation

- **[GUIDE.md](GUIDE.md)** — full tutorial with mental model, decision charts, feature differentiation tables, technique cookbook, and API reference.
- **[BankingDemo.kt](src/commonTest/kotlin/com/vynatix/vault/demo/BankingDemo.kt)** — single-file end-to-end demo exercising every public API across a banking domain. 9 `@Test`s covering 1.0 + 1.1 surface; reads as a tutorial, runs as a test.
- **[CHANGELOG.md](CHANGELOG.md)** — release history.

## Major capabilities

### 1.0 surface

- **Transactional `action { }`** — atomic multi-state writes; body's return value flows into `Success<R>`.
- **Effects + bridges** — observe state changes; two-way external sync via `Bridge<T>`; inbound-only via `observeFrom(Observable<T>)`.
- **Middleware** — wrap every transaction with `LoggingMiddleware`, `TimingMiddleware`, `ValidationMiddleware`, or your own.
- **Transformers** — normalize on write / project on read, including the asymmetric case where `set` and `get` produce different shapes.
- **Cross-vault state ownership** — foreign-vault states are rejected at compile time of the call (runtime ownership check at O(1)).

### 1.1 additions

- **`EncryptingTransformer(Cipher)`** — store ciphertext, read plaintext. Asymmetric-rollback-safe. Ships with educational `XorCipher`; production users plug their own AES via `javax.crypto` / CryptoKit.
- **`FileSystemKvStore(path)`** — disk-backed `KvStore` for `KvBridge`, atomic writes via tempfile + rename on JVM/Android and `NSData.writeToURL(atomically=true)` on iOS.
- **`Vault.snapshot()` / `Vault.restore()`** — capture and restore raw state, asymmetric-transformer-safe (raw round-trip means no double-encrypt).
- **`Vault.computed { } / Vault.derived(sources) { }`** — read-time-computed and push-recomputed derived states; the latter returns its own observable `State<T>` plus a `Disposable`.
- **`atomic(vararg vaults) { }`** — cross-vault transactions. Sorts by `lockOrderKey` for deadlock-safe lock acquisition; body throw rolls back every vault.
- **`suspendAction { }`** (`vault-coroutines`) — async-aware transactional body. Mutually exclusive with blocking `action` on the same vault via an internal coroutine `Mutex`.
- **Validation 0.4.0** (`validation` + `validation-coroutines` + `vault-validation` + `vault-validation-coroutines`) — standalone KMP refinement-types library, four-module split. `Validator<IN, OUT>` unified interface; class-based leaves (`object EmailValidator : BoxedValidator<String, Email>()`) and DSL-based composites (`val UserValidator = validator<User> { field("email", { it.email }, EmailValidator); each("addresses", { it.addresses }, AddressValidator); forKey("tags", { it.tags }, "primary", TagValidator) }`). Multi-error accumulation via sealed `ValidationResult<O>` (no Arrow dep). Rich `Violation(message, path, code, rule, args)` for i18n via the `MessageResolver` interface. 23 prebuilt rules (14 essentials + 9 format regexes: email, URL, UUID, IPv4/6, E.164, ISO8601, IBAN). `Vault.boxed(...) { ... }` state factory; `BoxedCodec` for KvBridge persistence; `BoxedHandle` for ergonomic state+validator pairs; suspend integration via `Vault.suspendValidateAndMutate`. `Validator.describe()` for schema export / introspection. `Transformer<T>.then(other)` for transformer composition. Konform migration doc shipped.

## Standard library (in-tree)

The core module ships drop-in helpers under `com.vynatix.vault.middleware`,
`com.vynatix.vault.bridge`, and `com.vynatix.vault.crypto`:

| Helper | Purpose |
|---|---|
| `LoggingMiddleware<V>(tag, log)` | Trace every txn's lifecycle |
| `TimingMiddleware<V>(onResult)` | Wall-clock duration per transaction |
| `ValidationMiddleware<V>(check)` | Post-body invariant check (throws → rollback) |
| `KvBridge<T>(kv, key, codec)` | Save-on-commit + load-on-attach via any `KvStore` |
| `Codec<T>` (`StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`) | Trivial encoders for common types |
| `InMemoryKvStore` | Trivial KV impl for tests + dev |
| `FileSystemKvStore(rootPath)` | Disk-backed `KvStore` (expect/actual; JVM + iOS) |
| `Cipher` + `EncryptingTransformer(Cipher)` | Encrypt-on-write, decrypt-on-read transformer |
| `XorCipher(seed)` | KMP-pure educational `Cipher` (NOT production-grade — documented) |

## Concurrency model

- All vault writes serialize through a per-vault reentrant lock.
- Transactions are thread-confined: only the action's owner thread sees pending
  writes. Cross-thread reads see committed values.
- `mutate` from a non-owner thread auto-wraps in a one-shot transaction —
  middleware fires; observers see only committed values.
- `atomic(v1, v2, …)` sorts vaults by a process-monotonic `lockOrderKey` and
  acquires locks in order — deadlock-safe across any combination of vaults.
- `suspendAction` and blocking `action` are mutually exclusive on the same
  vault via a coroutine `Mutex` installed lazily through an internal
  `AsyncSerializer` hook.

## Building

```
./gradlew :vault:allTests              # 305+ unit tests on iOS sim + Android JVM
./gradlew :vault:detekt :vault:ktlintCheck
./gradlew :vault:apiCheck              # ABI binary-compat check
./gradlew :vault:dokkaHtml             # API doc site at build/dokka/html
./gradlew :vault:publishToMavenLocal   # publish 0.1.0 to ~/.m2/repository/com/vynatix

# Companion modules
./gradlew :vault-coroutines:allTests :vault-coroutines:apiCheck
./gradlew :vault-compose:allTests    :vault-compose:apiCheck
./gradlew :validation:allTests       :validation:apiCheck
./gradlew :validation-coroutines:allTests :validation-coroutines:apiCheck
./gradlew :vault-validation:allTests :vault-validation:apiCheck
./gradlew :vault-validation-coroutines:allTests :vault-validation-coroutines:apiCheck
```
