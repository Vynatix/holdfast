# The Holdfast Library — Complete Guide

`com.vynatix.holdfast` is a Kotlin Multiplatform state-management library built around
**transactional state**: every mutation lives inside a transaction; observers see only
committed values; failed transactions never leak. The core depends only on
`kotlinx-coroutines-core` (exposed as `api` — `CoroutineScope` and `SharedFlow`
appear in the public surface) and `kotlinx-atomicfu`; there are no Compose,
Android, or iOS framework dependencies.

This guide covers the mental model, the seven primitives, the full transaction
workflow, decision charts for picking the right tool, feature differentiation tables,
a techniques cookbook, the concurrency model, and a terse API reference.

---

## Table of Contents

1. [Mental Model](#1-mental-model)
2. [The Shape of a Store](#2-the-shape-of-a-store)
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

A `Store` is a state container whose unit of consistency is a **transaction**.
You read state through `state` properties; you mutate inside `action { … }`;
you subscribe with `effect`. Every transaction is **all-or-nothing** — if its
body throws, no observer was ever told about the intermediate writes, no
external bridge was published to, and the stored value is byte-for-byte the
same as before the action ran.

### Why it exists

| Problem | Without Holdfast | With Holdfast |
|---|---|---|
| Multi-state writes can leave the system half-updated | You add ad-hoc try/catch and revert by hand | `action { … }` is atomic; throw rolls back everything |
| Observers fire mid-write and see "impossible" intermediate states | You debounce or guard at every callsite | Observers only fire on commit |
| Asymmetric serialization (`toJson`/`fromJson`) drifts on rollback | You hand-roll storage of pre-images | `transformer` keeps `set` and `get` separate; rollback never touches `set` |
| Cross-holdfast writes silently corrupt the wrong store | Casts succeed, bug ships to prod | Ownership check throws on first foreign mutate |
| Persistence layer publishes during rollback, polluting external systems | You manually distinguish "real" writes from rollback writes | Bridge publishes only on commit |
| Adding logging / persistence requires touching every action | Cross-cutting concerns are scattered | `Middleware` wraps the whole transaction |

### How Holdfast compares to other patterns

| Pattern | Mutation site | Atomicity unit | Observer sees |
|---|---|---|---|
| `var` field + listeners | Anywhere | Single field | Every write |
| `MutableStateFlow` | `value =` | Single state | Every distinct value |
| Redux/Reducer | `dispatch(action)` | One action's reduce | Reducer's return |
| **Holdfast** | `mutate` inside `action { }` | Whole `action { }` | Committed value only |

Holdfast is closest to "Redux with locality" — your mutations are co-located
with the state they touch, not pushed through a central reducer, but
visibility and atomicity come from a transaction boundary.

---

## 2. The Shape of a Store

A store subclass declares its state using delegated properties. The base
class is generic in `Self` (the curiously-recurring-template pattern) so
extensions like `infix fun State<T>.mutate(T)` resolve against the concrete
holdfast type.

```kotlin
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val label by state { "initial" }
    val email by state(EmailNormalizer()) { "" }   // with transformer
}
```

### Type hierarchy at a glance

```
Store<Self>                         abstract base; holds states + middleware + active txn
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
holdfast/src/commonMain/kotlin/com/vynatix/holdfast/
  Store.kt          base class, action/mutate/effect/bridge/invoke, ownership check
  MutableState.kt   per-state observers, bridge, transformer, applyCommitted
  Transaction.kt    pendingWrites, commit/rollback, status state machine
  Middleware.kt     three-hook interceptor with metadata bag
  Contract.kt       State, Bridge, Transformer, Initializer, StateDelegate, Disposable
  StoreLock.kt      reentrant mutex over kotlinx.atomicfu SynchronizedObject
  UUID.kt           v4 UUID generator (used for unnamed transactions)
  platform/
    Threading.kt    expect currentThreadId, threadYield
```

---

## 3. Quickstart

```kotlin
// 1. Define a holdfast.
class TodoStore : Store<TodoStore>() {
    val items by state { emptyList<String>() }
    val draft by state { "" }
}

// 2. Create an instance.
val holdfast = TodoStore()

// 3. Subscribe.
val sub = holdfast { items effect { println("items=$this") } }
// fires immediately with the initial value: items=[]

// 4. Mutate atomically.
holdfast action {
    draft mutate "buy milk"
    items mutate items.value + draft.value
    draft mutate ""
}
// effect fires once per modified state, post-commit:
//   items=[buy milk]

// 5. Failed transactions roll back.
holdfast action {
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
class Profile : Store<Profile>() {
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
val result: TransactionResult<Unit> = holdfast action {
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
holdfast action {           // T_outer
    a mutate 1
    holdfast action {       // T_inner with parent = T_outer
        b mutate 2
    }                    // T_inner.commit merges {b->2} into T_outer
    error("outer fails") // discards both {a->1} and {b->2}
}
// a.value == initial, b.value == initial.
```

### 4.3 `mutate(value)` — Write

`State<T>.mutate(T)` is an extension on `Store<Self>`. It buffers the
post-`transformer.set` value into the active transaction's `pendingWrites`.

```kotlin
holdfast action {
    count mutate count.value + 1
}
```

**Inside an active transaction owned by the current thread**: buffers the
write. Reads on the same thread (`count.value`) see the pending write
(read-your-own-writes). Reads on other threads still see the committed
value, never the pending one.

**Outside any transaction (or on a non-owner thread)**: synthesizes a
one-shot `action { this@mutate mutate that }`. Middleware fires; observers
see only the committed value. This means standalone `holdfast { x mutate v }`
is equivalent to `holdfast action { x mutate v }` — never a "raw" write that
skips observers, middleware, or commit semantics.

### 4.4 `effect { … }` — Observe

`State<T>.effect(T.() -> Unit): Disposable` subscribes a function to a
state. It fires:

- **Once immediately** with the current `value` (post-`transformer.get`).
- **Once per top-level commit** that staged a write to this state. By default
  (`distinct = false`) a commit that re-applies the same value re-fires
  observers; declare the state with `state(distinct = true) { … }` to opt into
  StateFlow-style same-value dedup (see §6).

```kotlin
val sub = holdfast { count effect { println("count=$this") } }
// → count=0   (initial)
holdfast action { count mutate 5 }
// → count=5
sub.dispose()
holdfast action { count mutate 6 }
// (no output — disposed)
```

The receiver `this` is the new value. Returning a `Disposable` lets you
unsubscribe; observers held forever are a memory leak.

### 4.5 `bridge(b)` — External sync

`State<T>.bridge(Bridge<T>)` connects a state to an external system that
implements both `Observable<T>` (push to holdfast) and `Publisher<T>` (pull
from holdfast).

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
holdfast { items bridge persistence }
```

**Outbound** (`publish`) fires only on commit, never during the action body
or on rollback. **Inbound** (`observe`) updates the state via
`applyFromBridge`, which writes through the transformer's `set`, fires
observers, but does NOT call `publish` again — preventing publish loops.

### 4.6 `middlewares(...)` — Intercept

`Store.middlewares(vararg)` registers middleware that wrap every transaction.
Each middleware sees `onTransactionStarted` before the body runs,
`onTransactionCompleted` after the body returns successfully, and
`onTransactionError` if the body throws.

```kotlin
class Logger<V : Store<V>> : Middleware<V>() {
    override fun onTransactionStarted(c: MiddlewareContext<V>) =
        log("→ ${c.transaction.id}")
    override fun onTransactionCompleted(c: MiddlewareContext<V>) =
        log("✓ ${c.transaction.id}")
    override fun onTransactionError(c: MiddlewareContext<V>, e: Throwable) =
        log("✗ ${c.transaction.id}: $e")
}

holdfast.middlewares(Logger())
```

Middleware nests with the LAST-registered middleware outermost:
`middlewares(A, B)` runs `B.started`, `A.started`, the user action body,
`A.completed`, `B.completed` (and on a throw, `A.error` then `B.error`).
The chain is rebuilt fresh per `action`, so middleware added later applies
to subsequent actions.

`MiddlewareContext.metadata` is a per-transaction `MutableMap<String, Any>`
for cross-middleware communication.

### 4.7 `invoke { … }` — Context block

`store { … }` — the operator on `Store` — runs `block(self)` with no locks
and no transaction. It exists so store-extension members like `effect` and
`bridge` can be called with store-as-receiver:

```kotlin
val d = holdfast { count effect { … } }      // effect is an extension on Store<Self>
val v = holdfast { count.value }             // plain read
```

`holdfast { x mutate y }` is a special case — `mutate` itself synthesizes an
implicit action when there is no active transaction. So this form still
goes through middleware and observers.

---

## 5. Transaction Lifecycle (Workflow Diagram)

```
            ┌─────────────────────────────────────────────────────┐
            │  holdfast action { body }   on owner thread            │
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
            body returns       body throws          outer re-throws an
            normally           (any exception)      inner action's Error
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
| Action commits with final = v0 (same as start) | committed v0 | committed v0 | fires with `get(v0)` (`distinct = true` skips) | publishes raw v0 (`distinct = true` skips) |

Three things to internalize:

1. **Observers and bridges only ever see committed values.** No mid-transaction
   leak. No rolled-back leak. Same-value commits re-fire by default; states
   declared `state(distinct = true) { … }` dedup them.
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
       │ Invariant spans STORES?       │
       └──────────┬──────────┬─────────┘
              yes │      no  │
                  ▼          ▼
  ┌────────────────────┐ ┌───────────────────────────────┐
  │ atomic(a, b) { … } │ │ Multi-state, must be atomic?  │
  │    (see §15)       │ └──────────┬──────────┬─────────┘
  └────────────────────┘        yes │      no  │
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
When an invariant spans two or more STORES, a single-store `action` cannot
protect it — reach for a cross-store frame instead ([§15](#15-cross-store-transactions)).

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
| Direction | one-way (holdfast → callback) | two-way (holdfast ↔ external) |
| Inbound writes | not supported | yes, via `observe` |
| Per-state count | many | one |
| Disposed by | returned `Disposable` | reassigning `bridge =` (or never) |
| Use for | UI updates, logging, computed | persistence, server sync, Compose StateFlow |

### 7.4 "Action vs nested action vs invoke"

```
   I'm currently inside…           and I want to…              do this
   ────────────────────────────    ─────────────────────────   ─────────────
   nothing                         atomic multi-write         action { … }
   nothing                         single read or effect      holdfast { … }
   an outer action                 atomic sub-batch with own  action { … }
                                   savepoint semantics        (becomes nested)
   an effect callback              atomic write               action { … }
                                                              (the outer txn is
                                                              already committed
                                                              by the time effects
                                                              fire — your action
                                                              becomes top-level)
   a middleware hook               read state                 context.store.x.value
   a middleware hook               write state                NOT recommended;
                                                              use action's body to
                                                              orchestrate writes
```

---

## 8. Feature Differentiation Tables

### 8.1 Subscription mechanisms

| | `effect` | `bridge` | `Middleware` |
|---|---|---|---|
| Granularity | per-state | per-state | per-store (all transactions) |
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
| Sees other states? | no | yes — `context.store` |
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
class Logger<V : Store<V>>(private val tag: String) : Middleware<V>() {
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
holdfast.middlewares(Logger("Counter"))
```

### 9.2 Validation that aborts the transaction

```kotlin
class NonNegativeBalance : Middleware<AccountStore>() {
    override fun onTransactionCompleted(c: MiddlewareContext<AccountStore>) {
        // Pending writes already buffered; check against current view.
        if (c.store.balance.value < 0)
            error("Balance cannot go negative")
    }
}
```

Throwing in `onTransactionCompleted` propagates out of `runMiddlewareChain`,
the action's catch sees it, rollback drops pending writes — the negative
balance never becomes visible.

### 9.3 Optimistic UI with manual rollback

```kotlin
class Composer : Store<Composer>() {
    val text by state { "" }
    val sending by state { false }
    val lastError by state<Throwable> { NoError }
}

suspend fun send(holdfast: Composer, api: Api) {
    val draft = holdfast { text.value }
    holdfast action {
        sending mutate true
        text mutate ""           // optimistic clear
    }
    runCatching { api.send(draft) }
        .onSuccess { holdfast action { sending mutate false } }
        .onFailure { e ->
            holdfast action {
                sending mutate false
                text mutate draft     // restore
                lastError mutate e
            }
        }
}
```

Holdfast's automatic rollback handles failures inside one action; for
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

holdfast { items bridge JsonFileBridge(Path("todos.json"), TodoCodec) }
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
fun MyScreen(holdfast: TodoStore) {
    val bridge = remember { StateFlowBridge(holdfast.items.value) }
    DisposableEffect(holdfast) {
        holdfast { items bridge bridge }
        onDispose { /* leave bridge attached or clear via state.bridge = null */ }
    }
    val items by bridge.state.collectAsState()
    LazyColumn { items(items) { Text(it) } }
}
```

### 9.6 Computed / derived state

The library ships native operators for this — `computed { … }` (read-time)
and `derived(sources) { … }` (push-recomputed); see §14.2. The hand-rolled
idioms below remain useful when you want the recompute to land inside the
same commit as the source write:

**Read-only derived (compute on demand):** define a holdfast function.

```kotlin
class CartStore : Store<CartStore>() {
    val items by state { emptyList<Line>() }
    fun total(): Money = items.value.sumOf { it.price * it.qty }
}
```

**Stored derived (compute in the action that updates the source):**

```kotlin
class CartStore : Store<CartStore>() {
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
        val current = this   // the effect's payload: the committed List<Line>
        action { total mutate current.sumOf { it.price * it.qty } }
    }
}
```

The third pattern double-commits and is rarely worth the indirection;
prefer pattern two — keeping derived consistency inside the original
action — or the built-in `derived` from §14.2.

### 9.7 Read-your-own-writes inside an action

```kotlin
holdfast action {
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
holdfast action {                      // T_outer
    a mutate 1
    val inner = holdfast action {      // T_inner (parent = T_outer)
        b mutate 2
    }
    // inner is TransactionResult.Success — pendingWrites {b->2} merged into T_outer
    if (riskCheck() == BAD) error("abort")
    c mutate 3
}
// On outer commit: a=1, b=2, c=3 — observers fire once each.
// On error("abort"): nothing committed; observers see no change.
```

A nested `action` catches its own body's throw and returns
`TransactionResult.Error` — the inner rollback discards only the savepoint's
pending writes. **The outer action continues by default**; nothing propagates
unless you make it:

```kotlin
holdfast action {
    a mutate 1
    val inner = holdfast action { b mutate 2; error("flake") }
    // inner is TransactionResult.Error; b's pending was discarded by the
    // inner's own rollback. The outer continues with a's pending intact.
    c mutate 3
}
// Final: a=1, c=3, b=initial.
```

To abort the outer when the inner fails, re-throw explicitly —
`if (inner is TransactionResult.Error) throw inner.exception` — which lands
in the outer's catch and rolls back everything, including `a`.

### 9.9 Idempotent rollback for cancellation

A transaction handed to you (e.g. via `TransactionResult.Success.transaction`)
can be `rollback()`'d after the fact — it's a no-op if already finalized.

```kotlin
val res = holdfast action { x mutate 1 }
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
    this += holdfast { count effect { … } }
    this += holdfast { label effect { … } }
}
cd.dispose()
```

---

## 10. Concurrency Model

### 10.1 What's serialized

| Operation | Lock | Reentrant? | Notes |
|---|---|---|---|
| `holdfast action { … }` | `transactionLock` | yes | The only entry point; nested actions reuse the same lock |
| `holdfast.middlewares(...)` | `middlewareLock` | yes | Reading the chain is also under this lock |
| `holdfast.state(…)` (delegate first read) | `propertiesLock` | yes | After first read, no lock for delegate |
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

This means you can have `holdfast action { … }` running on T1 while T2
calls `holdfast.count.value` — T2 reads a consistent committed snapshot,
never T1's pending writes.

### 10.4 What is NOT thread-safe

- Holding a reference to a `Transaction` and calling `commit` / `rollback`
  on it from a thread that does not own it. The library does not stop
  you, but observers may fire on whatever thread you call from.
- Disposing an `effect` `Disposable` while the same observer is mid-fire
  on another thread. Dispose is idempotent and safe to call concurrently;
  the in-flight callback finishes uninterrupted.
- A `Bridge<T>.observe` callback that calls back into the store on a
  different thread *during* `applyFromBridge`. Lock-order analysis: the
  callback runs while no holdfast locks are held (the bridge owns its own
  threading), so a re-entrant `mutate` from the callback acquires
  `transactionLock` cleanly.

---

## 11. Testing Patterns

### 11.1 Asserting commit observability

```kotlin
@Test fun mutationFiresObserverOnce() {
    val v = CounterStore()
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
    val v = CounterStore()
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
    val v = CounterStore()
    var calls = 0
    v.middlewares(object : Middleware<CounterStore>() {
        override fun onTransactionStarted(c: MiddlewareContext<CounterStore>) { calls++ }
    })
    v { count mutate 42 }
    assertEquals(1, calls)
}
```

### 11.4 Asserting cross-holdfast rejection

```kotlin
@Test fun foreignStateRejected() {
    val a = CounterStore()
    val b = CounterStore()
    val foreign = a.count
    val r = b action { foreign mutate 99 }
    assertIs<TransactionResult.Error>(r)
    assertEquals(0, a.count.value)
}
```

### 11.5 Concurrency stress

```kotlin
@Test fun noLostUpdatesUnder8Threads() = runBlocking {
    val v = CounterStore()
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
| `IllegalStateException: State '…' is owned by OtherStore, not ThisStore` | Mutating a state owned by a different store | The state belongs to a different store — pass the state declared on the store you're acting on |
| `IllegalStateException: This State was not produced by store.state { }` | Passing a `computed{}` / hand-rolled `State` to `mutate`/`bridge` | Only `by state { }` properties are mutable; derive read-only views from those instead |
| `IllegalStateException: Cannot mutate state on a Committed transaction` | Mutating after manually calling `commit()`/`rollback()` on the active transaction inside the action body | Let `action` manage commit/rollback; start a new `store action { … }` for further writes |
| `IllegalStateException: <StoreClass> is disposed — dispose() is terminal` | Calling any state API after `dispose()` | `dispose()` is terminal — create a new store instance, or don't dispose a store still in use |
| `IllegalStateException: emit(event) called outside of an action / suspendAction` | `EventfulStore.emit` outside a transaction | Emit only inside `action { }` / `suspendAction { }` so rollback can discard staged events |
| Bridge keeps publishing forever in a loop | Bridge's `publish` calls into a system that re-publishes back and the bridge does not dedupe | Have the bridge dedupe (compare to last-published) before notifying observers |
| Effect callbacks leak after a Composable disappears | `Disposable` not captured | Use `DisposableEffect` and call `.dispose()` in `onDispose` |
| Nested action's commit "doesn't seem to do anything" | Inner committed — but it's a savepoint; outer still owns the pending writes | This is correct. Inner's commit merged into outer; outer's commit/rollback is what the world sees |

---

## 13. API Reference

### `Store<Self>`

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
| `scope` | `open val scope: CoroutineScope` | Scope for the store's async work; resolution order: per-call parameter → subclass override → `bindToScope` binding → `Store.defaultScope` |
| `bindToScope` | `fun bindToScope(scope: CoroutineScope)` | Binds the store to a scope (level 3 of the resolution chain); rebindable, never cancels the previous or new scope |
| `dispose` | `fun dispose()` | Terminal, idempotent teardown — drops observers, detaches bridges, clears middleware; subsequent state APIs throw `IllegalStateException` naming the store class |
| `isDisposed` | `val isDisposed: Boolean` | Whether `dispose()` has been called |
| `properties` | `val properties: Map<String, State<*>>` | Snapshot of registered states |
| `getState` / `hasState` / `removeState` / `clearStates` | … | Reflection over the property map; `removeState`/`clearStates` dispose observers + bridge silently |

### Extensions on `State<T>` (member-extensions of `Store<Self>`)

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
open class Middleware<V : Store<V>> {
    data class MiddlewareContext<V>(
        val store: V,
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

    fun getOrThrow(): R      // Success.value, or rethrows the original Error.exception
    val valueOrNull: R?      // Success.value, or null on Error
}

// Chainable side-effect hooks — each returns the receiver.
inline fun <R> TransactionResult<R>.onSuccess(block: (R) -> Unit): TransactionResult<R>
inline fun <R> TransactionResult<R>.onError(block: (TransactionResult.Error) -> Unit): TransactionResult<R>

enum class TransactionStatus { Active, Committed, RolledBack, Failed }
```

---

## 14. The 1.1 surface

Everything below ships in 1.1 on top of the 1.0 baseline above. Each
capability is independently usable; pick the ones you need.

### 14.1 `Store.snapshot()` / `Store.restore(snapshot)`

```kotlin
class StoreSnapshot internal constructor(internal val rawValues: Map<String, Any>) {
    val stateNames: Set<String>
    val size: Int
}

fun <V : Store<V>> V.snapshot(): StoreSnapshot
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot): TransactionResult<Unit>
```

`snapshot` captures the raw stored value of every state that has been
delegate-initialized at least once. `restore` writes them back inside a
single top-level `action`, bypassing `transformer.set` so asymmetric
transformers (encryption, JSON codecs) round-trip losslessly.

```kotlin
val snap = holdfast.snapshot()
holdfast action { count mutate 9999; label mutate "wrong" }
holdfast.restore(snap)               // count + label back to snapshot values
```

Restore-time bridge publish: yes. Detach bridges first if the snapshot
shouldn't echo back to your persistence layer. Restore of an unknown state
name throws (caught by the wrapping action → `TransactionResult.Error`).

### 14.2 `Store.computed { }` / `Store.derived(sources) { }`

```kotlin
fun <V : Store<V>, T : Any> V.computed(compute: V.() -> T): State<T>
fun <V : Store<V>, T : Any> V.derived(
    vararg sources: State<*>,
    compute: V.() -> T,
): Pair<State<T>, Disposable>
```

- **`computed`**: read-time, no observation. Cheap. The returned `State<T>`
  has no observer mechanism — every read of `value` re-runs `compute`.
- **`derived`**: push-recomputed. Subscribes to each source via `effect`;
  on each source commit, runs `compute()` inside a fresh top-level action
  on the same store and stages the result in a backing `MutableState`.
  The returned `State<T>` is a real observable state — use `effect` to
  subscribe.

The recompute is deferred via `Store.postCommit` (an internal queue) so
it doesn't re-enter the parent's `pendingWrites` map mid-iteration.
Disposing the `Disposable` stops recomputation.

### 14.3 `atomic(vararg stores) { body }`

```kotlin
fun <R> atomic(
    vararg stores: Store<*>,
    policy: FramePolicy = FramePolicy.Strict,
    body: () -> R,
): TransactionResult<R>
```

Brackets multiple stores' transactions so they commit-or-rollback together.
Inside `body`, `v1.action { … }` and `v2.action { … }` join the atomic
frame as savepoints of each store's root. On body throw, every store is
rolled back; on body return, every store commits in lock order with
sequential observer fanout per-store.

```kotlin
val r = atomic(accountA, accountB) {
    accountA.action { balance update { it - amount } }
    accountB.action { balance update { it + amount } }
}
```

Stores are sorted by `Store.lockOrderKey` (process-monotonic, set at
construction) before lock acquisition — deadlock-safe across any
combination. Nested `atomic` is supported (savepoint semantics) with an
always-on lock-order check. This section is only the signature summary —
the full contract (enrollment enforcement, error escalation, `FramePolicy`,
middleware phases, frame observability) lives in
[§15 Cross-Store Transactions](#15-cross-store-transactions).

### 14.4 `EncryptingTransformer` + `Cipher` (`com.vynatix.holdfast.crypto`)

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

**Caveat — `distinct = true` is inert on an encrypted state backed by a
non-deterministic cipher.** State dedup compares post-`set` raw values, which
for an `EncryptingTransformer` are ciphertext. A secure AES-GCM-with-per-value-IV
cipher encrypts the same plaintext to different ciphertext each time, so equal
logical values never compare equal and dedup never fires — observers and
`KvBridge` publish on every commit regardless. This is by design: dedup does not
decrypt to compare (running `transformer.get` per commit would break the
asymmetric-transformer / no-double-decrypt invariants). Dedup upstream if you
need logical-value dedup.

### 14.5 `FileSystemKvStore` (`com.vynatix.holdfast.bridge`)

```kotlin
expect class FileSystemKvStore(rootPath: String) : KvStore
```

Backend for `KvBridge` that persists each key as a single file under
`rootPath`. Atomic writes via tempfile + rename on JVM (`Files.move(ATOMIC_MOVE)`)
and iOS (`NSData.writeToURL(atomically=true)`).

```kotlin
val kv = FileSystemKvStore(rootPath = "$home/.myapp")
holdfast { balance bridge KvBridge(kv, "balance:1", LongCodec) }
// balance auto-persists on every commit; new holdfasts attaching the same
// KvBridge hydrate from disk via load-on-attach.
```

Key encoding: URL-percent-encoded so any String is a safe filename.

### 14.6 Standard middleware (`com.vynatix.holdfast.middleware`)

```kotlin
class LoggingMiddleware<V>(tag: String, log: (String) -> Unit = ::println)
class TimingMiddleware<V>(onResult: (id: String, status: TransactionStatus, elapsedMs: Long) -> Unit)
class ValidationMiddleware<V>(check: V.() -> Unit)
class ProfilingMiddleware<V>(onSample: ((TransactionSample) -> Unit)? = null) {
    fun profile(): StoreProfile
    fun reset(): StoreProfile   // atomic drain: zeroes and returns the final snapshot
}
data class TransactionSample(transactionId, frameId, isSavepoint, status, duration, modifiedStates)
data class StoreProfile(transactionCount, committedCount, rolledBackCount, savepointCount,
                        totalDuration, slowest, stateWriteCounts) // + maxDuration, averageDuration
```

Drop-in. Order in `holdfast.middlewares(...)` matters — the LAST argument
is the outermost middleware (its `onTransactionStarted` runs first; its
`onTransactionError` runs last). Place logging/audit middleware LAST so
it sees errors thrown by validation middleware placed earlier.

`ProfilingMiddleware` profiles every transaction: monotonic-clock duration
(body + inner middleware; commit fanout is excluded), outcome, savepoint and
`frameId` identity, and which state properties were written. Read aggregates
with `profile()` (per-state write counts, slowest sample, average/max
duration), stream every sample via the `onSample` callback, and drain with
`reset()` — it atomically zeroes the counters and returns the final
snapshot, so periodic collection is lossless. Its own bookkeeping never
throws, so attaching it cannot change a transaction's outcome; under
`suspendAction` a body that resumes on another thread yields a sample with
empty `modifiedStates` (owner-thread-confined read) rather than an error.
Each transaction is recorded at most once, and `status` is hook-level
attribution: `Committed` means the completed hook fired (before commit), so
a LATER throw — an outer middleware, or another participant vetoing an
`atomic` frame — can leave a `Committed` count for a rolled-back
transaction. Register it LAST to profile the full middleware chain with
exact sync attribution, or first to profile the bare body at the cost of
that accuracy.

### 14.7 `KvBridge` + `Codec` + `KvStore` (`com.vynatix.holdfast.bridge`)

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
class KvBridge<T : Any>(
    kv: KvStore, key: String, codec: Codec<T>,
    onDecodeError: ((encoded: String, cause: Throwable) -> Unit)? = null,
) : Bridge<T>
```

Generic save-on-commit + load-on-attach. Combine with any `KvStore`
implementation (in-memory, file system, MultiplatformSettings, …).

If the persisted payload fails to decode on attach (corrupt bytes, schema
change), `KvBridge` **silently drops** it by default — the state stays at its
initializer, and the next commit **overwrites the un-decodable bytes** before
you can inspect them. Pass `onDecodeError` to observe the raw payload and cause
at the moment of the drop (so you can quarantine or migrate it first); it does
not change the drop behavior. A save failure (`encode`/`put` throw) surfaces the
surrounding transaction as `TransactionResult.Error` — after the in-memory
commit and observer fanout have already applied — not a rollback.

### 14.8 `:holdfast-coroutines`

```kotlin
fun <T : Any> State<T>.asFlow(): Flow<T>
fun <T : Any> State<T>.asStateFlow(
    scope: CoroutineScope = …,   // defaults to the owning store's Store.scope
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T>
// Eager publishing: asStateFlow(started = SharingStarted.Eagerly).

suspend fun <T : Any> State<T>.first(predicate: (T) -> Boolean): T
suspend fun <T : Any> State<T>.awaitValue(target: T): T

suspend fun <V : Store<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R>
suspend fun <R> suspendAtomic(vararg vaults: Store<*>, body: suspend () -> R): TransactionResult<R>
// Seeded overload (recommended; wasmJs-safe, no runBlocking):
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    initial: T,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable>
// Seedless overload (runBlocking seed — crashes wasmJs, can deadlock single-thread):
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable>

interface SuspendingKvStore                          // suspend get / put / remove / snapshot
interface SuspendingBridge<T : Any> : Bridge<T>      // suspend fun publishAwaited(value: T)
class SuspendingKvBridge<T : Any> : SuspendingBridge<T>, Disposable  // errors: SharedFlow<Throwable>; dispose()
fun <T : Any> SuspendingKvStore.suspendingBridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge<T>
// Deprecated WARNING-level alias returning the same type: SuspendingKvStore.bridge(key, codec, scope)
```

`suspendAction` allows the body to suspend (`delay`, `await`, `withContext`).
Mutually exclusive with blocking `Store.action` on the same store via an
internal coroutine `Mutex` installed lazily. Cancellation of the body
rolls back the transaction; commit phase wraps in `NonCancellable` so
observer/bridge fanout completes cleanly even if the surrounding scope
cancels mid-commit.

The body runs inside a single-store suspending scope (a relaxed frame marker
that never polices writes to OTHER stores). That scope makes three previously
broken shapes well-defined: a nested `suspendAction` on the SAME store joins as
a savepoint (inner commit merges, inner rollback discards only inner writes,
one observer fanout at the outer commit) instead of self-deadlocking on the
mutex; read-your-own-writes follows the body across dispatcher hops
(`withContext(Dispatchers.X)`), so a staged `state.value` is visible on
whichever thread resumes it (JVM/Android; on iOS/wasmJs a nested
`withContext(otherDispatcher)` section loses this, the same gap §15.1
documents for enrollment); and a blocking `action { }` — or a `suspendAtomic`
enrolling this store — called from INSIDE the body throws
`FrameInteropException` immediately (hoist the frame: run `suspendAtomic`
first and `suspendAction` inside it) rather than livelocking on the serializer
this `suspendAction` already holds. Disjoint-store frames inside the body stay
legal, subject to the global lock-order rule.

`suspendingBridge(...)`'s product is awaited by `suspendAction`'s commit
phase; under sync `action { }` it saves fire-and-forget through a conflated
channel (rapid publishes coalesce). The awaited path's failure contract is
ordering plus a surfaced error, not rollback: a `publishAwaited` throw
surfaces as `TransactionResult.Error` after the in-memory commit and
observer fanout have already applied, and the same throwable is emitted on
the bridge's `errors` flow. Fire-and-forget failures surface only on
`errors`. `SuspendingKvBridge` also owns a long-lived drainer coroutine, so it
is `Disposable`: call `dispose()` when done to close the save channel (its last
conflated value still drains), cancel in-flight loads, and shut down (`publish`
then returns `false`, `observe` is a no-op). Detaching via `state bridge null`
releases only the inbound subscription, not the drainer.

`suspendDerived` has two overloads. Prefer the **`initial`-seeded** one: it
holds `initial` until the first async `compute` lands and uses no `runBlocking`,
so it runs on every target including wasmJs. The **seedless** overload seeds
eagerly with `runBlocking` for a computed-value-at-construction contract, but
that crashes on wasmJs and can deadlock on single-threaded dispatchers. Both
share source-driven recompute, later-wins semantics, and a `dispose()` that
stops recomputes and unregisters the synthetic backing state.

`Middleware<V>` sync hooks fire on `suspendAction` as well as `action`
(2.0; was a documented no-op limitation in 1.1). Concentric-ring ordering:
`onTransactionStarted` runs in chain order before the body, `onTransactionCompleted`
or `onTransactionError` runs in reverse chain order around the body. Each hook
invocation is wrapped in `runCatching` — a throw from one middleware's hook does
not abort other middlewares' hooks. Behavior change: middleware authors who
relied on "suspendAction won't trigger me" must verify their hooks are idempotent
under the suspending path.

Limitations: body should be single-threaded — spawned threads' `mutate`
calls fall outside the recognized owner.

### 14.9 `:holdfast-compose`

```kotlin
@Composable
fun <T : Any> State<T>.collectAsState(): androidx.compose.runtime.State<T>

@Deprecated("The Store receiver is unused; call collectAsState() directly on the state.")
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(vararg keys: Any?, make: () -> Disposable): Disposable
```

Bridges holdfast state into Compose's snapshot system as a
`androidx.compose.runtime.State<T>`, triggering recomposition on every
successful commit. Backed by `produceState`; subscription's lifecycle is
tied to the surrounding Composable. `rememberDisposable` runs `make` on
composition entry and again when any of `keys` changes (the lambda itself
is not a key); the returned `Disposable` is disposed on key change or
composition exit. Neither entry point survives `Store.dispose()`: composing
against an already-disposed store throws `IllegalStateException`
(naming the disposed store class), while disposing mid-composition silently freezes
`collectAsState` values at the last commit — dispose the store only after
its dependent Composables have left the composition.

### 14.10 The cookbook: 1.1 idioms

**Encrypted-at-rest credential**:
```kotlin
class CredsStore : Store<CredsStore>() {
    val token by state(EncryptingTransformer(SystemAesCipher())) { "" }
}
val kv = FileSystemKvStore("$home/.app/creds")
holdfast { token bridge KvBridge(kv, "session", StringCodec) }
// token is plaintext on read; persisted file contains ciphertext.
```

**Cross-store transfer with one-line atomicity**:
```kotlin
fun AccountStore.transferTo(other: AccountStore, cents: Long) =
    atomic(this, other) {
        action { balance update { it - cents } }
        other.action { balance update { it + cents } }
    }
```

**Auto-recomputed running total**:
```kotlin
val (total, dispose) = holdfast.derived(holdfast.items) { items.value.sumOf { it.amount } }
val sub = holdfast { total effect { uiTotal.value = this } }
// later: sub.dispose() ; dispose.dispose()
```

**Snapshot-and-restore for undo**:
```kotlin
val undoStack = ArrayDeque<StoreSnapshot>()
fun saveCheckpoint() { undoStack.addLast(holdfast.snapshot()) }
fun undo() = undoStack.removeLastOrNull()?.let { holdfast.restore(it) }
```

**Async transactional fetch**:
```kotlin
val r = holdfast.suspendAction {
    status mutate Status.Loading
    val data = api.fetch()                  // suspending I/O
    items mutate (items.value + data)
    status mutate Status.Loaded
    data
}
```

### 14.11 Validation 0.3.0 — three modules at the boundary

[Hallmark](https://github.com/vynatix/hallmark) (`com.vynatix:hallmark`) is a
standalone KMP refinement-types library maintained in its own repository;
`:holdfast-hallmark` (in this repo) is a thin Holdfast adapter on top of it;
`com.vynatix:hallmark-coroutines` adds suspend support. The premise: every primitive (`String`, `Long`, …) flowing into your
domain is validated and wrapped in a typed `Boxed<P>` exactly once, at the
boundary. Inside the domain, you pass wrappers, never raw primitives — the
canonical fix for the **primitive obsession** code smell.

#### Module structure

| Artifact | Role |
|---|---|
| `com.vynatix:hallmark` *(separate [Hallmark repo](https://github.com/vynatix/hallmark))* | Core lib. No Holdfast dep. `Boxed` / `Rule` / `Validator` / composite DSL / 14 prebuilt rules / multi-error `HallmarkResult`. |
| `com.vynatix:hallmark-coroutines` *(separate Hallmark repo)* | Suspend extension. `SuspendRule`, `SuspendValidator`, `suspendValidator { }` DSL. |
| `com.vynatix:holdfast-hallmark` *(this repo)* | Holdfast adapter. `ValidatingTransformer`, `Store.boxed { }` factory, `BoxedCodec`. |

#### Core surface (`com.vynatix.hallmark`)

```kotlin
interface       Boxed<P : Any>                 { val value: P }

abstract class  Rule<P>(val code: String, val messageTemplate: String) {
    abstract fun validate(value: P): Boolean
    open fun message(value: P): String       = messageTemplate
    open fun args(value: P): Map<String, Any?> = emptyMap()
}

data class      Violation(message, path, code, rule, args)

sealed interface HallmarkResult<out OUT> {
    data class Success<OUT>(val value: OUT)   : HallmarkResult<OUT>
    data class Failure(val violations: NonEmptyList<Violation>) : HallmarkResult<Nothing>
    fun getOrThrow(): OUT     // throws HallmarkException on Failure
    fun getOrNull(): OUT?
}

enum class      SpecMode { ALL, ANY }
data class      Spec<P : Any, O : Boxed<P>>(rules, mode, factory)

interface       Validator<IN, OUT> {
    fun validate(value: IN): HallmarkResult<OUT>
    infix fun of(value: IN): OUT              // throws HallmarkException on Failure
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
EmailValidator of "not-an-email"                        // throws HallmarkException

val r = EmailValidator.validate(" ")                    // returns HallmarkResult.Failure
when (r) {
    is HallmarkResult.Success -> store(r.value)
    is HallmarkResult.Failure -> r.violations.forEach { v ->
        log("${v.code}: ${v.message} args=${v.args}")
    }
}
```

The 14 prebuilt rules (in `com.vynatix.hallmark.rules`) cover the basics —
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

`com.vynatix.hallmark.rules` ships nine practical-but-not-RFC-strict
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

#### Holdfast integration (`:holdfast-hallmark`)

Two state factories:

```kotlin
class UserStore : Store<UserStore>() {
    val email       by boxed(EmailValidator) { "init@example.com" }       // State<Email>
    val displayName by boxedHandle(NameValidator) { "init" }              // BoxedHandle<String, Name>
}

holdfast action {
    email mutate (EmailValidator of "alice@example.com")     // explicit validator at the call site
    displayName assign "Alice"                                // assign infix via BoxedHandle
    email mutate Email("not-an-email")                        // rolls back via transformer
}

// KvBridge persistence
holdfast {
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
  is a `BoxedHandle<V, P, O>` (typed to its owning store `V`) bundling state +
  validator. Enables the `assign` infix for one-line civilize-and-mutate at the
  call site. `assign` is powered by Kotlin context parameters, so consuming
  modules must add `-Xcontext-parameters` to their `freeCompilerArgs`; without
  that flag use the two-step `handle.state mutate handle.civilize(...)` instead.
  `assign` is gated — typed to the owning store (wrong-store use is a compile
  error) and refuses at runtime unless called inside that store's own open
  `action { }` on the action's thread.
- **`ValidatingTransformer`** — re-validates on every write, so
  constructor bypass (`data class copy`) is rejected.
- **`BoxedCodec`** — round-trips `Boxed<P>` through any `Codec<P>`.

#### Suspend validation (`hallmark-coroutines`)

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

val r: HallmarkResult<NewUser> = v.validate(NewUser("alice", "Alice"))
```

The composite DSL accepts either sync or suspend sub-validators via
overloaded `field` factories.

#### Holdfast adapter for suspend validation (`:holdfast-hallmark-coroutines`)

```kotlin
suspend fun adoptUsername(name: String): TransactionResult<Unit> =
    holdfast.suspendValidateAndMutate(holdfast.username, UsernameValidator, name)
```

Runs the suspend validator (which may do I/O), then mutates the Store state
inside a `suspendAction { }`. Atomic: validation failure rolls back the
entire transaction.

#### Transformer composition — `Transformer.then`

```kotlin
import com.vynatix.holdfast.then

val pipeline = ValidatingTransformer(EmailValidator).then(EncryptingTransformer(cipher))

class UserStore : Store<UserStore>() {
    val email by state(transformer = pipeline) { /* … */ }
}
```

`then` chains transformers: `set` runs `this.set` then `other.set`; `get`
runs `other.get` then `this.get` (reverse order, so round-trip preserved).
Ships in `:holdfast` core.

#### Migrating from Konform

The [Hallmark repository](https://github.com/vynatix/hallmark) carries a
Konform migration guide with a 1:1 mapping from Konform's `Validation<T>`
API to Hallmark's surface. No runtime Konform dep.

---

## 15. Cross-Store Transactions

Per-domain stores keep coupling visible and tests isolated — but the rare
invariant that SPANS stores (a sign-out that must clear four stores, a token
update that must flip a status flag with it) cannot be protected by any
single-store `action`. The cross-store frame is the tool for exactly that
shape:

```kotlin
val r = atomic(settings, backendStatus) {
    settings.action { backendToken mutate token }
    backendStatus.action { authFailed mutate false }
}

// Suspending peer (:holdfast-coroutines) — same contract, suspending body:
val r2 = suspendAtomic(settings, backendStatus) {
    settings { backendToken mutate token }
    backendStatus { authFailed mutate false }
}
```

**When NOT to reach for a frame:** if two states change together in every
flow, they belong in ONE store — frames are for the rare cross-domain step,
not a substitute for store design. Frames hold every participant's lock for
the whole body: keep bodies small and free of I/O (the same rule as
`action { }`), because a slow body or observer on one participant stalls
every other action on ALL participants.

### 15.1 Enrollment is enforced

Every store written inside the body must be in the participant list. A write
to an unenrolled store throws `UnenrolledStoreException` — such a write
would commit independently and would NOT roll back with the frame, silently
breaking the all-or-nothing promise:

```
UnenrolledStoreException: SettingsStore#12 was mutated (via action) inside
atomic(HistoryStore#3, BackendStatusStore#4) but is not enrolled. Its writes
would commit independently and would NOT roll back with the frame.
Fix: add SettingsStore#12 to the atomic(...) participant list. …
```

The `#N` suffix is the store's `lockOrderKey` — a stable per-instance id
that distinguishes multiple instances of one store class (`accountA` vs
`accountB`) and matches the order in which frames acquire locks. The `via`
clause names the actual entry point (`action`, `mutate`, `suspendAction`) —
a bare `mutate` on an unenrolled store is reported as `via mutate` even
though it internally synthesizes a one-shot action.

Rules of the enforcement window:

- It covers the **body only**. Middleware hooks, commit fanout, and observer
  callbacks run outside the window — an observer that reacts to a frame
  commit by writing to a foreign store is post-commit and stays legal.
- **Reads** of unenrolled stores are always legal (they see committed values).
- There is **no auto-enroll**: enrolling mid-frame would acquire a lock
  outside the sorted global order and reintroduce the deadlock class the
  design exists to prevent.
- The deliberate escape hatch is per call site:
  `atomic(a, b, policy = FramePolicy.AllowUnenrolled) { … }` — greppable,
  and scoped to that one frame.
- In `suspendAtomic`, the enforcement marker travels with the coroutine
  across dispatcher hops (JVM/Android: `ThreadContextElement`; iOS/wasmJs: a
  delegating interceptor — on those two platforms a nested
  `withContext(otherDispatcher)` section inside the body is not policed).
  Coroutines launched onto OTHER scopes (`GlobalScope.launch`) escape the
  frame on every platform — those writes are concurrent, not in-frame.

### 15.2 Inner errors escalate

An inner `action { }` / `suspendAction { }` on a participant that returns
`TransactionResult.Error` **aborts the whole frame** — every participant
rolls back and the frame returns `Error` carrying the inner exception.
Consequence for the inner call site: under `Strict`, the inner action does not
*return* its `Error` — escalation rethrows the original exception to unwind the
frame, so a `when (innerResult)` around it never reaches the `Error` branch.
The pre-0.3 behavior (a failed sub-action commits the other stores anyway) is
reachable per call site with `policy = FramePolicy.TolerateInnerErrors`, and
then checking each inner result is on you. Frame-contract violations
(`UnenrolledStoreException`, `FrameLockOrderException`,
`FrameInteropException`) are never tolerated: they rethrow out of the frame
after rollback instead of being folded into an ignorable `Error` result.

Policies combine: `FramePolicy.AllowUnenrolled + FramePolicy.TolerateInnerErrors`.

### 15.3 The consistency contract

For `atomic(a, b, c) { body }` with lock order a < b < c:

1. **Lock acquisition** — participants are de-duplicated and sorted by
   `Store.lockOrderKey`; locks are acquired in that global order (deadlock-safe
   by construction). One root transaction opens per store, all sharing one
   `Transaction.frameId` (`"atomic-<uuid>"` / `"suspendAtomic-<uuid>"`).
2. **Middleware `onTransactionStarted`** — per store, in lock order
   (outermost-registered middleware first within each store). A throw aborts
   the frame before the body runs.
3. **The body** — mutates stage into each store's root; inner actions are
   savepoints. Reads on the owner thread see pending writes
   (read-your-own-writes); other threads see committed values only.
4. **Middleware `onTransactionCompleted`** — ALL stores' hooks fire before
   ANY store commits, so a validation middleware throwing on store `c` still
   rolls `a` and `b` back. Corollary for middleware authors: for frames,
   `completed` does NOT mean durably-committed.
5. **Commit** — per store, in lock order. Store `a`'s observer → bridge →
   event fanout completes before store `b`'s commit applies. There is no
   cross-store snapshot isolation; however, an observer on `a` running on the
   frame's thread reads `b` through `b`'s still-active root, so it sees `b`'s
   about-to-be-committed value and the cross-store invariant holds at every
   fanout point.
6. **Rollback** (body throw, `started`/`completed` throw, or inner-error
   escalation) — REVERSE lock order, `onTransactionError` per store first,
   then rollback. Rollback never touches state and never re-runs
   `Transformer.set`.
7. **Post-commit drain** — deferred work (`derived` recomputes) runs per
   store at frame exit, after that store's transaction slot is restored.

`suspendAtomic` follows the same phases with the suspending machinery: the
per-store `AsyncSerializer` mutex instead of the blocking lock, commit under
`withContext(NonCancellable)`, `SuspendingBridge.publishAwaited` awaited —
a `publishAwaited` throw surfaces as `TransactionResult.Error` with the
in-memory commit and observer fanout already applied (surfaced error, not
rollback) — and the event drain honoring `BufferOverflow.SUSPEND`
back-pressure.
(One caveat: `SuspendingMiddlewareHooks` async hooks do not fire for frame
roots yet — sync hooks do.)

**Durability non-goal:** a frame is in-memory 2PC across stores in ONE
process. Bridge/persistence publishes remain per-store post-commit fanout;
there is no crash-consistency across external stores, and if a commit itself
throws partway through phase 5, already-committed stores stay committed.

**Result handle:** `Success.transaction` / `Error.transaction` is the LAST
participant root in lock order (the highest `lockOrderKey`) — a stable
terminal-state reference, not necessarily the store that failed. To attribute
per-store outcomes, correlate the roots via `Transaction.frameId` (middleware
or a `FrameObserver`). When a per-store commit throws in phase 5, the returned
`Error` message names the offending store even though `transaction` still
points at the last root.

### 15.4 Nesting and interop

- A frame nested inside an `action` or another frame on the same
  thread/coroutine opens SAVEPOINTS for shared stores: the nested frame's
  commit merges into the enclosing scope, and an enclosing rollback discards
  those merged (shared-store) writes too. Savepoint semantics apply ONLY to
  shared stores — an INTRODUCED store (next bullet) does NOT roll back with
  the enclosing scope. A nested frame's `Error` escalates to the enclosing
  frame like an inner action's (same `TolerateInnerErrors` opt-out).
- A nested frame may only INTRODUCE a store (one not enrolled anywhere in
  the enclosing chain) when the enclosing frame's policy is
  `FramePolicy.AllowUnenrolled`; under `Strict` the introduction throws
  `UnenrolledStoreException` at entry — an introduced store gets a FRESH
  root that commits at the nested frame's exit and does NOT roll back with
  the enclosing frame (REQUIRES_NEW semantics), the same escape a bare
  unenrolled write would be. An introduced store must also sort above every
  `lockOrderKey` the enclosing frame holds; otherwise
  `FrameLockOrderException` fires at entry, before any lock is taken.
  Prefer enrolling everything in the outermost frame.
- Blocking and suspending frames do not compose on the same stores:
  blocking `action { }` (or a nested `atomic`) on a `suspendAtomic`
  participant throws `FrameInteropException` immediately instead of
  deadlocking on the suspend mutex — use `mutate`/`update` or
  `suspendAction { }` inside a suspending body. Inside `suspendAtomic`, a
  participant's `suspendAction { }` joins the frame as a savepoint.
- **Torn-commit caveat:** the introduce-vs-savepoint distinction above is only
  enforced when an enclosing FRAME marker exists. Inside a plain `action` body
  there is no marker, so `c.action { atomic(a, b, c) { … } }` silently mixes
  flavors: `c` is shared (savepoint — rolls back with the enclosing
  `c.action`), while `a` and `b` get fresh roots that commit at the `atomic`'s
  exit. If the enclosing `c.action` then throws, `a`/`b` stay committed while
  `c` rolls back — a partial commit the frame cannot detect. When you need
  all-or-nothing across `a`, `b`, `c`, make the OUTER scope the frame
  (`atomic(a, b, c) { … }`) rather than nesting a frame inside a single-store
  action.

### 15.5 Frame observability

Per-store middleware already sees every frame root (correlate the N
per-store transactions of one frame via `Transaction.frameId`). For
app-level audit/telemetry that wants the frame as ONE event, register a
`FrameObserver` (experimental — `@ExperimentalStoreApi`):

```kotlin
@OptIn(ExperimentalStoreApi::class)
FrameObservers.register(object : FrameObserver {
    override fun onFrameStarted(frameId: String, participants: List<Store<*>>) { … }
    override fun onFrameCommitted(frameId: String) { … }
    override fun onFrameRolledBack(frameId: String, cause: Throwable) { … }
})
```

### 15.6 Testing cross-store invariants

`:holdfast-testing` correlates frames across tracked handles:

```kotlin
storeTest {
    val ha = track(accountA)
    val hb = track(accountB)

    atomic(accountA, accountB) { /* transfer */ }

    (ha and hb).shouldCommitTogether()      // same frameId committed on both
    ha.committedFrameIds()                  // all frame commits, in order
    (ha and hb).shouldNotCommitTogether()   // negation, e.g. after a rollback
}
```

---

## Appendix A — One-page cheatsheet

```kotlin
class V : Store<V>() {
    val x by state { 0 }
    val s by state(MyTransformer()) { "" }
    val token by state(EncryptingTransformer(cipher)) { "" }   // 1.1
    val items by state(distinct = true) { emptyList<Item>() }  // 1.1 dedup
}
val v = V()

// Subscribe.
val sub = v { x effect { println("x=$this") } }       // initial: x=0

// Atomic single-holdfast mutation; body return flows into Success.
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

// Cross-holdfast atomic (1.1).
atomic(accountA, accountB) {
    accountA.action { balance update { it - cents } }
    accountB.action { balance update { it + cents } }
}

// Snapshot / restore (1.1).
val snap = v.snapshot()
v.restore(snap)

// Push-recomputed derived state (1.1).
val (total, d) = v.derived(v.items) { items.value.sumOf { it.amount } }

// Suspending body (1.1, holdfast-coroutines).
val r2 = v.suspendAction { status mutate Loading; val data = api.fetch(); status mutate Loaded; data }

// Cleanup.
sub.dispose(); sub2.dispose(); d.dispose()
```
