# The Vault Library — Complete Guide

`com.vynatix.vault` is a Kotlin Multiplatform state-management library built around
**transactional state**: every mutation lives inside a transaction; observers see only
committed values; failed transactions never leak. It has no Compose dependency, no
coroutine dependency in its API, and no dependencies on Android or iOS frameworks.

This guide covers the mental model, the seven primitives, the full transaction
workflow, decision charts for picking the right tool, feature differentiation tables,
a techniques cookbook, the concurrency model, and a terse API reference.

---

## Table of Contents

1. [Mental Model](#1-mental-model)
2. [The Shape of a Vault](#2-the-shape-of-a-vault)
3. [Quickstart](#3-quickstart)
4. [The Seven Primitives](#4-the-seven-primitives)
5. [Transaction Lifecycle (Workflow Diagram)](#5-transaction-lifecycle-workflow-diagram)
6. [Visibility Model — Who Sees What When](#6-visibility-model--who-sees-what-when)
7. [Decision Charts](#7-decision-charts)
8. [Feature Differentiation Tables](#8-feature-differentiation-tables)
9. [Techniques Cookbook](#9-techniques-cookbook)
10. [Concurrency Model](#10-concurrency-model)
11. [Testing Patterns](#11-testing-patterns)
12. [Common Pitfalls](#12-common-pitfalls)
13. [API Reference](#13-api-reference)
14. [The 1.1 Surface](#14-the-11-surface) — snapshot/restore, derived, atomic, encryption, FileSystemKvStore, suspendAction

---

## 1. Mental Model

### 30-second pitch

A `Vault` is a state container whose unit of consistency is a **transaction**.
You read state through `state` properties; you mutate inside `action { … }`;
you subscribe with `effect`. Every transaction is **all-or-nothing** — if its
body throws, no observer was ever told about the intermediate writes, no
external bridge was published to, and the stored value is byte-for-byte the
same as before the action ran.

### Why it exists

| Problem | Without Vault | With Vault |
|---|---|---|
| Multi-state writes can leave the system half-updated | You add ad-hoc try/catch and revert by hand | `action { … }` is atomic; throw rolls back everything |
| Observers fire mid-write and see "impossible" intermediate states | You debounce or guard at every callsite | Observers only fire on commit |
| Asymmetric serialization (`toJson`/`fromJson`) drifts on rollback | You hand-roll storage of pre-images | `transformer` keeps `set` and `get` separate; rollback never touches `set` |
| Cross-vault writes silently corrupt the wrong store | Casts succeed, bug ships to prod | Ownership check throws on first foreign mutate |
| Persistence layer publishes during rollback, polluting external systems | You manually distinguish "real" writes from rollback writes | Bridge publishes only on commit |
| Adding logging / persistence requires touching every action | Cross-cutting concerns are scattered | `Middleware` wraps the whole transaction |

### How Vault compares to other patterns

| Pattern | Mutation site | Atomicity unit | Observer sees |
|---|---|---|---|
| `var` field + listeners | Anywhere | Single field | Every write |
| `MutableStateFlow` | `value =` | Single state | Every distinct value |
| Redux/Reducer | `dispatch(action)` | One action's reduce | Reducer's return |
| **Vault** | `mutate` inside `action { }` | Whole `action { }` | Committed value only |

Vault is closest to "Redux with locality" — your mutations are co-located
with the state they touch, not pushed through a central reducer, but
visibility and atomicity come from a transaction boundary.

---

## 2. The Shape of a Vault

A vault subclass declares its state using delegated properties. The base
class is generic in `Self` (the curiously-recurring-template pattern) so
extensions like `infix fun State<T>.mutate(T)` resolve against the concrete
vault type.

```kotlin
class CounterVault : Vault<CounterVault>() {
    val count by state { 0 }
    val label by state { "initial" }
    val email by state(EmailNormalizer()) { "" }   // with transformer
}
```

### Type hierarchy at a glance

```
Vault<Self>                         abstract base; holds states + middleware + active txn
 ├── state(transformer?, init)      registers a MutableState by property name
 ├── action { … }                   transactional batch
 ├── invoke { … }                   plain context block (just runs the lambda)
 └── extension members on State<T>:
       effect, bridge, mutate

State<T>                            read contract: val value: T
 └── MutableState<T>                concrete; carries observers, transformer, bridge ref

Transaction                         active|committed|rolledBack|failed
 ├── pendingWrites                  state → post-transformer.set staged value
 └── parent                         null for top-level; non-null for savepoints

Middleware<T>                       onTransactionStarted/Completed/Error hooks
Bridge<T>                           Observable<T> + Publisher<T> — external sync
Transformer<T>                      pure set(T): T, get(T): T, optional shouldTransform(T)
Disposable                          single dispose() method
```

### File layout

```
vault/src/commonMain/kotlin/com/vynatix/vault/
  Vault.kt          base class, action/mutate/effect/bridge/invoke, ownership check
  MutableState.kt   per-state observers, bridge, transformer, applyCommitted
  Transaction.kt    pendingWrites, commit/rollback, status state machine
  Middleware.kt     three-hook interceptor with metadata bag
  Contract.kt       State, Bridge, Transformer, Initializer, StateDelegate, Disposable
  VaultLock.kt      reentrant mutex over kotlinx.atomicfu SynchronizedObject
  UUID.kt           v4 UUID generator (used for unnamed transactions)
  platform/
    Threading.kt    expect currentThreadId, threadYield
```

---

## 3. Quickstart

```kotlin
// 1. Define a vault.
class TodoVault : Vault<TodoVault>() {
    val items by state { emptyList<String>() }
    val draft by state { "" }
}

// 2. Create an instance.
val vault = TodoVault()

// 3. Subscribe.
val sub = vault { items effect { println("items=$this") } }
// fires immediately with the initial value: items=[]

// 4. Mutate atomically.
vault action {
    draft mutate "buy milk"
    items mutate items.value + draft.value
    draft mutate ""
}
// effect fires once per modified state, post-commit:
//   items=[buy milk]

// 5. Failed transactions roll back.
vault action {
    items mutate listOf("never visible")
    error("simulated failure")
}
// effect fires zero times. items.value is still ["buy milk"].

// 6. Cleanup.
sub.dispose()
```

That is the complete day-one usage. Everything after this is depth.

---

## 4. The Seven Primitives

### 4.1 `state(transformer?, init)` — Declare

Registers a `MutableState` keyed by the property name. The first read creates
it; subsequent reads of the same property return the same `State` (delegate
identity is preserved across reads).

```kotlin
class Profile : Vault<Profile>() {
    val name by state { "anon" }                    // identity transformer
    val email by state(EmailNormalizer()) { "" }    // applies on set/get
    val tags by state { emptySet<String>() }        // any T : Any
}
```

`T` must be `: Any` (no nulls). Use a sentinel or wrap in a sealed type
if you need a "no value" case.

### 4.2 `action { ... }` — Transactional batch

Runs the lambda inside a transaction. Mutations buffer; on success they
commit and observers fire; on throw the buffer is dropped and nobody
notices the attempt happened.

```kotlin
val result: TransactionResult = vault action {
    items mutate listOf("a", "b")
    draft mutate "drafted"
}
when (result) {
    is TransactionResult.Success -> {}
    is TransactionResult.Error   -> log(result.exception)
}
```

**Returns** `TransactionResult` (sealed: `Success | Error`). It never throws —
exceptions are captured into `Error`.

**Nested actions** form a savepoint chain. The inner `action` becomes a
child transaction whose `parent` is the outer's. Inner commit merges the
inner's pending writes into the outer's; inner rollback drops just the
savepoint; outer rollback discards everything.

```kotlin
vault action {           // T_outer
    a mutate 1
    vault action {       // T_inner with parent = T_outer
        b mutate 2
    }                    // T_inner.commit merges {b->2} into T_outer
    error("outer fails") // discards both {a->1} and {b->2}
}
// a.value == initial, b.value == initial.
```

### 4.3 `mutate(value)` — Write

`State<T>.mutate(T)` is an extension on `Vault<Self>`. It buffers the
post-`transformer.set` value into the active transaction's `pendingWrites`.

```kotlin
vault action {
    count mutate count.value + 1
}
```

**Inside an active transaction owned by the current thread**: buffers the
write. Reads on the same thread (`count.value`) see the pending write
(read-your-own-writes). Reads on other threads still see the committed
value, never the pending one.

**Outside any transaction (or on a non-owner thread)**: synthesizes a
one-shot `action { this@mutate mutate that }`. Middleware fires; observers
see only the committed value. This means standalone `vault { x mutate v }`
is equivalent to `vault action { x mutate v }` — never a "raw" write that
skips observers, middleware, or commit semantics.

### 4.4 `effect { … }` — Observe

`State<T>.effect(T.() -> Unit): Disposable` subscribes a function to a
state. It fires:

- **Once immediately** with the current `value` (post-`transformer.get`).
- **Once per top-level commit** that actually changes the raw stored value.
  (Same-value commits do not re-fire — see §6.)

```kotlin
val sub = vault { count effect { println("count=$this") } }
// → count=0   (initial)
vault action { count mutate 5 }
// → count=5
sub.dispose()
vault action { count mutate 6 }
// (no output — disposed)
```

The receiver `this` is the new value. Returning a `Disposable` lets you
unsubscribe; observers held forever are a memory leak.

### 4.5 `bridge(b)` — External sync

`State<T>.bridge(Bridge<T>)` connects a state to an external system that
implements both `Observable<T>` (push to vault) and `Publisher<T>` (pull
from vault).

```kotlin
val persistence = object : Bridge<List<String>> {
    private val cb = mutableListOf<(List<String>) -> Unit>()
    override fun observe(observer: (List<String>) -> Unit): Disposable {
        cb.add(observer); return Disposable { cb.remove(observer) }
    }
    override fun publish(value: List<String>): Boolean {
        File("todos.json").writeText(Json.encodeToString(value)); return true
    }
}
vault { items bridge persistence }
```

**Outbound** (`publish`) fires only on commit, never during the action body
or on rollback. **Inbound** (`observe`) updates the state via
`applyFromBridge`, which writes through the transformer's `set`, fires
observers, but does NOT call `publish` again — preventing publish loops.

### 4.6 `middlewares(...)` — Intercept

`Vault.middlewares(vararg)` registers middleware that wrap every transaction.
Each middleware sees `onTransactionStarted` before the body runs,
`onTransactionCompleted` after the body returns successfully, and
`onTransactionError` if the body throws.

```kotlin
class Logger<V : Vault<V>> : Middleware<V>() {
    override fun onTransactionStarted(c: MiddlewareContext<V>) =
        log("→ ${c.transaction.id}")
    override fun onTransactionCompleted(c: MiddlewareContext<V>) =
        log("✓ ${c.transaction.id}")
    override fun onTransactionError(c: MiddlewareContext<V>, e: Throwable) =
        log("✗ ${c.transaction.id}: $e")
}

vault.middlewares(Logger())
```

Middleware composes left-to-right: `middlewares(A, B)` runs A's started
hook first, then B's, then the user action, then B's completed, then A's.
The chain is rebuilt fresh per `action`, so middleware added later applies
to subsequent actions.

`MiddlewareContext.metadata` is a per-transaction `MutableMap<String, Any>`
for cross-middleware communication.

### 4.7 `invoke { … }` — Context block

`vault { … }` — the operator on `Vault` — runs `block(self)` with no locks
and no transaction. It exists so vault-extension members like `effect` and
`bridge` can be called with vault-as-receiver:

```kotlin
val d = vault { count effect { … } }      // effect is an extension on Vault<Self>
val v = vault { count.value }             // plain read
```

`vault { x mutate y }` is a special case — `mutate` itself synthesizes an
implicit action when there is no active transaction. So this form still
goes through middleware and observers.

---

## 5. Transaction Lifecycle (Workflow Diagram)

```
            ┌─────────────────────────────────────────────────────┐
            │  vault action { body }   on owner thread            │
            └──────────────────────┬──────────────────────────────┘
                                   │
                ┌──────────────────▼─────────────────────┐
                │ Acquire transactionLock (reentrant)    │
                │ parent = _activeTransaction (may be ≠ null)│
                │ txn = Transaction(id, parent, threadId)│
                │ _activeTransaction = txn               │
                └──────────────────┬─────────────────────┘
                                   │
              ┌────────────────────▼──────────────────────┐
              │  middlewareChain { body() }               │
              │  ┌─────────────────────────────────────┐  │
              │  │ for each mutate(v) in body:         │  │
              │  │   txn.pendingWrites[state] =        │  │
              │  │     state.beforeSet(v)              │  │
              │  │   ── observers/bridge silent ──     │  │
              │  └─────────────────────────────────────┘  │
              └────────────────────┬──────────────────────┘
                                   │
                ┌──────────────────┼──────────────────┐
                │                  │                  │
            body returns       body throws          inner txn
            normally           (any exception)      throws
                │                  │                  │
                ▼                  ▼                  ▼
        ┌───────────────┐  ┌───────────────┐  ┌──────────────────┐
        │ txn.commit()  │  │ txn.rollback()│  │ propagates to    │
        │               │  │               │  │ outer's catch →  │
        │ if parent !=  │  │ pendingWrites │  │ outer rollback   │
        │   null:       │  │   .clear()    │  │                  │
        │  parent.merge │  │ status →      │  │                  │
        │   pending     │  │   RolledBack  │  │                  │
        │ else:         │  │               │  │                  │
        │  for each:    │  │ observers     │  │                  │
        │   state.apply │  │   NOT fired   │  │                  │
        │   Committed → │  │ bridge        │  │                  │
        │   notify obs, │  │   NOT pub'd   │  │                  │
        │   pub bridge  │  │               │  │                  │
        │ status →      │  │               │  │                  │
        │   Committed   │  │               │  │                  │
        └───────┬───────┘  └───────┬───────┘  └─────────┬────────┘
                │                  │                    │
                └──────────────────┼────────────────────┘
                                   │
              ┌────────────────────▼──────────────────────┐
              │ finally:                                  │
              │   _activeTransaction = parent             │
              │ release transactionLock                   │
              │ return Success(txn) | Error(e, txn)       │
              └───────────────────────────────────────────┘
```

### Transaction status state machine

```
            ┌─────────┐
            │ Active  │ ─── mutate(v) ──┐
            └────┬────┘                 │
                 │                      │
        commit() │ rollback()           │  pendingWrites[state] = ...
        success  │  / catch             │
                 ▼                      ▼
         ┌────────────┐          ┌────────────┐
         │ Committed  │          │ RolledBack │
         └────────────┘          └────────────┘
        (terminal)              (terminal)

   Active → Failed: only when commit/rollback themselves throw.
   Committed/RolledBack/Failed are terminal — further commit/rollback are no-ops.
```

The terminal-state idempotency is what makes
`runCatching { txn.rollback() }` after a successful action a no-op rather
than a state corruptor.

---

## 6. Visibility Model — Who Sees What When

| Action on T1 (owner) | T1's `state.value` | T2's `state.value` | T1's effects | Bridge |
|---|---|---|---|---|
| Before action starts | committed v0 | committed v0 | — | — |
| `mutate v1` inside `action` | post-`get(v1)` ← pending | committed v0 | (silent) | (silent) |
| `mutate v2` after v1 (same action) | post-`get(v2)` ← pending | committed v0 | (silent) | (silent) |
| Action body throws | committed v0 | committed v0 | (never fired for v1/v2) | (never published) |
| Action commits with final = v2 | committed v2 | committed v2 | fires once with `get(v2)` | publishes raw v2 |
| Action commits with final = v0 (same as start) | committed v0 | committed v0 | (no fire — dedup) | (no publish — dedup) |

Three things to internalize:

1. **Observers and bridges only ever see committed values.** No mid-transaction
   leak. No rolled-back leak. Same-value commits are deduped.
2. **Read-your-own-writes is owner-thread-only.** The thread executing the
   action sees its own pending writes. Other threads see committed values
   only — they cannot witness "in-flight" mutations.
3. **Transformer.get applies to reads and observer payloads alike.**
   `state.value` and the value passed to `effect`'s receiver are the
   same. Asymmetric transformers do not produce two different views.

---

## 7. Decision Charts

### 7.1 "Where should I put this write?"

```
                  Need write?
                       │
                       ▼
       ┌───────────────────────────────┐
       │ Multi-state, must be atomic?  │
       └──────────┬──────────┬─────────┘
              yes │      no  │
                  ▼          ▼
       ┌──────────────┐  ┌──────────────────────┐
       │ action { … } │  │ Single-state, no     │
       │              │  │ atomicity needed?    │
       └──────────────┘  └────────┬─────────────┘
                                  │
                            ┌─────┴──────┐
                          yes │      no  │
                              ▼          ▼
                       ┌──────────┐   ┌──────────────┐
                       │  v {     │   │ action { … } │
                       │   x      │   │  (use this   │
                       │   mutate │   │  always when │
                       │   y      │   │  unsure)     │
                       │ }        │   └──────────────┘
                       │ — same   │
                       │ outcome  │
                       │ as       │
                       │ action   │
                       └──────────┘
```

When in doubt, prefer `action`. The standalone-mutate form is the same
runtime cost but reads less explicitly as "this is a write." Reserve the
standalone form for one-liners where the action wrapper would be noise.

### 7.2 "Should I add a transformer?"

```
            Need to normalize / validate / encode on write?
                              │
                  ┌───────────┴────────────┐
                yes │                  no  │
                    ▼                      ▼
            ┌───────────────┐      ┌──────────────┐
            │ Reading is    │      │ Don't.       │
            │ also          │      │ Use a plain  │
            │ asymmetric?   │      │ state { }    │
            └───┬────────┬──┘      └──────────────┘
            yes │     no │
                ▼        ▼
       ┌──────────────┐ ┌────────────────────┐
       │ Transformer  │ │ Transformer with   │
       │ with both    │ │ identity get(),    │
       │ set() and    │ │ non-identity set() │
       │ get()        │ │ (e.g. trim, lower) │
       │ implemented  │ │                    │
       └──────────────┘ └────────────────────┘
```

Use a transformer when **the write should be stored in a different form
than the user provided** (`trim`, `lowercase`, encrypt) or **the read
should be in a different form than what is stored** (decrypt, format).
The library guarantees that rollback never re-applies `set` on the
recorded raw value, so asymmetric transformers do not drift.

Avoid transformers for **conditional writes** (use `action`'s ability to
throw) or **derived values** (just read inside an `action` and write to
a separate state).

### 7.3 "Bridge or effect?"

```
                What do I want to do on change?
                              │
            ┌─────────────────┼──────────────────┐
        push to               run side           push, AND
        external system       effect             listen back
        only                  in-process         (bidirectional sync)
            │                     │                     │
            ▼                     ▼                     ▼
     ┌────────────┐        ┌────────────┐        ┌────────────┐
     │  bridge    │        │  effect    │        │  bridge    │
     │  with a    │        │            │        │  (full     │
     │  no-op     │        │            │        │  Bridge<T>)│
     │  observe { }│       │            │        │            │
     └────────────┘        └────────────┘        └────────────┘
                                   │
                                   ▼
                           Compose: prefer
                           collectAsState
                           on a StateFlow that
                           you publish from
                           an effect, OR a
                           StateFlow Bridge.
```

| | `effect` | `bridge` |
|---|---|---|
| Direction | one-way (vault → callback) | two-way (vault ↔ external) |
| Inbound writes | not supported | yes, via `observe` |
| Per-state count | many | one |
| Disposed by | returned `Disposable` | reassigning `bridge =` (or never) |
| Use for | UI updates, logging, computed | persistence, server sync, Compose StateFlow |

### 7.4 "Action vs nested action vs invoke"

```
   I'm currently inside…           and I want to…              do this
   ────────────────────────────    ─────────────────────────   ─────────────
   nothing                         atomic multi-write         action { … }
   nothing                         single read or effect      vault { … }
   an outer action                 atomic sub-batch with own  action { … }
                                   savepoint semantics        (becomes nested)
   an effect callback              atomic write               action { … }
                                                              (the outer txn is
                                                              already committed
                                                              by the time effects
                                                              fire — your action
                                                              becomes top-level)
   a middleware hook               read state                 context.vault.x.value
   a middleware hook               write state                NOT recommended;
                                                              use action's body to
                                                              orchestrate writes
```

---

## 8. Feature Differentiation Tables

### 8.1 Subscription mechanisms

| | `effect` | `bridge` | `Middleware` |
|---|---|---|---|
| Granularity | per-state | per-state | per-vault (all transactions) |
| When it fires | per-commit, on changed states | outbound: per-commit / inbound: any time | start, complete, error of every txn |
| Has access to the transaction | no | no | yes (in `MiddlewareContext`) |
| Can mutate state | yes (via action) | yes (via observe→applyFromBridge) | yes (next() runs body, can wrap with logic) |
| Initial fire on subscribe | yes | yes (via observe call) | no (only on next txn) |
| Use case | UI binding, logging | persistence, sync, StateFlow adapter | logging, validation, audit, metrics |

### 8.2 Mutation paths

| | inside `action` (owner thread) | outside any action | inside action, foreign thread |
|---|---|---|---|
| Buffered? | yes — pendingWrites | yes — implicit one-shot action | yes — implicit one-shot action |
| Middleware fires? | once for the enclosing action | once for the implicit action | once for the implicit action |
| Read-your-own-writes? | yes | n/a (single write) | n/a |
| Throws on a finalized txn? | yes — `IllegalStateException` | n/a | n/a |
| Cost | O(1) into a map | one full transaction setup | one full transaction setup |
| Recommended? | preferred | acceptable for one-liners | acceptable |

### 8.3 Transformer vs Middleware

| | `Transformer<T>` | `Middleware<V>` |
|---|---|---|
| Scope | one state | the whole transaction (cross-cutting) |
| Pure? | yes — `set` and `get` are required pure | no — can `log`, `metrics.record`, etc. |
| Fires per | every read (`get`) and every write (`set`) | every transaction (start/end/error) |
| Can short-circuit? | no | yes — by throwing |
| Sees other states? | no | yes — `context.vault` |
| Examples | `EmailNormalizer`, `Encryption`, `JsonCodec` | `Logger`, `Validator`, `MetricsTimer` |

### 8.4 `state` initial value vs `state` with transformer

| | `state { initial }` | `state(t) { initial }` |
|---|---|---|
| Initial stored value | `initial` | `initial` (transformer is NOT applied at construction) |
| First `value` read | `initial` | `t.get(initial)` if `t.shouldTransform(initial)` else `initial` |
| First `mutate v` | stores `v` | stores `t.set(v)` if `t.shouldTransform(v)` else `v` |
| Rollback target | the last committed raw value | the last committed raw value (no `t.set` re-applied) |

The asymmetry of "no transformer at construction" is intentional. It lets
the initial value be the source of truth, and gives `shouldTransform`
control over edge values like a sentinel "not loaded" instance.

---

## 9. Techniques Cookbook

### 9.1 Logging every transaction

```kotlin
class Logger<V : Vault<V>>(private val tag: String) : Middleware<V>() {
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
vault.middlewares(Logger("Counter"))
```

### 9.2 Validation that aborts the transaction

```kotlin
class NonNegativeBalance : Middleware<AccountVault>() {
    override fun onTransactionCompleted(c: MiddlewareContext<AccountVault>) {
        // Pending writes already buffered; check against current view.
        if (c.vault.balance.value < 0)
            error("Balance cannot go negative")
    }
}
```

Throwing in `onTransactionCompleted` propagates out of `runMiddlewareChain`,
the action's catch sees it, rollback drops pending writes — the negative
balance never becomes visible.

### 9.3 Optimistic UI with manual rollback

```kotlin
class Composer : Vault<Composer>() {
    val text by state { "" }
    val sending by state { false }
    val lastError by state<Throwable> { NoError }
}

suspend fun send(vault: Composer, api: Api) {
    val draft = vault { text.value }
    vault action {
        sending mutate true
        text mutate ""           // optimistic clear
    }
    runCatching { api.send(draft) }
        .onSuccess { vault action { sending mutate false } }
        .onFailure { e ->
            vault action {
                sending mutate false
                text mutate draft     // restore
                lastError mutate e
            }
        }
}
```

Vault's automatic rollback handles failures inside one action; for
multi-step async work, you orchestrate the compensating action yourself.

### 9.4 Persistence via Bridge

```kotlin
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

vault { items bridge JsonFileBridge(Path("todos.json"), TodoCodec) }
```

### 9.5 Compose StateFlow adapter

```kotlin
class StateFlowBridge<T : Any>(initial: T) : Bridge<T> {
    private val flow = MutableStateFlow(initial)
    val state: StateFlow<T> = flow.asStateFlow()
    private val observers = mutableListOf<(T) -> Unit>()
    override fun observe(observer: (T) -> Unit): Disposable {
        observers.add(observer); observer(flow.value)
        return Disposable { observers.remove(observer) }
    }
    override fun publish(value: T): Boolean {
        flow.value = value; return true
    }
}

@Composable
fun MyScreen(vault: TodoVault) {
    val bridge = remember { StateFlowBridge(vault.items.value) }
    DisposableEffect(vault) {
        vault { items bridge bridge }
        onDispose { /* leave bridge attached or clear via state.bridge = null */ }
    }
    val items by bridge.state.collectAsState()
    LazyColumn { items(items) { Text(it) } }
}
```

### 9.6 Computed / derived state

The library has no native `derive(other) { … }` operator. The two idioms:

**Read-only derived (compute on demand):** define a vault function.

```kotlin
class CartVault : Vault<CartVault>() {
    val items by state { emptyList<Line>() }
    fun total(): Money = items.value.sumOf { it.price * it.qty }
}
```

**Stored derived (compute in the action that updates the source):**

```kotlin
class CartVault : Vault<CartVault>() {
    val items by state { emptyList<Line>() }
    val total by state { Money.Zero }
    fun add(line: Line) = action {
        items mutate items.value + line
        total mutate items.value.sumOf { it.price * it.qty }
    }
}
```

**Auto-recomputed derived:** wire it through `effect` (extra commit per source change):

```kotlin
init {
    items effect {
        action { total mutate this.sumOf { it.price * it.qty } }
    }
}
```

The third pattern double-commits and is rarely worth the indirection;
prefer pattern two — keeping derived consistency inside the original
action.

### 9.7 Read-your-own-writes inside an action

```kotlin
vault action {
    count mutate 5
    val seen = count.value          // == 5 on this thread, even pre-commit
    count mutate seen + 10          // == 15 stored
}
```

This is the only place reads see uncommitted values, and only on the
thread executing the action. From any other thread, `count.value` returns
the last committed value until this action commits.

### 9.8 Savepoint semantics

```kotlin
vault action {                      // T_outer
    a mutate 1
    val inner = vault action {      // T_inner (parent = T_outer)
        b mutate 2
    }
    // inner is TransactionResult.Success — pendingWrites {b->2} merged into T_outer
    if (riskCheck() == BAD) error("abort")
    c mutate 3
}
// On outer commit: a=1, b=2, c=3 — observers fire once each.
// On error("abort"): nothing committed; observers see no change.
```

Inner errors propagate to the outer's catch and roll the outer back. To
*recover* from an inner error and continue the outer, wrap the inner in
runCatching:

```kotlin
vault action {
    a mutate 1
    val inner = runCatching { vault action { b mutate 2; error("flake") } }
    // inner.exception is set; b's pending was discarded by inner's rollback.
    // Outer continues with a's pending intact.
    c mutate 3
}
// Final: a=1, c=3, b=initial.
```

### 9.9 Idempotent rollback for cancellation

A transaction handed to you (e.g. via `TransactionResult.Success.transaction`)
can be `rollback()`'d after the fact — it's a no-op if already finalized.

```kotlin
val res = vault action { x mutate 1 }
// later, somewhere else:
if (res is TransactionResult.Success) res.transaction.rollback()
// no-op: already Committed.
```

Don't *rely* on this for "undo" — the post-hoc rollback does not restore
the pre-image. Use a separate undo stack (e.g. snapshot `value` before,
mutate to it on undo).

### 9.10 Disposing many subscriptions at once

```kotlin
class CompositeDisposable : Disposable {
    private val list = mutableListOf<Disposable>()
    operator fun plusAssign(d: Disposable) { list.add(d) }
    override fun dispose() { list.forEach { it.dispose() }; list.clear() }
}

val cd = CompositeDisposable().apply {
    this += vault { count effect { … } }
    this += vault { label effect { … } }
}
cd.dispose()
```

---

## 10. Concurrency Model

### 10.1 What's serialized

| Operation | Lock | Reentrant? | Notes |
|---|---|---|---|
| `vault action { … }` | `transactionLock` | yes | The only entry point; nested actions reuse the same lock |
| `vault.middlewares(...)` | `middlewareLock` | yes | Reading the chain is also under this lock |
| `vault.state(…)` (delegate first read) | `propertiesLock` | yes | After first read, no lock for delegate |
| `MutableState.value` read | `stateLock` (per state) | yes | Plus optional pending-write peek if owner thread |
| `MutableState.observe / dispose` | `observersLock` (per state) | yes | Snapshot then fire — observer callback NOT under lock |
| `MutableState.bridge =` | `bridgeLock` (per state) | yes | Calls `observe` on the bridge inside |

### 10.2 Lock ordering

The library acquires locks in this consistent global order; respect it
when extending:

```
transactionLock  →  middlewareLock  →  propertiesLock  →  bridgeLock  →  stateLock  →  observersLock
```

Of these, only adjacent acquisitions actually nest in practice; the
critical AB-BA candidate fixed in earlier work was `stateLock ↔
observersLock`, which `applyCommitted` now resolves by snapshotting under
`stateLock`, releasing, then notifying under `observersLock`.

### 10.3 Thread confinement of a transaction

A `Transaction` records its `ownerThreadId` at construction. `mutate`
checks `txn.ownerThreadId == currentThreadId()` before buffering. A
mutate from a non-owner thread skips the pending path entirely and
synthesizes its own one-shot transaction (which serializes through
`transactionLock`).

This means you can have `vault action { … }` running on T1 while T2
calls `vault.count.value` — T2 reads a consistent committed snapshot,
never T1's pending writes.

### 10.4 What is NOT thread-safe

- Holding a reference to a `Transaction` and calling `commit` / `rollback`
  on it from a thread that does not own it. The library does not stop
  you, but observers may fire on whatever thread you call from.
- Disposing an `effect` `Disposable` while the same observer is mid-fire
  on another thread. Dispose is idempotent and safe to call concurrently;
  the in-flight callback finishes uninterrupted.
- A `Bridge<T>.observe` callback that calls back into the vault on a
  different thread *during* `applyFromBridge`. Lock-order analysis: the
  callback runs while no vault locks are held (the bridge owns its own
  threading), so a re-entrant `mutate` from the callback acquires
  `transactionLock` cleanly.

---

## 11. Testing Patterns

### 11.1 Asserting commit observability

```kotlin
@Test fun mutationFiresObserverOnce() {
    val v = CounterVault()
    val seen = mutableListOf<Int>()
    val sub = v { count effect { seen.add(this) } }
    seen.clear()
    v action { count mutate 5 }
    assertEquals(listOf(5), seen)
    sub.dispose()
}
```

### 11.2 Asserting rollback invisibility

```kotlin
@Test fun rolledBackMutationsAreInvisible() {
    val v = CounterVault()
    val seen = mutableListOf<Int>()
    val sub = v { count effect { seen.add(this) } }
    seen.clear()
    v action {
        count mutate 99
        error("rollback")
    }
    assertEquals(emptyList<Int>(), seen)
    assertEquals(0, v.count.value)
    sub.dispose()
}
```

### 11.3 Asserting middleware fires for outside-action mutate

```kotlin
@Test fun bareMutateFiresMiddleware() {
    val v = CounterVault()
    var calls = 0
    v.middlewares(object : Middleware<CounterVault>() {
        override fun onTransactionStarted(c: MiddlewareContext<CounterVault>) { calls++ }
    })
    v { count mutate 42 }
    assertEquals(1, calls)
}
```

### 11.4 Asserting cross-vault rejection

```kotlin
@Test fun foreignStateRejected() {
    val a = CounterVault()
    val b = CounterVault()
    val foreign = a.count
    val r = b action { foreign mutate 99 }
    assertIs<TransactionResult.Error>(r)
    assertEquals(0, a.count.value)
}
```

### 11.5 Concurrency stress

```kotlin
@Test fun noLostUpdatesUnder8Threads() = runBlocking {
    val v = CounterVault()
    val workers = 8; val perWorker = 200
    coroutineScope {
        repeat(workers) {
            launch(Dispatchers.Default) {
                repeat(perWorker) {
                    v action { count mutate count.value + 1 }
                }
            }
        }
    }
    assertEquals(workers * perWorker, v.count.value)
}
```

---

## 12. Common Pitfalls

| Symptom | Cause | Fix |
|---|---|---|
| Observer fires twice for one logical event | Subscribed via `effect` AND wired through a bridge | Pick one |
| Test sees `expected=N, actual=N+1` for first event | Forgot the initial-fire on subscribe | `seen.clear()` before the assertion |
| `IllegalStateException: Failed to record state change` | Mutating in a foreign-vault state | The state belongs to a different vault — pass the right state |
| `TransactionException: Cannot mutate state on a Committed transaction` | Holding a `Transaction` after `action` returned and trying to mutate via it directly | `Transaction` is owned by `action`; just call `vault action { … }` again |
| Bridge keeps publishing forever in a loop | Bridge's `publish` calls into a system that re-publishes back and the bridge does not dedupe | Have the bridge dedupe (compare to last-published) before notifying observers |
| Effect callbacks leak after a Composable disappears | `Disposable` not captured | Use `DisposableEffect` and call `.dispose()` in `onDispose` |
| Nested action's commit "doesn't seem to do anything" | Inner committed — but it's a savepoint; outer still owns the pending writes | This is correct. Inner's commit merged into outer; outer's commit/rollback is what the world sees |

---

## 13. API Reference

### `Vault<Self>`

| Member | Signature | Description |
|---|---|---|
| `state` | `fun <T> state(transformer: Transformer<T>? = null, distinct: Boolean = false, init: Initializer<T>): StateDelegate<T>` | Declares a state property; `distinct=true` opts into same-value commit dedup |
| `action` | `infix fun <R> action(body: Self.() -> R): TransactionResult<R>` | Runs body in a transaction; body's return value carried in `Success<R>` |
| `invoke` | `operator fun <R> invoke(block: Self.() -> R): R` | Plain context block |
| `middlewares` | `fun middlewares(vararg middleware: Middleware<Self>)` | Registers middleware (LAST argument is outermost) |
| `clearMiddleware` | `fun clearMiddleware()` | Removes all registered middleware |
| `activeTransaction` | `val activeTransaction: Transaction?` | Volatile read of in-flight transaction |
| `uncaughtObserverHandler` | `var uncaughtObserverHandler: ((Throwable) -> Unit)?` | Optional handler for commit-fire observer exceptions (default null = silent swallow) |
| `lockOrderKey` | `val lockOrderKey: Long` *(opt-in)* | Process-monotonic ordering key used by `atomic(...)` for deadlock-safe lock acquisition |
| `properties` | `val properties: Map<String, State<*>>` | Snapshot of registered states |
| `getState` / `hasState` / `removeState` / `clearStates` | … | Reflection over the property map; `removeState`/`clearStates` dispose observers + bridge silently |

### Extensions on `State<T>` (member-extensions of `Vault<Self>`)

| Member | Signature | Description |
|---|---|---|
| `mutate` | `infix fun State<T>.mutate(that: T)` | Buffers post-`set` value into active txn or wraps in implicit action |
| `update` | `infix fun State<T>.update(block: (T) -> T)` | Read-modify-write; equivalent to `mutate(block(value))` |
| `effect` | `infix fun State<T>.effect(effect: T.() -> Unit): Disposable` | Subscribes to commits |
| `bridge` | `infix fun State<T>.bridge(bridge: Bridge<T>?)` | Connects a two-way external sync; `null` detaches |
| `observeFrom` | `infix fun State<T>.observeFrom(o: Observable<T>): Disposable` | Inbound-only push from an external `Observable`, no outbound publish |

### `Transaction`

| Member | Signature | Description |
|---|---|---|
| `id` | `val id: String` | The action class simple name, or a UUID |
| `status` | `val status: TransactionStatus` | `Active` / `Committed` / `RolledBack` / `Failed` |
| `endTime` | `val endTime: Long?` | Epoch milliseconds at which status left `Active` |
| `parent` | `val parent: Transaction?` *(opt-in)* | Outer transaction for savepoint chains |
| `modifiedStates` | `val modifiedStates: Set<State<*>>` | Read-only view of pending-write keys (owner-thread only) |
| `commit` | `fun commit()` | Idempotent. No-op if not Active |
| `rollback` | `fun rollback()` | Idempotent. No-op if not Active |

You normally do not call `commit` / `rollback` yourself — `action`
manages them.

### `MutableState<T>`

| Member | Signature | Description |
|---|---|---|
| `value` | `override val value: T` | Post-`get` view; read-your-own-writes for owner thread |
| `observe` | `fun observe(observer: (T) -> Unit): Disposable` | Subscribe with initial fire |
| `bridge` | `var bridge: Bridge<T>?` | Get/set the bridge; setting installs an observer on it |

`MutableState` is the concrete state class. You will rarely instantiate
it directly — `state { … }` does it for you.

### `Bridge<T>` / `Observable<T>` / `Publisher<T>`

```kotlin
fun interface Observable<T : Any> { fun observe(observer: (T) -> Unit): Disposable }
fun interface Publisher<T : Any> { fun publish(value: T): Boolean }
interface Bridge<T : Any> : Observable<T>, Publisher<T>
```

### `Transformer<T>`

```kotlin
interface Transformer<T : Any> {
    fun set(value: T): T
    fun get(value: T): T
    fun shouldTransform(value: T): Boolean = true
}
```

`set` is invoked on every write before storing. `get` is invoked on every
read. `shouldTransform` lets you skip both for sentinel values (e.g. an
"empty" instance that should round-trip unchanged).

### `Middleware<V>`

```kotlin
open class Middleware<V : Vault<V>> {
    data class MiddlewareContext<V>(
        val vault: V,
        val transaction: Transaction,
        val metadata: MutableMap<String, Any> = mutableMapOf(),
    )
    protected open fun onTransactionStarted(context: MiddlewareContext<V>) {}
    protected open fun onTransactionCompleted(context: MiddlewareContext<V>) {}
    protected open fun onTransactionError(context: MiddlewareContext<V>, error: Throwable) {}
}
```

### Sealed result and status types

```kotlin
sealed interface TransactionResult<out R> {
    data class Success<R>(val transaction: Transaction, val value: R) : TransactionResult<R>
    data class Error(val exception: Throwable, val transaction: Transaction) : TransactionResult<Nothing>
}

enum class TransactionStatus { Active, Committed, RolledBack, Failed }
```

---

## 14. The 1.1 surface

Everything below ships in 1.1 on top of the 1.0 baseline above. Each
capability is independently usable; pick the ones you need.

### 14.1 `Vault.snapshot()` / `Vault.restore(snapshot)`

```kotlin
class VaultSnapshot internal constructor(internal val rawValues: Map<String, Any>) {
    val stateNames: Set<String>
    val size: Int
}

fun <V : Vault<V>> V.snapshot(): VaultSnapshot
fun <V : Vault<V>> V.restore(snapshot: VaultSnapshot): TransactionResult<Unit>
```

`snapshot` captures the raw stored value of every state that has been
delegate-initialized at least once. `restore` writes them back inside a
single top-level `action`, bypassing `transformer.set` so asymmetric
transformers (encryption, JSON codecs) round-trip losslessly.

```kotlin
val snap = vault.snapshot()
vault action { count mutate 9999; label mutate "wrong" }
vault.restore(snap)               // count + label back to snapshot values
```

Restore-time bridge publish: yes. Detach bridges first if the snapshot
shouldn't echo back to your persistence layer. Restore of an unknown state
name throws (caught by the wrapping action → `TransactionResult.Error`).

### 14.2 `Vault.computed { }` / `Vault.derived(sources) { }`

```kotlin
fun <V : Vault<V>, T : Any> V.computed(compute: V.() -> T): State<T>
fun <V : Vault<V>, T : Any> V.derived(
    vararg sources: State<*>,
    compute: V.() -> T,
): Pair<State<T>, Disposable>
```

- **`computed`**: read-time, no observation. Cheap. The returned `State<T>`
  has no observer mechanism — every read of `value` re-runs `compute`.
- **`derived`**: push-recomputed. Subscribes to each source via `effect`;
  on each source commit, runs `compute()` inside a fresh top-level action
  on the same vault and stages the result in a backing `MutableState`.
  The returned `State<T>` is a real observable state — use `effect` to
  subscribe.

The recompute is deferred via `Vault.postCommit` (an internal queue) so
it doesn't re-enter the parent's `pendingWrites` map mid-iteration.
Disposing the `Disposable` stops recomputation.

### 14.3 `atomic(vararg vaults) { body }`

```kotlin
fun <R> atomic(vararg vaults: Vault<*>, body: () -> R): TransactionResult<R>
```

Brackets multiple vaults' transactions so they commit-or-rollback together.
Inside `body`, `v1.action { … }` and `v2.action { … }` join the atomic
frame as savepoints of each vault's root. On body throw, every vault is
rolled back; on body return, every vault commits in lock order with
sequential observer fanout per-vault.

```kotlin
val r = atomic(accountA, accountB) {
    accountA.action { balance update { it - amount } }
    accountB.action { balance update { it + amount } }
}
```

Vaults are sorted by `Vault.lockOrderKey` (process-monotonic, set at
construction) before lock acquisition — deadlock-safe across any
combination. Nested `atomic` is supported via reentrant locks.

### 14.4 `EncryptingTransformer` + `Cipher` (`com.vynatix.vault.crypto`)

```kotlin
interface Cipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

class EncryptingTransformer(cipher: Cipher) : Transformer<String>
class XorCipher(seed: ByteArray) : Cipher       // educational only
```

Use `state(EncryptingTransformer(cipher)) { initial }` to make a state
encrypt-on-write, decrypt-on-read. Stored `currentValue` is ciphertext;
`KvBridge`-persisted bytes are ciphertext; reads through `state.value`
are plaintext. Asymmetric-rollback safe (the library records raw
ciphertext and writes raw on restore — never re-runs `transformer.set`).

`XorCipher` is **NOT production-grade** — it's a KMP-pure stand-in.
Production users implement `Cipher` over `javax.crypto` (JVM) or
CryptoKit (iOS) — typically AES-GCM with a per-state IV embedded in
the encoded output.

### 14.5 `FileSystemKvStore` (`com.vynatix.vault.bridge`)

```kotlin
expect class FileSystemKvStore(rootPath: String) : KvStore
```

Backend for `KvBridge` that persists each key as a single file under
`rootPath`. Atomic writes via tempfile + rename on JVM (`Files.move(ATOMIC_MOVE)`)
and iOS (`NSData.writeToURL(atomically=true)`).

```kotlin
val kv = FileSystemKvStore(rootPath = "$home/.myapp")
vault { balance bridge KvBridge(kv, "balance:1", LongCodec) }
// balance auto-persists on every commit; new vaults attaching the same
// KvBridge hydrate from disk via load-on-attach.
```

Key encoding: URL-percent-encoded so any String is a safe filename.

### 14.6 Standard middleware (`com.vynatix.vault.middleware`)

```kotlin
class LoggingMiddleware<V>(tag: String, log: (String) -> Unit = ::println)
class TimingMiddleware<V>(onResult: (id: String, status: TransactionStatus, elapsedMs: Long) -> Unit)
class ValidationMiddleware<V>(check: V.() -> Unit)
```

Drop-in. Order in `vault.middlewares(...)` matters — the LAST argument
is the outermost middleware (its `onTransactionStarted` runs first; its
`onTransactionError` runs last). Place logging/audit middleware LAST so
it sees errors thrown by validation middleware placed earlier.

### 14.7 `KvBridge` + `Codec` + `KvStore` (`com.vynatix.vault.bridge`)

```kotlin
interface KvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun snapshot(): Map<String, String>
}

interface Codec<T : Any> {
    fun encode(value: T): String
    fun decode(string: String): T
}
object StringCodec : Codec<String>
object LongCodec   : Codec<Long>
object IntCodec    : Codec<Int>
object BooleanCodec: Codec<Boolean>

class InMemoryKvStore : KvStore                     // tests + dev
class KvBridge<T : Any>(kv: KvStore, key: String, codec: Codec<T>) : Bridge<T>
```

Generic save-on-commit + load-on-attach. Combine with any `KvStore`
implementation (in-memory, file system, MultiplatformSettings, …).

### 14.8 `:vault-coroutines`

```kotlin
fun <T : Any> State<T>.asFlow(): Flow<T>
fun <T : Any> State<T>.asStateFlow(scope: CoroutineScope, started: SharingStarted = SharingStarted.WhileSubscribed()): StateFlow<T>
fun <T : Any> State<T>.asEagerStateFlow(): EagerStateFlow<T>

suspend fun <T : Any> State<T>.first(predicate: (T) -> Boolean): T
suspend fun <T : Any> State<T>.awaitValue(target: T): T

suspend fun <V : Vault<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R>
```

`suspendAction` allows the body to suspend (`delay`, `await`, `withContext`).
Mutually exclusive with blocking `Vault.action` on the same vault via an
internal coroutine `Mutex` installed lazily. Cancellation of the body
rolls back the transaction; commit phase wraps in `NonCancellable` so
observer/bridge fanout completes cleanly even if the surrounding scope
cancels mid-commit.

Limitations (1.1): no middleware support (`runMiddlewareChain` is non-
suspending); body should be single-threaded — spawned threads' `mutate`
calls fall outside the recognized owner.

### 14.9 `:vault-compose`

```kotlin
@Composable
fun <V : Vault<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(make: () -> Disposable): Disposable
```

Bridges vault state into Compose's snapshot system as a
`androidx.compose.runtime.State<T>`, triggering recomposition on every
successful commit. Backed by `produceState`; subscription's lifecycle is
tied to the surrounding Composable.

### 14.10 The cookbook: 1.1 idioms

**Encrypted-at-rest credential**:
```kotlin
class CredsVault : Vault<CredsVault>() {
    val token by state(EncryptingTransformer(SystemAesCipher())) { "" }
}
val kv = FileSystemKvStore("$home/.app/creds")
vault { token bridge KvBridge(kv, "session", StringCodec) }
// token is plaintext on read; persisted file contains ciphertext.
```

**Cross-vault transfer with one-line atomicity**:
```kotlin
fun AccountVault.transferTo(other: AccountVault, cents: Long) =
    atomic(this, other) {
        action { balance update { it - cents } }
        other.action { balance update { it + cents } }
    }
```

**Auto-recomputed running total**:
```kotlin
val (total, dispose) = vault.derived(vault.items) { items.value.sumOf { it.amount } }
val sub = vault { total effect { uiTotal.value = this } }
// later: sub.dispose() ; dispose.dispose()
```

**Snapshot-and-restore for undo**:
```kotlin
val undoStack = ArrayDeque<VaultSnapshot>()
fun saveCheckpoint() { undoStack.addLast(vault.snapshot()) }
fun undo() = undoStack.removeLastOrNull()?.let { vault.restore(it) }
```

**Async transactional fetch**:
```kotlin
val r = vault.suspendAction {
    status mutate Status.Loading
    val data = api.fetch()                  // suspending I/O
    items mutate (items.value + data)
    status mutate Status.Loaded
    data
}
```

### 14.11 Validation 0.3.0 — three modules at the boundary

`:validation` is a standalone KMP refinement-types library; `:vault-validation`
is a thin Vault adapter on top of it; `:validation-coroutines` adds suspend
support. The premise: every primitive (`String`, `Long`, …) flowing into your
domain is validated and wrapped in a typed `Boxed<P>` exactly once, at the
boundary. Inside the domain, you pass wrappers, never raw primitives — the
canonical fix for the **primitive obsession** code smell.

#### Module structure

| Artifact | Role |
|---|---|
| `com.vynatix:validation` | Core lib. No Vault dep. `Boxed` / `Rule` / `Validator` / composite DSL / 14 prebuilt rules / multi-error `ValidationResult`. |
| `com.vynatix:validation-coroutines` | Suspend extension. `SuspendRule`, `SuspendValidator`, `suspendValidator { }` DSL. |
| `com.vynatix:vault-validation` | Vault adapter. `ValidatingTransformer`, `Vault.boxed { }` factory, `BoxedCodec`. |

#### Core surface (`com.vynatix.validation`)

```kotlin
interface       Boxed<P : Any>                 { val value: P }

abstract class  Rule<P>(val code: String, val messageTemplate: String) {
    abstract fun validate(value: P): Boolean
    open fun message(value: P): String       = messageTemplate
    open fun args(value: P): Map<String, Any?> = emptyMap()
}

data class      Violation(message, path, code, rule, args)

sealed interface ValidationResult<out OUT> {
    data class Success<OUT>(val value: OUT)   : ValidationResult<OUT>
    data class Failure(val violations: NonEmptyList<Violation>) : ValidationResult<Nothing>
    fun getOrThrow(): OUT     // throws ValidationException on Failure
    fun getOrNull(): OUT?
}

enum class      SpecMode { ALL, ANY }
data class      Spec<P : Any, O : Boxed<P>>(rules, mode, factory)

interface       Validator<IN, OUT> {
    fun validate(value: IN): ValidationResult<OUT>
    infix fun of(value: IN): OUT              // throws ValidationException on Failure
    fun ofOrNull(value: IN): OUT?
}

abstract class  BoxedValidator<P : Any, O : Boxed<P>> : Validator<P, O>
```

#### Defining a leaf validator (class-based)

```kotlin
data class Email(override val value: String) : Boxed<String>

object EmailValidator : BoxedValidator<String, Email>() {
    private val nonEmpty    = NonBlankRule()
    private val sensibleLen = LengthInRule(3..254)
    private val containsAt  = MatchesRule(Regex(".+@.+"))

    override val specs = listOf(
        Spec(listOf(nonEmpty, sensibleLen, containsAt), SpecMode.ALL, ::Email),
    )
}

val email = EmailValidator of "alice@example.com"       // typed Email
EmailValidator of "not-an-email"                        // throws ValidationException

val r = EmailValidator.validate(" ")                    // returns ValidationResult.Failure
when (r) {
    is ValidationResult.Success -> store(r.value)
    is ValidationResult.Failure -> r.violations.forEach { v ->
        log("${v.code}: ${v.message} args=${v.args}")
    }
}
```

The 14 prebuilt rules (in `com.vynatix.validation.rules`) cover the basics —
`NonEmptyRule`, `NonBlankRule`, `LengthInRule(IntRange)`, `MinLengthRule(n)`,
`MaxLengthRule(n)`, `MatchesRule(Regex)`, `StartsWithRule(s)`, `EndsWithRule(s)`,
`GtRule(n)`, `GteRule(n)`, `LtRule(n)`, `LteRule(n)`, `InRangeRule(range)`,
`NonEmptyCollectionRule<T>`, `SizeInRule<T>(IntRange)`. Format-specific regexes
(email, URL, UUID, IBAN) are intentionally **not** shipped — bring your own.

#### Multi-spec leaves (one validator, multiple shapes)

```kotlin
object NumberValidator : BoxedValidator<String, Num>() {
    override val specs = listOf(
        Spec(listOf(IntegerRule()),     SpecMode.ALL) { Num.Int(it.toInt()) },
        Spec(listOf(FloatRule()),       SpecMode.ALL) { Num.Float(it.toDouble()) },
    )
}
NumberValidator of "42"     // Num.Int(42)
NumberValidator of "3.14"   // Num.Float(3.14)
```

First matching spec wins. If no spec matches, every spec's failing rules
contribute violations to the returned Failure.

#### Composite validators (DSL block)

```kotlin
val UserValidator: Validator<User, User> = validator<User> {
    field("email", { it.email }, EmailValidator)
    field("age",   { it.age },   AgeValidator)
    field("address", { it.address }, AddressValidator)         // sub-composite
    if (admin) field("salaryUsd", { it.salaryUsd }, MoneyValidator)
}
```

The DSL produces a `Validator<T, T>`. Sub-validators may be either leaves
(producing `Boxed<P>`) or other composites (producing the same struct type).
`Violation.path` threads automatically — a failure inside `User.address.zip`
arrives as `["address", "zip"]`.

Composites accumulate violations across **all** fields (not just the first
failure), so HTTP/form layers can surface every problem at once.

#### Collection fields — `each` and `forKey`

```kotlin
val UserValidator = validator<User> {
    field("email", { it.email }, EmailValidator)
    each("addresses", { it.addresses }, AddressValidator)             // List<Address>
    forKey("tags", { it.tags }, "primary", TagValidator)              // Map<String, String>
}
```

Path notation distinguishes index segments (`"[0]"`, `"[2]"`) from named
field segments. A failure inside `user.addresses[2].zip` arrives as
`path = ["addresses", "[2]", "zip"]`.

#### Format regex rules

`com.vynatix.validation.rules` ships nine practical-but-not-RFC-strict
format checks: `EmailRule`, `UrlRule`, `UuidRule`, `Ipv4Rule`, `Ipv6Rule`,
`E164PhoneRule`, `Iso8601DateRule`, `Iso8601DateTimeRule`, `IbanRule`. None
of these claim full RFC compliance — they target the 95% case used by HTML5
forms. Adopters needing stricter forms compose their own
`MatchesRule(regex)` or subclass `Rule<String>`.

#### Internationalization — `MessageResolver`

```kotlin
class AndroidMessageResolver(val resources: Resources) : MessageResolver {
    override fun resolve(violation: Violation, locale: String?): String {
        val resId = when (violation.code) {
            "string.minLength" -> R.string.err_min_length
            "string.nonBlank"  -> R.string.err_non_blank
            else               -> return violation.message
        }
        return resources.getString(resId, *violation.args.values.toTypedArray())
    }
}

val resolved: List<String> = result.resolveAll(AndroidMessageResolver(resources))
```

The library ships `EnglishMessageResolver` (the default — returns
`Violation.message` verbatim) and the `MessageResolver` interface for
custom strategies. `code` + `args` are the contract surface.

#### Schema export / introspection

```kotlin
when (val d = UserValidator.describe()) {
    is ValidatorDescription.LeafDescription -> /* leaf — list specs/rules */
    is ValidatorDescription.CompositeDescription -> d.fields.forEach { … }
    is ValidatorDescription.OpaqueDescription -> /* fallback for hand-rolled */
}
```

Useful for OpenAPI / JSON-Schema generation, form-builder UIs, or doc
generation. Ships introspection only — actual schema-format export is
adopter-side.

#### Vault integration (`vault-validation`)

Two state factories:

```kotlin
class UserVault : Vault<UserVault>() {
    val email       by boxed(EmailValidator) { "init@example.com" }       // State<Email>
    val displayName by boxedHandle(NameValidator) { "init" }              // BoxedHandle<String, Name>
}

vault action {
    email mutate (EmailValidator of "alice@example.com")     // explicit validator at the call site
    displayName assign "Alice"                                // assign infix via BoxedHandle
    email mutate Email("not-an-email")                        // rolls back via transformer
}

// KvBridge persistence
vault {
    email bridge KvBridge(
        kv     = kvStore,
        key    = "user.email",
        codec  = BoxedCodec(StringCodec, EmailValidator),
    )
}
```

- **`boxed(validator) { initial }`** — sugar for
  `state(transformer = ValidatingTransformer(v)) { v of initial() }`.
  Property type is `State<O>`; mutate with the explicit validator.
- **`boxedHandle(validator) { initial }`** — same wiring, but the property
  is a `BoxedHandle<P, O>` bundling state + validator. Enables the
  `assign` infix (powered by Kotlin context parameters) for one-line
  civilize-and-mutate at the call site.
- **`ValidatingTransformer`** — re-validates on every write, so
  constructor bypass (`data class copy`) is rejected.
- **`BoxedCodec`** — round-trips `Boxed<P>` through any `Codec<P>`.

#### Suspend validation (`validation-coroutines`)

```kotlin
class UniqueUsernameRule(private val taken: Set<String>) : SuspendRule<String>(
    code = "username.unique",
    messageTemplate = "username already taken",
) {
    override suspend fun validate(value: String): Boolean {
        delay(20)                            // simulated remote check
        return value !in taken
    }
}

class UsernameValidator(taken: Set<String>) : SuspendBoxedValidator<String, Username>() {
    override val specs = listOf(
        SuspendSpec(listOf(UniqueUsernameRule(taken)), SpecMode.ALL) { Username(it) },
    )
}

val v = suspendValidator<NewUser> {
    field("username",    { it.username },    UsernameValidator)     // suspend leaf
    field("displayName", { it.displayName }, DisplayNameValidator)  // sync leaf
}

val r: ValidationResult<NewUser> = v.validate(NewUser("alice", "Alice"))
```

The composite DSL accepts either sync or suspend sub-validators via
overloaded `field` factories.

#### Vault adapter for suspend validation (`vault-validation-coroutines`)

```kotlin
suspend fun adoptUsername(name: String): TransactionResult<Unit> =
    vault.suspendValidateAndMutate(vault.username, UsernameValidator, name)
```

Runs the suspend validator (which may do I/O), then mutates the Vault state
inside a `suspendAction { }`. Atomic: validation failure rolls back the
entire transaction.

#### Transformer composition — `Transformer.then`

```kotlin
import com.vynatix.vault.then

val pipeline = ValidatingTransformer(EmailValidator).then(EncryptingTransformer(cipher))

class UserVault : Vault<UserVault>() {
    val email by state(transformer = pipeline) { /* … */ }
}
```

`then` chains transformers: `set` runs `this.set` then `other.set`; `get`
runs `other.get` then `this.get` (reverse order, so round-trip preserved).
Ships in `:vault` core.

#### Migrating from Konform

See [`validation/KONFORM-MIGRATION.md`](../validation/KONFORM-MIGRATION.md)
for a 1:1 mapping from Konform's `Validation<T>` API to this library's
surface. No runtime Konform dep.

---

## Appendix A — One-page cheatsheet

```kotlin
class V : Vault<V>() {
    val x by state { 0 }
    val s by state(MyTransformer()) { "" }
    val token by state(EncryptingTransformer(cipher)) { "" }   // 1.1
    val items by state(distinct = true) { emptyList<Item>() }  // 1.1 dedup
}
val v = V()

// Subscribe.
val sub = v { x effect { println("x=$this") } }       // initial: x=0

// Atomic single-vault mutation; body return flows into Success.
val r = v action { x update { it + 1 }; "$x.value done" }  // 1.1: update + <R>

// Failed atomic mutation.
v action { x mutate 99; error("nope") }                // (no fire)

// Bare mutation — same outcome as a one-mutate action.
v { x mutate 2 }                                       // → x=2

// Cross-cutting concern. LAST argument is outermost middleware.
v.middlewares(ValidationMiddleware { … }, LoggingMiddleware("v"))

// External sync (two-way bridge); detach with bridge null.
v { s bridge KvBridge(kv, "s", StringCodec) }
v { s bridge null }

// Inbound-only push (1.1).
val sub2 = v { s observeFrom externalObservable }

// Cross-vault atomic (1.1).
atomic(accountA, accountB) {
    accountA.action { balance update { it - cents } }
    accountB.action { balance update { it + cents } }
}

// Snapshot / restore (1.1).
val snap = v.snapshot()
v.restore(snap)

// Push-recomputed derived state (1.1).
val (total, d) = v.derived(v.items) { items.value.sumOf { it.amount } }

// Suspending body (1.1, vault-coroutines).
val r2 = v.suspendAction { status mutate Loading; val data = api.fetch(); status mutate Loaded; data }

// Cleanup.
sub.dispose(); sub2.dispose(); d.dispose()
```
