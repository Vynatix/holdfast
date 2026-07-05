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

## Behavior change: nesting inside a `suspendAction` body (`:holdfast-coroutines`)

`suspendAction` now runs its body inside a single-store suspending scope, so
shapes that used to deadlock, livelock, or throw a raw kotlinx
`IllegalStateException` are now well-defined:

- A nested `store.suspendAction { }` on the **same** store joins as a
  savepoint (inner commit merges, inner rollback discards only inner writes).
  No change needed — it just works now.
- A blocking `store.action { }`, or a `suspendAtomic`/`atomic` **enrolling this
  store**, called from inside the body now throws `FrameInteropException`
  immediately (previously it hung on the held serializer mutex). Hoist the
  frame: run the frame first and put `suspendAction` inside it.

```kotlin
// Before (hung on the serializer this suspendAction already holds):
store.suspendAction {
    suspendAtomic(store, other) { … }   // now throws FrameInteropException
}

// After — hoist the frame; run suspendAction (or bare mutate/update) inside:
suspendAtomic(store, other) {
    store.suspendAction { … }           // joins as a savepoint
}
```

Disjoint-store frames inside a `suspendAction` body (`suspendAtomic(other) { }`
where `other` is not the acting store) remain legal, subject to the global
lock-order rule. Read-your-own-writes now also survives a
`withContext(Dispatchers.X)` hop inside the body (JVM/Android; on iOS/wasmJs a
nested `withContext(otherDispatcher)` section still loses it, the same gap that
applies to enrollment enforcement).

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

## Behavior change: a bare `store { }` no longer permits mutation (`:holdfast`)

A bare `store { }` invoke opens no transaction — it exists to provide the
store as receiver for non-mutating wiring (`effect`, `bridge`, `observeFrom`,
reads). Mutating directly inside it used to synthesize a separate one-shot
transaction per write, so observers fired between writes. That now throws a
teaching `IllegalStateException`.

```kotlin
// Before (piecemeal — two commits, observers fired between):
store {
    count mutate 1
    label mutate "x"
}

// After — group the writes in one transaction:
store action {
    count mutate 1
    label mutate "x"
}
```

Still legal inside `store { }`: `effect`/`bridge`/`observeFrom` wiring, reads,
a nested `action { }` (opens its own transaction), and an in-frame write on an
enrolled store during `atomic`/`suspendAtomic`. A standalone single `mutate`/
`update` is still available with the store as receiver directly — from a store
method, or `with(store) { count update { it + 1 } }` — where it wraps itself in
an implicit atomic action.

## Behavior change: `by state { }` registers eagerly at construction (`:holdfast`)

State delegates now register (and run their initializer) when the store is
constructed, not lazily on first read — so `snapshot()` and `properties` see
every declared state without a preliminary read. Two consequences to migrate:

1. **A throwing initializer fails at construction**, not on first read. If you
   relied on constructing a store whose state initializer might throw and only
   failing when that state was first read, move the fallible work out of the
   initializer.

2. **Forward-referencing initializers must be reordered.** Initializers run in
   declaration order, so a state that reads a later-declared state now fails at
   construction:

   ```kotlin
   // Before (worked lazily — `total` was only read after `items`):
   class Cart : Store<Cart>() {
       val total by state { items.value.size }   // reads items…
       val items by state { emptyList<Item>() }  // …declared later
   }

   // After — declare dependencies first:
   class Cart : Store<Cart>() {
       val items by state { emptyList<Item>() }
       val total by state { items.value.size }
   }
   // …or make `total` a read-time derivation instead:
   //   val total = computed { items.value.size }
   ```

Binaries compiled against the previous release keep the lazy behavior; only
code recompiled against this release registers eagerly.

## Behavior change: the CRTP `Self` type is enforced at construction (`:holdfast`)

A `Store` subclass must parameterize `Self` with its own class. A
mis-parameterization that previously ran until it hit a swallowed
`ClassCastException` now throws at construction (JVM/Android):

```kotlin
// Before (compiled; failed later with an opaque ClassCastException):
class Foo : Store<Bar>()

// After — Self is the declaring class:
class Foo : Store<Foo>()
```

Generic intermediate bases whose `Self` is a type variable (e.g. a custom
`abstract class Base<S : Base<S>> : Store<S>()`, or the built-in
`EventfulStore`) are unaffected. iOS/wasmJs do not enforce this (native
reflection can't recover the erased type argument), but the JVM/Android dev
loop catches it.

## Removed (ABI): accidentally-public internals (`:holdfast`, `:holdfast-coroutines`)

Members that were never intended as API were removed from the binary surface.
None are expected to be referenced by application code:

- `FileSystemKvStore` / `SuspendingFileSystemKvStore` companion constants
  (`HEX_DIGITS`, `HEX_RADIX`, `TMP_PREFIX`) are now `private`.
- `TimingMiddleware.KEY_START_MS` is now `private`.
- `MutableState`'s constructor is now `internal` — construct states with
  `store.state { … }` (the only supported path). Reading/using `MutableState`
  as a type is unchanged.
- `StoreLock` is annotated `@StoreInternalApi` — referencing it now requires
  opting in to the internal-API marker (it was never a user-facing type).

If you were (accidentally) depending on any of these, switch to the public
`state { }` / bridge APIs.

## See also

- [`holdfast/CHANGELOG.md`](holdfast/CHANGELOG.md) — core release history
  (pre-0.1.0 internal design archive preserved with the old naming).
- [`holdfast-coroutines/CHANGELOG.md`](holdfast-coroutines/CHANGELOG.md),
  [`holdfast-compose/CHANGELOG.md`](holdfast-compose/CHANGELOG.md),
  [`holdfast-hallmark/CHANGELOG.md`](holdfast-hallmark/CHANGELOG.md) —
  per-module histories.
