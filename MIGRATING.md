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

## Behavior change: writes from an observer into its own committing store (0.4.0)

An effect (or `observe` callback, bridge publish, event collector) runs during
its store's commit fanout — after the transaction has applied its writes, while
it is still the store's active transaction. A write back into that store from
there used to be staged into the finished transaction and silently lost. It is
now refused (issue #20):

- `mutate`, `update` and `emit` on that store throw `IllegalStateException`
  ("Cannot write S.x: S's transaction '…' has already applied its writes …").
  Thrown out of an effect, it reaches `uncaughtObserverHandler` — or the default
  log below.
- A nested `action { }` or `atomic(...) { }` on that store returns
  `TransactionResult.Error` carrying that exception, without running its body.
  Check that result (e.g. `.getOrThrow()`, which rethrows into
  `uncaughtObserverHandler`): an effect that ignores it still drops the write,
  and nothing is logged.

If a test or an app relied on such a write "working", it never did: the value
never committed. Pick one of:

- **Write in the action itself.** If a write to `x` should always follow a
  write to `y`, do both in the same `action`.
- **Derive the value.** `computed { }` recomputes on read; `derived(...)`
  recomputes in its own transaction after each source commit.
- **Run a follow-up action after the commit**: a follow-up `action { }` from
  another thread, or one launched on a dispatcher that does not run it inline,
  checking the result —
  `store.scope.launch(Dispatchers.Default) { store action { … }.getOrThrow() }`.
  On `Dispatchers.Unconfined`, or `Dispatchers.Main.immediate` when the commit
  already runs on the main thread (a store bound to `viewModelScope`, say), the
  launched body runs at once, inside the commit's fanout, and is refused like
  any nested action; the launch alone drops that `Error`, and `.getOrThrow()`
  turns it into a coroutine failure that reaches the scope's exception handler.
  Another thread's `action` is not refused: it waits for the
  store and then commits normally. Use `action` there rather than a bare
  `mutate`/`update`: while a `suspendAction`/`suspendAtomic` holds the store, a
  bare write from any thread stages into its transaction — before that
  transaction applies it silently joins it, after it throws ("Cannot write S.x:
  a suspendAction or suspendAtomic holds S …") until the suspending call
  returns.

Writes to a *different* store from an effect keep working (subject to the
cross-store lock-ordering caveat in the README's known issues).

## Behavior change: post-commit failures are logged by default (0.4.0)

With no `Store.uncaughtObserverHandler` set, a throwing effect, bridge publish
(`Bridge.publish` or `SuspendingBridge.publishAwaited`) or `derived` recompute
used to be dropped without a trace. It is now logged: a line naming the store,
then the stack trace, on standard error (JVM/Android) or standard output
(iOS/wasmJs). Nothing else changes — the commit still stands and the remaining
observers still run.

- To keep failures out of standard error, route them:
  `store.uncaughtObserverHandler = { e -> logger.warn("post-commit failure", e) }`.
- To restore the old silence deliberately: `store.uncaughtObserverHandler = { }`.

## See also

- [`holdfast/CHANGELOG.md`](holdfast/CHANGELOG.md) — core release history
  (pre-0.1.0 internal design archive preserved with the old naming).
- [`holdfast-coroutines/CHANGELOG.md`](holdfast-coroutines/CHANGELOG.md),
  [`holdfast-compose/CHANGELOG.md`](holdfast-compose/CHANGELOG.md),
  [`holdfast-hallmark/CHANGELOG.md`](holdfast-hallmark/CHANGELOG.md) —
  per-module histories.
