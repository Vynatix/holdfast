# Migrating

The per-call-site rename and removal map for Holdfast's public API. Several
changelog entries (including internal pre-0.1.0 history) link here; this file
documents the mappings that matter to users of the published artifacts today.

## Naming: vault → holdfast → Store

The library was developed internally under the name **vault**, briefly carried
the working name **Holdfast** for its central class, and ships with the central
class named `Store<Self : Store<Self>>`. The **brand, repository, Maven
artifacts** (`com.vynatix:holdfast*`), and **package** (`com.vynatix.holdfast`)
keep the holdfast name; **class and API names use `Store`**.

| Old name | Current name | Status |
|---|---|---|
| `vaultTest { }` (`:holdfast-testing`) | `storeTest { }` | Old name is a `WARNING`-level deprecated alias, kept for one minor release |
| `bindVault` | `bindStore` | Deprecated alias, kept for one minor release |
| `owningVault` | `owningStore` | Deprecated alias, kept for one minor release |
| `Vault<Self>` / `Holdfast<Self>` | `Store<Self>` | Internal pre-release names only — never published |
| `CounterVault` / `CounterHoldfast` etc. in docs/samples | `CounterStore` etc. | Doc-only sample names |

There is no `holdfastTest { }` — the testing entry point has always shipped as
`vaultTest`/`storeTest`.

## Removed: `asEagerStateFlow()` / `EagerStateFlow` (`:holdfast-coroutines`)

Replaced by the single hot-StateFlow API:

```kotlin
// Before
val flow = store.count.asEagerStateFlow()

// After — scope defaults to the owning store's Store.scope
val flow = store.count.asStateFlow(started = SharingStarted.Eagerly)
```

## Removed: the `context(scope: CoroutineScope)` overloads (`:holdfast-coroutines`)

The K2 context-parameter overloads of `State.asStateFlow`,
`SuspendingKvStore.bridge`, and `SuspendingKvStore.suspendingBridge` were
removed. Inside any coroutine body the implicit `CoroutineScope` receiver
satisfied the context parameter, so a zero-scope-arg call like
`runBlocking { state.asStateFlow(started = SharingStarted.Eagerly) }` silently
captured the ambient scope instead of the store's — attaching an eager sharing
job to `runBlocking` hung it forever.

Only the default-parameter forms remain. Migration:

- pass the scope explicitly — `state.asStateFlow(myScope)`,
  `store.bridge(key, codec, myScope)`; or
- omit it to use the owning store's scope (`asStateFlow`) or
  `Store.defaultScope` (the bridge factories) — which is what the context
  overloads were resolving away from.

## Behavior change: nested frames must enroll their stores (`atomic` / `suspendAtomic`)

Under the default `FramePolicy.Strict`, a frame nested inside another frame
may no longer introduce a store that no enclosing frame enrolls — the entry
check throws `UnenrolledStoreException` before any lock is taken. An
introduced store gets a fresh root that commits at the *nested* frame's exit
and does **not** roll back with the enclosing frame, which silently breaks
the enclosing frame's all-or-nothing promise (the same escape a bare
unenrolled write would be, closed for the same reason).

```kotlin
// Before (silently ran c as an independent transaction):
atomic(a, b) {
    atomic(c) { c.action { flag mutate true } }
}

// After — either enroll c in the outermost frame (outer rollback covers it):
atomic(a, b, c) {
    c.action { flag mutate true }
}

// …or opt in explicitly to the independent (REQUIRES_NEW-style) side frame:
atomic(a, b, policy = FramePolicy.AllowUnenrolled) {
    atomic(c) { c.action { flag mutate true } }   // commits even if a/b roll back
}
```

The same rule applies to `suspendAtomic` (the entry check is shared).
## Removed: `SuspendingKvBridge.Awaiting` (`:holdfast-coroutines`)

`SuspendingKvBridge` itself now implements `SuspendingBridge` — the nested
`Awaiting` subclass is gone, and the two factory functions produce the same
class. `suspendingBridge(...)` returns `SuspendingKvBridge<T>` (was
`SuspendingKvBridge.Awaiting<T>`); `bridge(...)` is a `WARNING`-level
deprecated alias of `suspendingBridge(...)`.

```kotlin
// Before
val b: SuspendingKvBridge.Awaiting<Long> = kv.suspendingBridge("balance", LongCodec)
val f: SuspendingKvBridge<Long> = kv.bridge("balance", LongCodec)   // never awaited

// After — one class, one truth
val b: SuspendingKvBridge<Long> = kv.suspendingBridge("balance", LongCodec)
```

Behavior change for former `bridge(...)` products: they are now
`SuspendingBridge`s, so `suspendAction`'s commit phase **awaits** their writes
(strictly more durable, adds per-commit latency, and no conflation across
`suspendAction` commits). Under sync `action { }` nothing changes — saves stay
fire-and-forget through the conflated channel. Callers who explicitly relied
on `Awaiting`'s direct-launch sync publish now get the conflated-channel path
instead; use `suspendAction` if every intermediate value must persist.

## See also

- [`holdfast/CHANGELOG.md`](holdfast/CHANGELOG.md) — core release history
  (pre-0.1.0 internal design archive preserved with the old naming).
- [`holdfast-coroutines/CHANGELOG.md`](holdfast-coroutines/CHANGELOG.md),
  [`holdfast-compose/CHANGELOG.md`](holdfast-compose/CHANGELOG.md),
  [`holdfast-hallmark/CHANGELOG.md`](holdfast-hallmark/CHANGELOG.md) —
  per-module histories.
