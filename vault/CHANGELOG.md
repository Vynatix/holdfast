# Changelog

All notable changes to the Vault library are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-05-02

First versioned release. The library is **not yet 1.0** — APIs may evolve based
on early-adopter feedback. Binary compatibility is tracked via
`binary-compatibility-validator` from this release forward; future ABI breaks
will appear as diffs in `vault/api/*.api`.

### Added — Core
- `State<T>.update { … }` — read-modify-write convenience.
- `State<T>.observeFrom(Observable<T>)` — inbound-only push subscription.
- Generic `action<R>(body): TransactionResult<R>` — body's value carried in
  `TransactionResult.Success.value`.
- `state(distinct = true)` — opt-in same-value dedup for observers and bridges.
- `Transaction.modifiedStates: Set<State<*>>` — owner-thread-only read view of
  pending-write keys, for audit middleware and userland undo.
- `@VaultActionDsl` `@DslMarker` on `Vault<Self>` — prevents accidental
  outer-receiver access in nested DSLs.
- `Vault.uncaughtObserverHandler: ((Throwable) -> Unit)?` — opt-in surfacing
  of commit-fire observer exceptions (default null preserves silent-swallow).

### Added — Standard library (`com.vynatix.vault.middleware`, `com.vynatix.vault.bridge`)
- `LoggingMiddleware<V>(tag, log)` — drop-in tracing of every transaction.
- `TimingMiddleware<V>(onResult)` — wall-clock duration measurements.
- `ValidationMiddleware<V>(check)` — post-body invariant check with rollback.
- `Codec<T>` interface + `StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`.
- `KvStore` interface + `InMemoryKvStore` impl.
- `KvBridge<T>(kv, key, codec)` — generic save-on-commit + load-on-attach
  bridge backed by any `KvStore`.

### Added — New modules
- **`com.vynatix:vault-coroutines`** — `State<T>.asFlow`, `asStateFlow(scope)`,
  `asEagerStateFlow`, `first(predicate)`, `awaitValue(target)`.
- **`com.vynatix:vault-compose`** — `@Composable State<T>.collectAsState()`,
  `@Composable rememberDisposable { … }`.

### Changed (BREAKING)
- `Vault.action` is now generic in the body's return type. `TransactionResult`
  is a `sealed interface TransactionResult<out R>`:
  - `Success<R>(transaction, value: R)`
  - `Error(exception, transaction): TransactionResult<Nothing>`

  Migration:
  - `assertIs<TransactionResult.Success>(r)` → `assertIs<TransactionResult.Success<*>>(r)`
    (or `<Unit>` where the body returns Unit).
  - `vault action { … }: TransactionResult` → `: TransactionResult<R>` (R inferred
    from the body, or `Unit` for void bodies — usually inferred automatically).
- `infix State<T>.bridge(b)` now accepts `Bridge<T>?` (null detaches). Existing
  non-null callers compile unchanged.
- `Transaction.endTime` is now `Long?` (epoch millis) instead of `String?`.
- `Vault.middlewares(...)` documentation corrected: the LAST argument is the
  outermost middleware. Place logging/audit middleware last so `onTransactionError`
  sees inner middlewares' failures.
- The `UUID` and `Timestamp` classes are removed in favor of `kotlin.uuid.Uuid`
  and `kotlinx.time.Clock`.

### Fixed
- `MutableState.bridge` setter no longer leaks the previous bridge's inbound
  observer registration on swap or null-set.
- `getMutableState` ownership check is now O(1) via `MutableState.owningVault`,
  down from O(N) over `_properties.values`.
- `removeState` / `clearStates` now dispose observers and bridges silently;
  removing a state with pending writes in an active transaction throws
  `IllegalStateException` instead of silently orphaning them.

### Documentation
- Full KDoc on every public type and member.
- `GUIDE.md` (1100+ lines): mental model, decision charts, feature
  differentiation tables, cookbook, concurrency model, API reference.
- `BankingDemo.kt`: single-file end-to-end exercise of every public API.
- README per module.
- Dokka HTML generation per module.

### Deferred to a future release
- Cross-vault atomic actions (would require global lock ordering — out of scope).
- Snapshot/restore, derived state, suspending action — each needs a dedicated
  design pass (non-trivial). `Transaction.modifiedStates` enables userland
  workarounds in the meantime.
