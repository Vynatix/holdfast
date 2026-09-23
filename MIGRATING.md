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

## Source break: `Store.clock` (0.3.0)

`Store` gains an experimental `open val clock: kotlin.time.Clock` (with
`bindClock(Clock?)`), so time-relative store code can read `clock.now()` and a
test can pin it (issue #20, R10). A property named `clock` in your own `Store`
subclass now collides with it and stops compiling — whatever its type, and
including `val clock by state { … }` or a `private val clock`.

- **Rename it** (for example to `appClock`) when it is not the clock your store
  should read time through.
- **Or override it** when it is a `kotlin.time.Clock`. Overriding an
  experimental member needs the opt-in, and use a getter rather than a `val`
  initializer (an initializer's backing field is still `null` while earlier
  properties initialize):

  ```kotlin
  class SessionStore(private val source: Clock) : Store<SessionStore>() {
      @OptIn(ExperimentalStoreApi::class)
      override val clock: Clock get() = source
  }
  ```

  An override beats `bindClock`, so a test cannot pin that store's time with
  `bindClock`. Unless the store must always read one specific clock, drop your
  member and call `store.bindClock(yourClock)` instead — at app init, or with a
  fixed clock in a test.

One resolution change needs no error to surface. An unqualified `clock` can
appear inside a `Store` subclass's own body (property initializers,
`state { … }` initializers, member functions) or inside any lambda whose
receiver is a store (`store.action { … }`, `store { … }`). If it used to resolve
to a top-level `clock`, an enclosing class's property or the subclass's own
`companion object` member, it now resolves to `Store.clock`: Kotlin tries the
members of `this`, inherited ones included, before companion objects, outer
classes and top-level declarations. Without the `ExperimentalStoreApi` opt-in
this fails to compile. In a file or module that already opts in, it silently
reads the store's clock, which is `Clock.System` unless bound. Qualify the old
reference where you meant it: `Companion.clock` (or `SessionStore.clock`),
`this@Outer.clock`, or a package-qualified name. Better still, drop it and call
`bindClock`.

`bindClock` throws on a disposed store. Reading `clock` never throws.

## See also

- [`holdfast/CHANGELOG.md`](holdfast/CHANGELOG.md) — core release history
  (pre-0.1.0 internal design archive preserved with the old naming).
- [`holdfast-coroutines/CHANGELOG.md`](holdfast-coroutines/CHANGELOG.md),
  [`holdfast-compose/CHANGELOG.md`](holdfast-compose/CHANGELOG.md),
  [`holdfast-hallmark/CHANGELOG.md`](holdfast-hallmark/CHANGELOG.md) —
  per-module histories.
