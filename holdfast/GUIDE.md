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
15. [Cross-Store Transactions](#15-cross-store-transactions) — enrollment, inner errors, the consistency contract, nesting, observability
16. [Snapshots, persistence and boot (experimental)](#16-snapshots-persistence-and-boot-experimental) — reset, encoding snapshots, schema versions, state tags and redaction

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
 ├── state(transformer?, init)      declares a state by property name (materialized on first need)
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
  StateDeclaration.kt / StateRegistry.kt
                    declared vs materialized states; the delegate state(…) returns
  Materialization.kt / NoWriteRegion.kt
                    initializer latches, cycle detection, initializers may not write
  ConsistentRead.kt write brackets; the consistent cut snapshot() takes
  StoreLock.kt      reentrant mutex over kotlinx.atomicfu SynchronizedObject
  UUID.kt           v4 UUID generator (used for unnamed transactions)
  platform/
    Threading.kt    expect currentThreadId, threadYield
    StoreThreadLocals.kt
                    expect running-initializer slot (NoWriteRegion)
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

Declares a state keyed by the property name. Declaring happens while the store
is constructed and runs nothing: the store knows every declared state from
then on. The `MutableState` itself is created from `init` — the state is
*materialized* — the first time it is needed: on the first read of the
property, or when `snapshot()`/`restore()` (§14.1) or the experimental
`reset()` (§16.1) needs it. Subsequent reads of the same property return the
same `State` (delegate identity is preserved across reads). The store keeps
`init` for its whole lifetime: after `removeState`, the next read creates the
state from it again, and the experimental `reset()` (§16.1) runs it again to
put the state back to its initial value.

`init` runs once per materialization, on the thread that first needs the
state. (`reset()` also re-runs it inside its own transaction, and a sterile
`restore()` re-runs a `Remote` state's; see §16.1/§16.4 for how those runs
differ.) The store takes no lock to run it — only the state's own latch
(§10.2) — and another thread needing the state meanwhile waits for it. It
does run under whatever locks its caller already holds: first needed inside
an action, an `atomic(...)` frame, an observer during commit fanout, or a
`snapshot()`/`restore()` called inside an action, it runs under that action's
locks, and when `reset()` or a sterile `restore()` re-runs it, it always runs
under that action's locks. So keep initializers cheap and never make one wait
for another thread's store work. It may read other states (materializing them in turn),
and it sees their committed values only — never the pending writes of an
action on its thread, not even the one that needed the state: the initial
value is committed at once and survives that action's rollback (§9.7). An
initializer that `reset()` re-runs is the exception: it reads its store's
declared states at their reset values, and its result is staged into the
reset's transaction and rolls back with it (§16.1). So is one that a sterile
`restore()` re-runs: it reads the other `Remote` states at their reset values
and its store's other declared states at the values the restore's transaction
holds for them (restored, or an enclosing action's pending writes), and its
result rolls back with that transaction (§16.4). It may not write:
`mutate`/`update`, `action`, `atomic`, `reset()` and `emit` inside an
initializer throw `IllegalStateException`, and so does an initializer that
needs its own state, directly or through other initializers (a cycle). A
throwing initializer leaves the state unmaterialized, and the next read runs
it again. Each name is declared once per store — a second property with the
same name, such as a subclass redeclaring a base class's state, fails when the
store is constructed — unless the same declaration runs again (a local
delegated property in a function called twice, or a member property of a
helper class instantiated twice over the store), which binds to the existing
state; the first declaration's initializer and transformer win.

A custom delegate that wraps `state(…)` (as `:holdfast-hallmark`'s
`boxedHandle` does) must also forward
`operator fun provideDelegate(thisRef: Any?, property: KProperty<*>)` to the
wrapped delegate and serve reads from the delegate that call returns. A
wrapper that forwards only `getValue` declares its state on its first read,
so a `snapshot()` taken earlier silently leaves the state out, and restoring
that name into a fresh store fails.

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

**Returns** `TransactionResult` (sealed: `Success | Error`). A failure inside
the body, a middleware hook or the commit is captured into `Error`, not
thrown. `action` itself throws `IllegalStateException` when called on a
disposed store or from inside a state initializer (§4.1) or a schema
migration (`SchemaVersioned.migrate`, §16.3), and inside an `atomic(...)`
frame it can throw the frame's contract exceptions or escalate an inner error
(§15.1–15.2).

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
(read-your-own-writes) — except inside a state initializer or a schema
migration (`SchemaVersioned.migrate`, run by a `restore` called in the
action), which read committed values only, or, in an initializer `reset()`
re-runs, its store's reset values; in an initializer a sterile `restore()`
re-runs, the `Remote` states' reset values and its store's other declared
states at the values the restore's transaction holds for them (restored, or
an enclosing action's pending writes) (§4.1/§16.1/§16.3/§16.4). Reads on
other threads still see the committed value, never the pending one.

**Outside any transaction (or on a non-owner thread)**: synthesizes a
one-shot `action { this@mutate mutate that }`. Middleware fires; observers
see only the committed value. This means standalone `holdfast { x mutate v }`
is equivalent to `holdfast action { x mutate v }` — never a "raw" write that
skips observers, middleware, or commit semantics — except while a
`suspendAction`/`suspendAtomic` holds the store: then a bare write from any
thread stages into (or, once it has applied, is refused by) that transaction;
see §8.2.

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

**An effect must not write back into the store that is notifying it.**
Commit fanout runs after the transaction has applied its writes, but while it
is still the store's active transaction, so anything staged into it then could
never commit. `mutate`, `update` and `emit` on that store throw an
`IllegalStateException` naming the store and state, which reaches
`uncaughtObserverHandler` (below). A nested `action` or `atomic` on it returns
`TransactionResult.Error` without running its body: check that result (e.g.
`.getOrThrow()`, which rethrows into the handler), or the write is dropped
without a log line. Write in the action itself, derive the value
(`computed { }`, `derived(...)`), or run a follow-up action once the commit has
finished: as `store action { … }` on another thread, which waits for the store,
or launched on a dispatcher that does not run it inline, checking the result —
`store.scope.launch(Dispatchers.Default) { store action { … }.getOrThrow() }`.
Mind the dispatcher: on `Dispatchers.Unconfined`, or on
`Dispatchers.Main.immediate` when the commit already runs on the main thread
(a store bound to `viewModelScope`, say), `launch` runs its body at once,
inside this commit's fanout, where the action is refused. The launch alone
drops that `Error`; `.getOrThrow()` turns it into a coroutine failure that
reaches the scope's exception handler. (Use `action` there, not a bare
`mutate`: while a `suspendAction` holds the store, another thread's bare
`mutate` is refused too — see §8.2.) Writing to another store whose
transaction is not committing still works.

**A throwing effect never undoes the commit, and never stops the other effects
unless the handler itself throws** (which ends that commit's fanout and makes
the action return an `Error`). Its exception goes to the store's
`uncaughtObserverHandler` — the same place throwing bridge publishes and failed
`derived` recomputes go. With no handler set, it is logged: a line naming the
store, then the stack trace, on standard error (JVM/Android) or standard output
(iOS/wasmJs). Set `store.uncaughtObserverHandler = { e -> crashReporter.log(e) }`
at app init to route these failures yourself, or `{ }` to silence them. The
initial fire on subscribe is different: it runs inside `effect` and throws to
its caller.

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
   action sees its own pending writes (except inside a state initializer or
   a schema migration, which read committed values only, or, in an
   initializer `reset()` re-runs, its store's reset values; in an
   initializer a sterile `restore()` re-runs, the `Remote` states' reset
   values and its store's other declared states at the values the restore's
   transaction holds for them, restored or an enclosing action's pending
   writes — §4.1/§16.1/§16.3/§16.4). Other threads see committed values
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
   an effect callback on the       write that same store      NOT from the effect:
   store it observes (commit                                  action { … } returns
   fire)                                                      Error without running
                                                              (its commit has applied
                                                              but is still fanning
                                                              out — §4.4). Write in
                                                              the action itself, use
                                                              computed/derived, or
                                                              launch a follow-up
                                                              action (§4.4)
   an effect callback              atomic write to ANOTHER    other action { … }
                                   store                      (top-level; waits for
                                                              that store)
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
| Can mutate state | other stores, via action; not its own store during its commit fire (returns Error — see §4.4) | yes (via observe→applyFromBridge) | yes (next() runs body, can wrap with logic) |
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

Foreign thread while a `suspendAction`/`suspendAtomic` holds the store: a bare
`mutate`/`update` is **not** wrapped in its own action. The suspending body may
resume on any thread, so every thread's bare write stages into its
transaction: before that transaction applies, the write silently joins it;
once it has applied, the write throws until the suspending call returns. From
other threads, write through `store action { … }`, which waits for the store.

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
| Stored after `reset()` (§16.1, experimental) | `initial`, re-computed | `initial`, re-computed (transformer NOT applied, as at construction) |

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
and `derived(sources) { … }` (push-recomputed); see §14.2. Of the patterns
below, the first computes on read and the second writes the value in the
same commit as the source, so both always agree with it; the third uses the
built-in `derived`, which recomputes right after the commit:

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

**Auto-recomputed derived:** let `derived` (§14.2) recompute it after each
commit that changes a source:

```kotlin
class CartStore : Store<CartStore>() {
    val items by state { emptyList<Line>() }
    private val totalAndSub = derived(items) { items.value.sumOf { it.price * it.qty } }
    val total: State<Money> get() = totalAndSub.first
}
```

Don't hand-roll this with an `effect` that opens an `action` on its own
store: that action would run inside the source commit's fanout, where it
returns `TransactionResult.Error` without running (§4.4) — and an effect that
ignores the result drops the write without a trace. `derived` recomputes
after the commit's fanout instead, so its value can briefly lag the source;
prefer pattern two when the total must change in the same commit.

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
the last committed value until this action commits. A state initializer
(§4.1) and a schema migration (§16.3) are the exceptions on the action's own
thread. An initializer that runs inside the action, because the action is the
first to need its state, and a `migrate` that a `restore` called in the action
runs, both read committed values, not the action's pending writes. (An
initializer that `reset()` re-runs reads its store's reset values instead,
§16.1. One that a sterile `restore()` re-runs reads its store's other declared
states as the restore's transaction holds them: restored, or this action's
pending writes, §16.4.)

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
| `holdfast.state(…)` (declaration, at construction) | `propertiesLock` | yes | Registers the name; runs no user code |
| First read of a state (materialization) | per-state initializer latch | no — re-entering it is an initializer cycle, which throws | The initializer runs outside `propertiesLock`, holding its own latch plus whatever the reading thread already holds (the `transactionLock` when first needed inside an action or `atomic(...)` frame (every participant's `transactionLock`), an observer during commit fanout, or a `snapshot()`/`restore()` called inside an action, and `middlewareLock` too from an action body); `propertiesLock` is taken briefly to publish. After that, no lock for the delegate |
| `MutableState.value` read | `stateLock` (per state) | yes | Plus optional pending-write peek if owner thread |
| `MutableState.observe / dispose` | `observersLock` (per state) | yes | Snapshot then fire — observer callback NOT under lock |
| `MutableState.bridge =` | `bridgeLock` (per state) | yes | Calls `observe` on the bridge inside |

### 10.2 Lock ordering

The library acquires locks in this consistent global order; respect it
when extending:

```
transactionLock  →  middlewareLock  →  initializer latch  →  propertiesLock  →  bridgeLock  →  stateLock  →  observersLock
```

Of these, only adjacent acquisitions actually nest in practice; the
critical AB-BA candidate fixed in earlier work was `stateLock ↔
observersLock`, which `applyCommitted` now resolves by snapshotting under
`stateLock`, releasing, then notifying under `observersLock`.

An initializer latch (§4.1) is held while a state's initializer runs, and an
action body that reads a never-read state waits for it under the action's
locks. That is why an initializer may only read: the store refuses every
write, action and frame from inside one, so it never needs a lock to its
left. Initializers waiting for each other's latches in a cycle — on one
thread or across threads — are detected and throw instead of deadlocking.

### 10.3 Thread confinement of a transaction

A `Transaction` records its `ownerThreadId` at construction. `mutate`
checks `txn.ownerThreadId == currentThreadId()` before buffering. A
mutate from a non-owner thread skips the pending path entirely and
synthesizes its own one-shot transaction (which serializes through
`transactionLock`). Exception: while a `suspendAction`/`suspendAtomic` holds
the store, the owner check is relaxed to every thread, so a non-owner bare
`mutate` stages into the suspending transaction and throws once it has
applied (§8.2). Use `store action { … }` from other threads.

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
| `IllegalStateException: State must be created by this Store instance` | Mutating a state owned by a different store | The state belongs to a different store — pass the state declared on the store you're acting on |
| `IllegalStateException: Cannot write S.x: S's transaction '…' has already been rolled back (status: RolledBack) …` (or `… has already applied its writes (status: Committed) …`) | Mutating after manually calling `rollback()` (or `commit()`) on the active transaction inside the action body; or writing into an `atomic` participant from a middleware's `onTransactionError` or a `FrameObserver` while the frame unwinds | Let `action` manage commit/rollback; start a new `store action { … }` for further writes |
| `IllegalStateException: Cannot write S.x: S's transaction '…' has already applied its writes …` (or `emit an event on S`, or an `Error` from a nested `action`/`atomic`) | An effect/observer writes back into the store whose commit is notifying it; the write could never commit | Write in the action itself, derive the value (`computed`/`derived`), or run a follow-up action after the commit (as `store action { … }` on another thread, or launched on a dispatching scope with `.getOrThrow()`) — see §4.4 |
| `IllegalStateException: Cannot write S.x: a suspendAction or suspendAtomic holds S …` | A bare `mutate`/`update` from another thread while a `suspendAction`/`suspendAtomic` on S is committing | Write through `S action { … }`, which waits for the store — see §8.2 |
| `Holdfast: a post-commit side effect of S failed …` on standard error (JVM/Android) or standard output (iOS/wasmJs) | An effect, bridge publish or `derived` recompute threw after its commit; with no `uncaughtObserverHandler` set, the failure is logged | Fix the thrower, or set `uncaughtObserverHandler` to route (or `{ }` to silence) these failures |
| `IllegalStateException: Cannot write S.x: the initializer of S.y is running on this thread …` (or `open an action on S`, `open an atomic(...) frame`, `reset S`, `emit an event on S`) | A state initializer writes, or opens an action or frame; initializers run whenever the state is first needed — including inside `snapshot()`, and again inside `reset()` | Compute the initial value from what the initializer can read; make the write in an action once the store exists — see §4.1 |
| `IllegalStateException: State initializer cycle: S.x → S.y → S.x` (or `… cycle across threads …`) | Initializers that need each other's states | Give one state of the cycle an initial value that does not read the others; compute the rest from it |
| `IllegalStateException: S already declares a state named 'x' …` when constructing a store | Two properties with one name on one store — typically a subclass redeclaring a base class's state | Give one of them another name |
| A custom delegate's state is missing from `snapshot()` (and `restore()` into a fresh store silently leaves it at its initial value: the one-argument `restore` ignores names the store has not declared) | The delegate wraps `state(…)` but forwards only `getValue`, so the state is declared on first read instead of at construction | Forward `provideDelegate(thisRef, property)` to the wrapped delegate — see §4.1 |
| After an app update, a renamed codec state starts at its initial value (a `RestoreReport` lists its old name as `UnknownState`; `RestorePolicy.Strict` fails) | The rename shipped without a new schema version, so nothing moved the old name's text | Implement `SchemaVersioned`, raise `schemaVersion`, and `view.rename(old, new)` in `migrate` — see §16.3 |
| `SnapshotMigrationException: Cannot restore a snapshot of schema version N into S, whose schema version is M …` | Text a newer release of S wrote, restored by an older one; a captured snapshot of a store of another schema; or S's `migrate` threw | A store cannot read a later schema of itself; for a captured snapshot, restore `StoreSnapshot.decode(snapshot.encode())`; fix a throwing `migrate` — see §16.3 |
| `IllegalStateException: store disposed` | Calling any state API after `dispose()` | `dispose()` is terminal — create a new store instance, or don't dispose a store still in use |
| `IllegalStateException: emit(event) called outside of an action / suspendAction` | `EventfulStore.emit` outside a transaction | Emit only inside `action { }` / `suspendAction { }` so rollback can discard staged events |
| Bridge keeps publishing forever in a loop | Bridge's `publish` calls into a system that re-publishes back and the bridge does not dedupe | Have the bridge dedupe (compare to last-published) before notifying observers |
| Effect callbacks leak after a Composable disappears | `Disposable` not captured | Use `DisposableEffect` and call `.dispose()` in `onDispose` |
| Nested action's commit "doesn't seem to do anything" | Inner committed — but it's a savepoint; outer still owns the pending writes | This is correct. Inner's commit merged into outer; outer's commit/rollback is what the world sees |

---

## 13. API Reference

### `Store<Self>`

| Member | Signature | Description |
|---|---|---|
| `state` | `fun <T : Any> state(transformer: Transformer<T>? = null, distinct: Boolean = false, initialize: Initializer<T>): StateDelegate<T>` | Declares a state property when the store is constructed; `initialize` runs on first need (§4.1); `distinct=true` opts into same-value commit dedup |
| `state` (with a codec or tags) | `fun <T : Any> state(transformer: Transformer<T>? = null, distinct: Boolean = false, codec: StateCodec<T>? = null, tags: Set<StateTag> = emptySet(), initialize: Initializer<T>): StateDelegate<T>` *(experimental)* | The stable `state` plus a `StateCodec` that `snapshot().encode()` writes the state's raw value with (§16.2) and `StateTag`s the library enforces — `Secret`, `UserAuthored`, `Remote` (§16.4); a call that passes neither resolves to the stable overload and needs no opt-in; throws on a disposed store, and a refused tag combination fails the declaration with `IllegalArgumentException` |
| `action` | `infix fun <R> action(body: Self.() -> R): TransactionResult<R>` | Runs body in a transaction; body's return value carried in `Success<R>` |
| `invoke` | `operator fun <R> invoke(block: Self.() -> R): R` | Plain context block |
| `middlewares` | `fun middlewares(vararg middleware: Middleware<Self>)` | Registers middleware (LAST argument is outermost) |
| `clearMiddleware` | `fun clearMiddleware()` | Removes all registered middleware |
| `activeTransaction` | `val activeTransaction: Transaction?` | Volatile read of in-flight transaction |
| `uncaughtObserverHandler` | `var uncaughtObserverHandler: ((Throwable) -> Unit)?` | Handler for post-commit failures: observer callbacks (including a write back into this store during its own commit fanout), fanout `Transformer.get`, `Bridge.publish` / `SuspendingBridge.publishAwaited`, `derived` recomputes. Default null = logged to standard error (JVM/Android) or standard output (iOS/wasmJs), naming the store; `{ }` silences. Observer/`Transformer.get`/bridge failures are reported on the committing thread inside the fanout, where a throwing handler ends the fanout and fails the action; `derived` failures are reported after the recompute releases the store, where a throwing handler fails no action |
| `lockOrderKey` | `val lockOrderKey: Long` *(opt-in)* | Process-monotonic ordering key used by `atomic(...)` for deadlock-safe lock acquisition |
| `scope` | `open val scope: CoroutineScope` | Scope for the store's async work; resolution order: per-call parameter → subclass override → `bindToScope` binding → `Store.defaultScope` |
| `bindToScope` | `fun bindToScope(scope: CoroutineScope)` | Binds the store to a scope (level 3 of the resolution chain); rebindable, never cancels the previous or new scope |
| `clock` | `open val clock: Clock` *(experimental — `@ExperimentalStoreApi`)* | The `kotlin.time.Clock` store code reads time through; resolution order: subclass getter override → `bindClock` binding → `Clock.System`. Initializers read it lazily, when a state is first needed (its first read, or `snapshot()`/`restore()`), and again at every `reset()` (§16.1), and a `Remote` state's at every sterile `restore()` (§16.4). Library timestamps (`Transaction.endTime`, timing middleware) don't use it |
| `bindClock` | `fun bindClock(clock: Clock?)` *(experimental)* | Binds a clock (level 2), e.g. a fixed test clock; `null` unbinds. Throws on a disposed store. `storeTest` restores each tracked store's binding to its value at first `track` (`store.action {}` doesn't auto-track), so bind after tracking |
| `reset` | `fun <V : Store<V>> V.reset(): TransactionResult<Unit>` *(experimental, extension)* | Puts every declared state back to its initializer's value in one transaction: initializers re-run (reading each other's reset values), results staged raw, only changed states staged and fired; a throwing initializer rolls it all back (§16.1) |
| `restore` (with a policy) | `fun <V : Store<V>> V.restore(snapshot: StoreSnapshot, policy: RestorePolicy, sterile: Boolean = false): TransactionResult<RestoreReport>` *(experimental, extension)* | `restore` (§14.1) under `Strict`, `IgnoreUnknown` or `BestEffort`, reporting the restored states, the declared states the snapshot holds no value for, and each skipped entry; a rejected restore changes nothing (§16.2). A snapshot of another schema version is migrated first, or refused (§16.3). `sterile = true` drops the snapshot's `Remote` entries and resets every `Remote` state to its initial value in the same transaction (§16.4) |
| `snapshot` (with a scope) | `fun <V : Store<V>> V.snapshot(scope: SnapshotScope): StoreSnapshot` *(experimental, extension)* | `All` is `snapshot()`; `UserAuthored` captures only the `UserAuthored` states; `Raw` lets typed reads return a `Secret` state's plaintext, in memory only (§16.4) |
| `taggedStates` / `tags` | `fun Store<*>.taggedStates(tag: StateTag): List<State<*>>`; `val State<*>.tags: Set<StateTag>` *(experimental, extensions)* | The one tag lookup: a state's tags (a `derived` with a `Secret` source is `Secret`), and the store's states carrying a tag in declaration order, never-read ones materialized first; `taggedStates` throws on a disposed store, `tags` keeps answering (§16.4) |
| `schemaVersion` / `migrate` | `interface SchemaVersioned { val schemaVersion: Int; fun migrate(from: Int, view: EncodedSnapshotView) }` *(experimental; a store subclass implements it)* | Numbers the store's schema (a store without it is version 1) and upcasts an older decoded snapshot's encoded text before a restore reads it; a newer snapshot, a captured one of another version, or a throwing `migrate` fails the restore with `SnapshotMigrationException`, changing nothing. `migrate` may read states but not write any store (§16.3) |
| `dispose` | `fun dispose()` | Terminal, idempotent teardown — drops observers, detaches bridges, clears middleware; subsequent state APIs throw `IllegalStateException("store disposed")` |
| `isDisposed` | `val isDisposed: Boolean` | Whether `dispose()` has been called |
| `properties` | `val properties: Map<String, State<*>>` | Snapshot of the materialized states (a never-read state is absent until something needs it) |
| `getState` / `hasState` / `removeState` / `clearStates` | … | Reflection over the materialized states; `removeState`/`clearStates` dispose observers + bridge silently and keep the declaration, so the next read (or `snapshot()`/`restore()`/`reset()`) recreates the state from its initializer (derived backing and internal states have no initializer and go with their declarations). Both throw `IllegalStateException` for a state with a pending write in the active transaction or one enclosing it, or held by an open `reset()` (§16.1) or sterile `restore()` (§16.4) |

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
| `value` | `override val value: T` | Post-`get` view; read-your-own-writes for owner thread (except inside a state initializer or a schema migration (`SchemaVersioned.migrate`), which read committed values only, or, in an initializer re-run by `reset()`, its store's reset values; in an initializer re-run by a sterile `restore()`, the `Remote` states' reset values and its store's other declared states at the values the restore's transaction holds for them, restored or an enclosing action's pending writes — §4.1/§9.7/§16.1/§16.3/§16.4) |
| `observe` | `fun observe(observer: (T) -> Unit): Disposable` | Subscribe with initial fire |
| `bridge` | `var bridge: Bridge<T>?` | Get/set the bridge; setting installs an observer on it |
| `toString` | `override fun toString(): String` | Names the state (`MutableState(CounterStore.count)`), never its value (§16.4) |

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
class StoreSnapshot internal constructor(…) {
    val stateNames: Set<String>   // the states its scope captured (every declared state for snapshot()); not the backing states of derived
    val size: Int
    // equals/hashCode compare values; toString lists state names only.
    // Experimental (§16.2): schemaVersion, unencodableStateNames, encode, entry, get,
    // render, and StoreSnapshot.decode(text).
}

fun <V : Store<V>> V.snapshot(): StoreSnapshot
fun <V : Store<V>> V.snapshot(scope: SnapshotScope): StoreSnapshot   // experimental, §16.4
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot): TransactionResult<Unit>   // RestorePolicy.IgnoreUnknown
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot, policy: RestorePolicy, sterile: Boolean = false): TransactionResult<RestoreReport>   // experimental, §16.2/§16.4
```

`snapshot` captures the raw stored value of every declared state (see §4.1
for delegates that wrap `state(…)`) — a state nobody has read yet is
materialized first (its initializer runs), so even an untouched store's
snapshot is complete. The values form one consistent cut: a commit applying
on another thread meanwhile is either wholly in the snapshot or not in it,
and the snapshot never makes that writer wait. Taken inside an action, a
snapshot holds committed values, not that action's pending writes —
including for a state it materializes there, whose initializer reads
committed values only (§4.1). The backing states of
`derived`/`suspendDerived` are captured too, but are not in `stateNames`:
`restore` writes them back only into the store instance that took the
snapshot, and skips one that `removeState`/`clearStates` has dropped since.
`restore` writes the values back inside one `action` (called inside another
action, it is a savepoint: its writes commit, or roll back, with the
enclosing action), bypassing `transformer.set` so asymmetric transformers
(encryption, JSON codecs) round-trip losslessly; a declared target that is
not materialized yet is materialized first, before the `action` opens (at top
level that takes no lock of the store).

```kotlin
val snap = holdfast.snapshot()
holdfast action { count mutate 9999; label mutate "wrong" }
holdfast.restore(snap)               // count + label back to snapshot values
```

Restore-time bridge publish: yes. Detach bridges first if the snapshot
shouldn't echo back to your persistence layer. A state name the store does not
declare is ignored, and a declared state the snapshot holds no value for keeps
its value without its observers firing (`RestorePolicy.IgnoreUnknown`). A value
the target state cannot hold — a snapshot of another store class with a
`String` where this store's state holds an `Int` — fails the restore with
`TransactionResult.Error`, and nothing changes. Snapshots compare by value:
two snapshots at the same schema version (§16.3) holding the same states with
equal raw values are `==`, whichever store instances took them. To pick
another restore policy, turn a snapshot into text and back, or read one
state's value from it, see the experimental §16.2.

To go back to the initial values rather than to a captured snapshot, use the
experimental `reset()` (§16.1). To keep a state's value out of encoded text,
renders and logs, or to capture only what the user wrote, see the
experimental state tags (§16.4).

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
  after each commit that touches a source, runs `compute()` once — however
  many of its same-store sources that commit changed — inside a fresh
  top-level action on the same store and stages the result in a backing
  `MutableState`.
  The returned `State<T>` is a real observable state — use `effect` to
  subscribe.

The recompute is deferred via `Store.postCommit` (an internal queue) so
it doesn't re-enter the parent's `pendingWrites` map mid-iteration. It
never waits for its own store. For sources on the same store, it runs
after the triggering commit's fanout, once the locks are released. For a
source on another store while the derived's store is idle, it runs inline
inside the source's observer fanout, under the source store's lock — so a
slow `compute` lengthens that commit — and once per changed source, since
there is no transaction on the derived's store to coalesce behind. If the
derived's own store is busy (another action, frame or `suspendAction`
holds it), the recompute is handed to that holder and runs on the
holder's thread when it releases — so a derived can briefly lag its
sources, even on the same store when another holder takes the store right
after the commit, then converges. Read the sources, or use `computed`,
when you need the caller's own write. A throwing `compute` rolls that
recompute back and is reported through `uncaughtObserverHandler`
(logged while no handler is set); the next source commit
recomputes normally. Disposing the `Disposable` stops recomputation,
including a recompute that is already queued.

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
Encryption is not redaction: observers, `effect`, `derived` states and
typed snapshot reads see plaintext, and an encoded snapshot holds the
ciphertext. To keep the value out of encoded snapshots, renders, test
timelines and middleware output, also tag the state `StateTag.Secret`
(experimental, §16.4). An initializer's result is stored without `set`,
like every initial value, so start from a value the cipher decrypts to
itself (the empty string, for `XorCipher`) or from ciphertext.

`XorCipher` is **NOT production-grade** — it's a KMP-pure stand-in.
Production users implement `Cipher` over `javax.crypto` (JVM) or
CryptoKit (iOS) — typically AES-GCM with a per-state IV embedded in
the encoded output.

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

interface Codec<T : Any> : StateCodec<T> {       // every Codec is a StateCodec (§16.2)
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
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable>

interface SuspendingKvStore                          // suspend get / put / remove / snapshot
interface SuspendingBridge<T : Any> : Bridge<T>      // suspend fun publishAwaited(value: T)
fun <T : Any> SuspendingKvStore.bridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge<T>
fun <T : Any> SuspendingKvStore.suspendingBridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge.Awaiting<T>
```

`suspendAction` allows the body to suspend (`delay`, `await`, `withContext`).
Mutually exclusive with blocking `Store.action` on the same store via an
internal coroutine `Mutex` installed lazily. Cancellation of the body
rolls back the transaction; commit phase wraps in `NonCancellable` so
observer/bridge fanout completes cleanly even if the surrounding scope
cancels mid-commit.

`Middleware<V>` sync hooks fire on `suspendAction` as well as `action`
(2.0; was a documented no-op limitation in 1.1). Concentric-ring ordering:
`onTransactionStarted` runs in chain order before the body, `onTransactionCompleted`
or `onTransactionError` runs in reverse chain order around the body. Each hook
invocation is wrapped in `runCatching` — a throw from one middleware's hook does
not abort other middlewares' hooks. Behavior change: middleware authors who
relied on "suspendAction won't trigger me" must verify their hooks are idempotent
under the suspending path.

Limitations: while the `suspendAction` holds the store, a bare
`mutate`/`update` from ANY thread (including threads the body spawns) stages
into its transaction. Before the apply pass it silently joins it; after, it
throws until `suspendAction` returns (§8.2). Other threads should write
through `store action { … }`, which waits.

### 14.9 `:holdfast-compose`

```kotlin
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(make: () -> Disposable): Disposable
```

Bridges holdfast state into Compose's snapshot system as a
`androidx.compose.runtime.State<T>`, triggering recomposition on every
successful commit. Backed by `produceState`; subscription's lifecycle is
tied to the surrounding Composable.

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
| `com.vynatix:holdfast-hallmark` *(this repo)* | Holdfast adapter. `ValidatingTransformer`, `Store.boxed { }` / `boxedHandle { }` factories (plus experimental `codec`/`tags` overloads, §16.4), `BoxedCodec`. |

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

State factories:

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
  is a `BoxedHandle<P, O>` bundling state + validator. Enables the
  `assign` infix (powered by Kotlin context parameters) for one-line
  civilize-and-mutate at the call site.
- **`boxed(validator, codec = …, tags = …) { initial }` /
  `boxedHandle(validator, codec = …, tags = …) { initial }`**
  *(experimental, `@ExperimentalStoreApi`)* — the same factories, declared
  through the experimental `state(transformer, distinct, codec, tags,
  initialize)` overload. `codec` is the snapshot codec `snapshot().encode()`
  writes the boxed value with; wrap a primitive codec, e.g.
  `BoxedCodec(LongCodec, PinValidator)` (§16.2). `tags` are the state's
  `StateTag`s (§16.4). A call that passes neither resolves to the stable
  factory and needs no opt-in. For a `StateTag.Secret` state, a rejection by
  the initializer, a write, `civilize` or `assign` throws a
  `HallmarkException` whose violations keep their code, path and rule; each
  message reads "`<code>` rejected the value (withheld: a Secret state)" and
  the violations carry no arguments.
- **`ValidatingTransformer`** — re-validates on every write, so
  constructor bypass (`data class copy`) is rejected. A transformer you
  construct yourself does not know its state's tags — that includes the
  `Transformer.then` pipeline below — so hallmark's messages, which may quote
  the rejected value, reach the exception even on a Secret-tagged state.
  Declare a Secret boxed state with the `tags` overloads above, not with
  `state(transformer = ValidatingTransformer(v), tags = setOf(StateTag.Secret))`.
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
entire transaction. For a `StateTag.Secret` state (declared with the `tags`
overload above), the `HallmarkException` withholds the rejected value the
same way; any other state keeps hallmark's messages.

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
UnenrolledStoreException: SettingsStore was mutated (via action) inside
atomic(HistoryStore, BackendStatusStore) but is not enrolled. Its writes
would commit independently and would NOT roll back with the frame.
Fix: add SettingsStore to the atomic(...) participant list. …
```

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
rolls back and the frame returns `Error` carrying the inner exception. The
pre-0.3 behavior (a failed sub-action commits the other stores anyway) is
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
   fanout point. An observer may write to a participant that has not committed
   yet (`b`, while `a` fans out) — the write stages into `b`'s root and
   commits with it — but not to one that already has (`a`, while `b` fans out):
   that throws, and a nested `action`/`atomic` on it returns an `Error` the
   observer must check, exactly like a write back into a store from its own
   fanout (§4.4). `suspendAtomic` behaves the same way, with one gap: a
   blocking `action`/`atomic` on a participant that has not committed yet
   still waits for the frame's serializer forever — use `mutate` there.
6. **Rollback** (body throw, `started`/`completed` throw, or inner-error
   escalation) — REVERSE lock order, `onTransactionError` per store first,
   then rollback. Rollback never touches state and never re-runs
   `Transformer.set`.
7. **Post-commit drain** — deferred work (`derived` recomputes) runs at
   frame exit, once every participant's transaction slot is restored and
   its lock released, for each store whose root the frame opened.

`suspendAtomic` follows the same phases with the suspending machinery: the
per-store `AsyncSerializer` mutex instead of the blocking lock, commit under
`withContext(NonCancellable)`, `SuspendingBridge.publishAwaited` awaited, and
the event drain honoring `BufferOverflow.SUSPEND` back-pressure.
(One caveat: `SuspendingMiddlewareHooks` async hooks do not fire for frame
roots yet — sync hooks do.)

**Durability non-goal:** a frame is in-memory 2PC across stores in ONE
process. Bridge/persistence publishes remain per-store post-commit fanout;
there is no crash-consistency across external stores, and if a commit itself
throws partway through phase 5, already-committed stores stay committed.

### 15.4 Nesting and interop

- A frame nested inside an `action` or another frame on the same
  thread/coroutine opens SAVEPOINTS for shared stores: the nested frame's
  commit merges into the enclosing scope; the enclosing rollback discards
  everything, including nested writes. A nested frame's `Error` escalates to
  the enclosing frame like an inner action's (same `TolerateInnerErrors`
  opt-out).
- A nested frame may only INTRODUCE stores whose `lockOrderKey` sorts above
  every key the enclosing frame holds; otherwise `FrameLockOrderException`
  fires at entry, before any lock is taken. Prefer enrolling everything in
  the outermost frame.
- Blocking and suspending frames do not compose on the same stores:
  blocking `action { }` (or a nested `atomic`) on a `suspendAtomic`
  participant throws `FrameInteropException` immediately instead of
  deadlocking on the suspend mutex — use `mutate`/`update` or
  `suspendAction { }` inside a suspending body. Inside `suspendAtomic`, a
  participant's `suspendAction { }` joins the frame as a savepoint.

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

## 16. Snapshots, persistence and boot (experimental)

Issue #20 lets an app boot its stores from captured state instead of a
hand-written `seed()` function per store. The pieces land one at a time, and
each section below covers one. Everything in this chapter is
`@ExperimentalStoreApi` except `StateCodec` and what §16.2 says of the stable
one-argument `restore(snapshot)` and of `StoreSnapshot` equality (§14.1): opt
in with `@OptIn(ExperimentalStoreApi::class)`, and expect names and behavior
to change in any 0.x release.

The chapter builds on two things covered earlier. A store knows every state
it declares from the moment it is constructed, and keeps each state's
initializer for its whole lifetime (§4.1). And `snapshot()`/`restore()` move
raw stored values, never re-running `Transformer.set` (§14.1).

### 16.1 Reset

```kotlin
@ExperimentalStoreApi
fun <V : Store<V>> V.reset(): TransactionResult<Unit>
```

`reset()` puts every declared state back to what its initializer computes,
in one transaction. Afterwards every declared state holds the raw value a
newly constructed store's state holds once read. In tests,
`:holdfast-testing`'s `shouldMatchSnapshotOf` against a new store passes, and
so does `store.snapshot() == NewStore().snapshot()`: snapshots compare by
value (§16.2).

The one exception is an initializer that reads a `derived` state computed
from states this reset changes. A `derived` recomputes only after the reset
commits, so the initializer reads its pre-reset value, and its state can
differ from a new store's. In an initializer, read the derived's sources
directly or use `computed { }`, which sees the reset values.

```kotlin
class SessionStore : Store<SessionStore>() {
    val user by state { "guest" }
    val cart by state { emptyList<String>() }
    val greeting by state { "Hello, ${user.value}" }   // reads user
}

@OptIn(ExperimentalStoreApi::class)
fun signOut(session: SessionStore) {
    // Before: user = "ada", cart = [book], greeting = "Welcome back, ada".
    // After: "guest", [], "Hello, guest", as in a new SessionStore.
    session.reset().getOrThrow()
}
```

- **Initializers run again.** The reset re-runs each declared state's
  initializer inside its transaction, in declaration order, under the reset
  action's locks, so other actions on the store wait for the reset. An
  initializer that reads `clock` reads it at reset time.
- **Raw output.** The result is staged the way an initial value is stored:
  raw, without `Transformer.set`. So an `EncryptingTransformer` state is not
  encrypted a second time, and its stored ciphertext equals a new store's.
- **Only changed states fire.** A state is staged only when the result
  differs (`==`) from the value the transaction holds for it. Observers and
  bridges fire once for each state the reset changes and never for one it
  leaves alone, even with `distinct = false`. Bridges receive the reset values
  through `publish`, as with `restore()`, so detach them first if that should
  not echo.
- **Reset values inside the reset.** An initializer that reads another
  declared state of the same store reads that state's reset value. If that
  state's initializer has not run yet in this reset, it runs first, so a
  state may read one declared after it. This is the order and the values a
  new store's first reads produce. Everything else an initializer reads,
  such as another store's states or a `derived` state, it reads at the
  committed value, never at an enclosing action's or frame's pending writes.
  Initializers may not write, and a cycle fails the reset.
- **Every declared state.** A state nobody has read yet, or one that
  `removeState`/`clearStates` dropped, is materialized before the reset's
  transaction opens (its initializer runs as a first read would run it), then
  reset like the rest. Its initializer therefore runs twice. Once the reset
  has decided a state's value (staged it, or left it because it already held
  its reset value), `removeState`/`clearStates` refuse that state with an
  `IllegalStateException` until the reset's transaction, or the action or
  frame it joined, commits or rolls back. `derived` states are not reset:
  they recompute from their sources once the reset commits.
- **One transaction.** Middleware sees one transaction, with the id `Reset`.
  Called inside an action on the same store, the reset is a savepoint: it
  overrides that action's pending writes to the store's declared states and
  commits or rolls back with the action. Inside an `atomic(...)` frame that
  enrolls the store, it joins the frame the same way.
- **All or nothing.** A throwing initializer, or an initializer cycle, rolls
  the whole reset back and returns `TransactionResult.Error`. No state's
  value changes. States the reset materialized before its transaction opened
  stay materialized, as after `snapshot()`, so a state dropped with
  `removeState` is back in `properties`. An initializer that catches another
  state's failure does not exempt that state: as in a new store, its
  initializer runs again when it is next read or its turn comes, and fails
  the reset if it throws again. `reset()` throws, like `action`, on a
  disposed store, when called from inside an initializer or a schema
  migration (§16.3), inside a `suspendAtomic` body that enrolls the store
  (`FrameInteropException`, because a blocking action there would
  deadlock), and inside a frame body that does not enroll the store
  (`UnenrolledStoreException`, unless the frame's policy allows unenrolled
  writes).

### 16.2 Encoding snapshots: codecs, restore policies, typed reads

```kotlin
interface StateCodec<T : Any> {                 // stable; every bridge.Codec is one
    fun encode(value: T): String
    fun decode(string: String): T
}

// Store member: the stable state(...) plus a codec (and tags, §16.4).
@ExperimentalStoreApi
fun <T : Any> state(
    transformer: Transformer<T>? = null,
    distinct: Boolean = false,
    codec: StateCodec<T>? = null,
    tags: Set<StateTag> = emptySet(),
    initialize: Initializer<T>,
): StateDelegate<T>

class StoreSnapshot {                           // members added to §14.1's
    @ExperimentalStoreApi val schemaVersion: Int
    @ExperimentalStoreApi val unencodableStateNames: Set<String>
    @ExperimentalStoreApi fun encode(includeRemote: Boolean = false): String
    @ExperimentalStoreApi fun <T : Any> entry(state: State<T>): SnapshotEntry<T>
    @ExperimentalStoreApi operator fun <T : Any> get(state: State<T>): T?
    @ExperimentalStoreApi fun render(): String
    @ExperimentalStoreApi companion object {
        @ExperimentalStoreApi fun decode(text: String): StoreSnapshot
    }
}

sealed interface SnapshotEntry<out T : Any> {
    data class Present<out T : Any>(val value: T) : SnapshotEntry<T>
    data object Absent : SnapshotEntry<Nothing>
}
data object Redacted : SnapshotEntry<Nothing>

enum class RestorePolicy { Strict, IgnoreUnknown, BestEffort }
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot, policy: RestorePolicy, sterile: Boolean = false): TransactionResult<RestoreReport>
class RestoreReport { val restored: Set<String>; val kept: Set<String>; val issues: List<RestoreIssue>; val sterilized: Set<String> }
sealed class RestoreIssue { UnknownState, NoCodec, Undecodable, TypeMismatch }  // stateName + reason
class RestoreRejectedException : IllegalStateException { val policy; val issues }
class SnapshotFormatException : IllegalArgumentException
```

A snapshot holds raw values in memory. To write one to disk, or hand it to
another process, give its states a codec: `snapshot().encode()` turns the
snapshot into text, and `StoreSnapshot.decode(text)` turns the text back into
a snapshot that `restore` accepts, in a new store instance or after a restart.

```kotlin
@OptIn(ExperimentalStoreApi::class)
class SettingsStore : Store<SettingsStore>() {
    val theme by state(codec = StringCodec) { "light" }
    val fontSize by state(codec = IntCodec) { 14 }
    val draft by state { "" }        // no codec: captured in memory, never encoded
}

@OptIn(ExperimentalStoreApi::class)
fun moveSettings(from: SettingsStore, to: SettingsStore): RestoreReport {
    // Say `from` holds theme = "dark", fontSize = 16, draft = "unsent".
    val snapshot = from.snapshot()
    println("font size ${snapshot[from.fontSize]}")   // typed read (an Int?): "font size 16"
    val text = snapshot.encode()
    // {"format":"holdfast.store","v":1,"schema":1,
    //  "states":{"fontSize":"16","theme":"dark"},"skipped":["draft"]}
    return to.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
    // restored = [fontSize, theme], kept = [draft]: `to` keeps its own draft.
}
```

- **Codecs.** `StateCodec<T>` turns a value into text and back. It is stable,
  although the rest of this chapter is experimental: `bridge.Codec` extends
  it, so `StringCodec`, `IntCodec`, `LongCodec`, `BooleanCodec` and your own
  `KvBridge` codecs work unchanged. A codec sees the state's raw stored value,
  after `Transformer.set`: an `EncryptingTransformer` state is encoded as its
  ciphertext, and restoring that ciphertext does not encrypt it again.
- **Declaring.** `state(codec = …) { … }` is an experimental overload of
  `state`, which also takes `tags` (§16.4). A call that passes neither a
  codec nor tags, such as `state { … }` or `state(transformer = t) { … }`,
  still resolves to the stable overload and needs no opt-in.
- **Encoding.** `encode()` writes version 1 of the store format:
  `{"format":"holdfast.store","v":1,"schema":1,"states":{…},"skipped":[…]}`.
  `states` maps each state with a codec to its codec text. `skipped` names the
  states without one, which are also listed in `unencodableStateNames`: their
  values stay out of the text. The text is canonical: states sorted by name,
  no whitespace, one fixed escaping (an unpaired surrogate is written as its
  `\u` escape, so the text is valid Unicode). A snapshot therefore always
  encodes to the same text. Equality ignores codecs, though: equal snapshots
  from stores whose states declare different codecs can encode differently.
  `derived` states are never encoded; they recompute
  from their sources. `schema` is the store's schema version, which
  `schemaVersion` reads back: 1 unless the store implements
  `SchemaVersioned` (§16.3).
  A `Secret` state is written as `null`, and a `Remote` one is left out
  unless you pass `includeRemote = true` (§16.4).
- **Decoding.** `decode` throws `SnapshotFormatException` for text it cannot
  read. The message names the problem, a character offset and possibly a
  state name, but never quotes a state's value, and the exception has no
  cause. Fields the reader does not
  know are skipped, so text from a later Holdfast that adds fields still
  reads. Containers nested more than 64 levels deep are rejected without
  deep recursion.
- **Restoring.** The one-argument `restore(snapshot)` uses
  `RestorePolicy.IgnoreUnknown`. The experimental `restore(snapshot, policy)`
  lets you choose, and returns a `RestoreReport`. An entry the restore cannot
  apply is a `RestoreIssue`: a name the store does not declare
  (`UnknownState`), text for a state without a codec (`NoCodec`), text the
  codec rejects (`Undecodable`), or a value of the wrong class
  (`TypeMismatch`). `Strict` fails on any issue, `IgnoreUnknown` only on
  issues other than `UnknownState`, and `BestEffort` skips every bad entry and
  reports it. A failed restore returns `TransactionResult.Error` carrying a
  `RestoreRejectedException` that names each state, and nothing changes.
  Inside an `atomic(...)` frame, that error aborts the whole frame. Under
  every policy, a declared state the snapshot holds no value for keeps its
  value, and its observers do not fire. A snapshot of another schema version
  than the store's is migrated first, or refused (§16.3).
- **Order of work.** A restore runs the user code its plan needs before its
  action opens, so at top level it holds no lock of the store: the store's
  `migrate` for an older decoded snapshot (§16.3), initializers of never-read
  target states, and the codecs decoding a decoded snapshot's text. The
  action then stages the raw values in one transaction, whose id is
  `Restore`; middleware, observers and bridges run in it, as for any action.
  (A target state that a concurrent `removeState`/`clearStates` drops after
  that is materialized again inside the action, under its locks; an internal
  state dropped that way fails the restore.)
- **The type witness.** A state's declared type is erased at runtime, so a
  captured value is checked against the class of the value the state holds.
  The same class always fits. A different class is rejected only when either
  class is a built-in value type (`String`, `Boolean`, `Char` or a primitive
  number), so subclasses, sealed siblings and different `List`
  implementations always pass. As a result, a state declared as `Any`,
  `Number` or `Comparable` can be refused a value of another built-in type.
  No check runs for a snapshot taken by an instance of the same store class
  (or a superclass), or for decoded values, which the state's own codec
  produced. The skip trusts the class, not its type arguments: for a generic
  store class (`class Box<T : Any> : Store<Box<T>>`), a `Box<Int>` snapshot
  restores unchecked into a `Box<String>`, and the wrong value surfaces later
  as a `ClassCastException` where the state is read. The same holds for
  states whose declarations differ between instances of one class, such as
  function-local delegated properties. Restore such snapshots only into
  instances with the same type arguments.
- **Typed reads.** `snapshot[state]` returns the value the snapshot holds for
  `state`, typed by the state. It returns the `Transformer.get` view, so an
  encrypted state reads as plaintext. `entry(state)` distinguishes
  `Present(value)` from `Absent` and `Redacted` (a value withheld: `null`
  in the text, or a `Secret` state's value outside a `SnapshotScope.Raw`
  capture, §16.4). A captured snapshot answers the states of the store instance that
  took it, and throws `IllegalArgumentException` for another instance's
  state. A decoded snapshot answers any store's state by name, decoding the
  text with that state's codec. It reads the text as written, without a
  schema check or `migrate` (§16.3). It throws `SnapshotFormatException`
  when the codec cannot decode the text, or when the snapshot holds a keyed
  state family, not a single value, under the state's name. Both keep
  working after the store is disposed.
- **Equality.** Snapshots compare by value. Two captured snapshots are equal
  when they are at the same schema version (`schemaVersion`, §16.3) and hold
  the same state names with `==` raw values, whichever instances took them
  and whatever codecs their states declare. Two decoded
  snapshots are equal when they hold the same text. A captured snapshot never
  equals a decoded one: compare their `encode()` output instead. That output
  survives the round trip exactly:
  `StoreSnapshot.decode(s.encode()).encode() == s.encode()`. `toString()`
  lists state names only. `render()` shows the stored values, for debugging,
  except a `Secret` state's (§16.4).
- **Values stay out of failures.** No exception these APIs throw carries a
  state's value in its message or cause chain. A codec exception is reported
  by its class name only, because its message may quote the text it failed on.

A `kotlinx.serialization` type needs no plugin to be a codec. Wrap its
`KSerializer`:

```kotlin
class KSerializerCodec<T : Any>(
    private val serializer: KSerializer<T>,
    private val json: Json = Json,
) : StateCodec<T> {
    override fun encode(value: T): String = json.encodeToString(serializer, value)
    override fun decode(string: String): T = json.decodeFromString(serializer, string)
}

@OptIn(ExperimentalStoreApi::class)
class InboxStore : Store<InboxStore>() {
    val pinned by state(codec = KSerializerCodec(ListSerializer(String.serializer()))) {
        emptyList<String>()
    }
}
```

The recipe needs only the `kotlinx-serialization-json` runtime. A class
annotated `@Serializable` needs the serialization compiler plugin to generate
its `serializer()`, and then works the same way.

### 16.3 Schema versions and migration

```kotlin
@ExperimentalStoreApi
interface SchemaVersioned {                     // implemented by a Store subclass
    val schemaVersion: Int                      // at least 1; a store without SchemaVersioned is at 1
    fun migrate(from: Int, view: EncodedSnapshotView)
}

@ExperimentalStoreApi
class EncodedSnapshotView {                     // a copy of the snapshot's text, valid while migrate runs
    val stateNames: Set<String>
    val families: Families                      // keyed state families, by name (read-only for now)
    operator fun contains(name: String): Boolean
    operator fun get(name: String): String?     // codec text; null when withheld or absent
    fun put(name: String, text: String?)        // null withholds the value
    fun remove(name: String): Boolean
    fun rename(from: String, to: String): Boolean
    class Families { val names: Set<String> }
}

@ExperimentalStoreApi
class SnapshotMigrationException : IllegalStateException {
    val snapshotVersion: Int
    val storeVersion: Int
}
```

Text that one release of an app saved has to restore in the next, even
after states were renamed or their codecs changed. A store whose schema
changes implements `SchemaVersioned`: it numbers its schema, and its
`migrate` upcasts an older snapshot's encoded text before the restore reads
it.

```kotlin
// Schema 1, the first release, saved:
// {"format":"holdfast.store","v":1,"schema":1,"states":{"fontSize":"16","theme":"dark"},"skipped":[]}
@OptIn(ExperimentalStoreApi::class)
class ReaderSettings : Store<ReaderSettings>(), SchemaVersioned {
    val theme by state(codec = StringCodec) { "day" }
    val textSize by state(codec = IntCodec) { 14 }

    override val schemaVersion: Int get() = 3

    override fun migrate(from: Int, view: EncodedSnapshotView) {
        if (from < 2) view.rename("fontSize", "textSize")                   // schema 2 renamed it
        if (from < 3 && view["theme"] == "dark") view.put("theme", "night") // schema 3 respelled it
    }
}

@OptIn(ExperimentalStoreApi::class)
fun boot(saved: String): ReaderSettings {
    val settings = ReaderSettings()
    settings.restore(StoreSnapshot.decode(saved), RestorePolicy.Strict).getOrThrow()
    return settings   // from the text above: textSize = 16, theme = "night"
}
```

- **Versions.** A store that does not implement `SchemaVersioned` is at
  version 1, and so is every snapshot it takes, so a store's first changed
  schema is 2. `snapshot()` records the store's version as `schemaVersion`,
  and `encode()` writes it as `"schema"`. Raise the version whenever text an
  earlier release saved would no longer restore as it is: a state renamed or
  removed, or a codec whose text changed. Keep `schemaVersion` constant,
  because every `snapshot()` and restore reads it. It must be at least 1:
  otherwise `snapshot()` throws `IllegalStateException`, and a restore
  returns an `Error` carrying one.
- **The restore checks the version first.** Before anything else, under
  every policy, a restore compares the snapshot's version with the store's.
  At the same version, the snapshot restores as it is and `migrate` does not
  run. An older decoded snapshot is upcast: `migrate` runs once, with `from`
  set to the snapshot's version, on a copy of its text, and the restore then
  reads the edited copy with the store's codecs and policy. The snapshot
  itself does not change. A newer snapshot fails the restore with a
  `SnapshotMigrationException` that names the store and both versions,
  because a store cannot know what a later schema means. Nothing changes, and
  no initializer or codec runs.
- **Captured snapshots are not migrated.** A captured snapshot holds raw
  values, and `migrate` edits text. So a captured snapshot of another version
  (taken from a store of another class) fails the restore the same way.
  Restore `StoreSnapshot.decode(snapshot.encode())` instead, which migrates
  the states that have a codec.
- **Typed reads are not migrated.** Only a restore checks the version and
  runs `migrate`. `snapshot[state]` and `entry(state)` on a decoded snapshot
  of another version read its text as written: a renamed state is `Absent`
  under its new name (above, `StoreSnapshot.decode(saved)[settings.textSize]`
  is `null`, and `[settings.theme]` is `"dark"`), a changed codec may read
  stale text wrongly or throw `SnapshotFormatException`, and a newer snapshot
  is not refused. To read migrated values, restore the snapshot into the
  store first, then read the store.
- **One call covers every step.** `migrate` runs once per restore, not once
  per version, so write it as a ladder, oldest step first: `if (from < 2) …`,
  then `if (from < 3) …`, as above.
- **The view holds text.** A renamed state has no codec under its old name,
  so `migrate` edits the codecs' text, never decoded values. `get` returns a
  state's text, or `null` when the snapshot withholds the value or has no
  entry for the state (`contains` tells them apart). `put` sets a state's
  text; `null` withholds the value, so the restore leaves that state as it
  is. `remove` drops an entry, and that state keeps its value too. `rename`
  moves an entry, replacing any entry under the new name, and returns `false`
  when there is nothing to move. A state the old store could not encode has
  no entry. `stateNames` is a copy, so you can edit the view while iterating
  it. `families` names the keyed state families the text holds, read-only
  until keyed states arrive. The view is valid only while `migrate` runs: an
  edit after it returns throws `IllegalStateException`.
- **No writes.** `migrate` runs while the restore plans, before its action
  opens, so at top level it holds no lock of the store. It may read states,
  and reads committed values, but may not write any store: `mutate`,
  `update`, `action`, `atomic`, `restore`, `reset()` and `emit` (and
  `:holdfast-coroutines`' `suspendAction`/`suspendAtomic`) throw
  `IllegalStateException` in it.
- **A failed migration changes nothing.** A throwing `migrate` fails the
  restore with a `SnapshotMigrationException` that names the store, both
  versions and the class of the exception. The exception itself is not
  attached, because its message may quote an encoded value. An exception the
  library threw inside `migrate`, such as a refused write or a `put` under a
  keyed family's name, is attached as the cause, because its message names
  only stores and states.
- **A rename needs a new version.** Without `SchemaVersioned`, or with a
  `migrate` that misses the rename, the old name is a
  `RestoreIssue.UnknownState`. `IgnoreUnknown` skips and reports it, and the
  renamed state keeps its value. `Strict` fails the restore.

### 16.4 State tags, snapshot scopes and redaction

```kotlin
@ExperimentalStoreApi
abstract class StateTag {                       // closed, not exhaustive: match with an `else`
    object Secret : StateTag                    // never leaves memory or reaches a log
    object UserAuthored : StateTag              // what the user wrote; snapshot(UserAuthored) captures it
    object Remote : StateTag                    // synced data; left out of encode(), reset by a sterile restore
}

@ExperimentalStoreApi
abstract class SnapshotScope { object All; object UserAuthored; object Raw }

// Store member: the stable state(...) plus a codec (§16.2) and tags.
@ExperimentalStoreApi
fun <T : Any> state(
    transformer: Transformer<T>? = null,
    distinct: Boolean = false,
    codec: StateCodec<T>? = null,
    tags: Set<StateTag> = emptySet(),
    initialize: Initializer<T>,
): StateDelegate<T>

@ExperimentalStoreApi val State<*>.tags: Set<StateTag>
@ExperimentalStoreApi fun Store<*>.taggedStates(tag: StateTag): List<State<*>>
@ExperimentalStoreApi fun <V : Store<V>> V.snapshot(scope: SnapshotScope): StoreSnapshot
@ExperimentalStoreApi fun <V : Store<V>> V.restore(
    snapshot: StoreSnapshot,
    policy: RestorePolicy,
    sterile: Boolean = false,
): TransactionResult<RestoreReport>
class RestoreReport { val sterilized: Set<String> }   // added to §16.2's
```

A tag is policy the library enforces for one state, wherever that state's
value could leave memory, reach a log, or be written by the wrong party. It
is declared with the state and never changes.

```kotlin
@OptIn(ExperimentalStoreApi::class)
class MailStore : Store<MailStore>() {
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val pinned by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val unread by state(codec = IntCodec, tags = setOf(StateTag.Remote)) { 0 }
}

@OptIn(ExperimentalStoreApi::class)
fun saveAndReboot(mail: MailStore): MailStore {
    // Say mail holds token = "t0k3n", pinned = "m1", unread = 42.
    println(mail.snapshot()[mail.token])                            // "null": withheld outside SnapshotScope.Raw
    println(mail.snapshot(SnapshotScope.Raw)[mail.token])           // "t0k3n", in memory only
    println(mail.snapshot(SnapshotScope.UserAuthored).stateNames)   // "[pinned]"
    val saved = mail.snapshot().encode()
    // {"format":"holdfast.store","v":1,"schema":1,"states":{"pinned":"m1","token":null},"skipped":[]}
    val rebooted = MailStore()
    rebooted.restore(StoreSnapshot.decode(saved), RestorePolicy.Strict, sterile = true).getOrThrow()
    return rebooted   // pinned = "m1", token = "" (never written out), unread = 0 (reset)
}
```

- **Declaring.** Pass `tags` to the experimental `state` overload; the set is
  copied. Two combinations are refused where the state is declared, with an
  `IllegalArgumentException` naming it: `Secret` with `UserAuthored` (an
  overlay of what the user wrote leaves memory; a secret must not), and
  `UserAuthored` with `Remote` (sync may overwrite a Remote state and must
  never overwrite what the user wrote: declare one state of each and combine
  them in a derived state). `state.tags` reads a state's tags, and
  `store.taggedStates(tag)` lists the store's states that carry one, in
  declaration order, materializing never-read ones first. These two are the
  one lookup every tag-driven feature uses. A `derived` (or `suspendDerived`)
  state with a Secret state among its `sources` is Secret too; a derived is
  never `UserAuthored` or `Remote`, and a `computed { }` state has no tags.
  The taint follows the listed sources only: a Secret state read inside
  `compute` without being passed as a source leaves the derived untagged,
  and its value is shown and encoded like any other, so list every Secret
  state a derived reads as a source (which also makes it recompute when that
  state changes).
- **Secret: withheld, not scrambled.** Reads stay plaintext: `value`,
  observers, `effect` and `derived` see the value as always. The value is
  withheld wherever it would be written out or shown:
  - `encode()` writes it as `null`, in every scope, and its codec never sees
    it (a Secret state without a codec is listed as skipped, like any other).
    A decoded snapshot reads it as `Redacted`, and restoring that text leaves
    the state's value as it is.
  - A snapshot's typed reads (`snapshot[state]`, `entry(state)`) return
    `Redacted` (`get` returns `null`) unless the snapshot was captured with
    `SnapshotScope.Raw`. A decoded snapshot is never a Raw capture: it reads a
    Secret state as `Redacted` even when its text holds a value.
  - `render()` of a captured snapshot shows `<redacted>` and `toString()`
    shows names only, in every scope. A decoded snapshot knows no tags: it
    renders (and re-encodes) the text it holds, which is `null` for text
    `encode()` wrote. `MutableState.toString()` names the state
    (`MutableState(MailStore.token)`) and never shows a value.
  - `:holdfast-testing` records `Redacted` in timeline events and bridge
    histories, refuses value matchers on a Secret state with a teaching error
    (`emitted(prop)` and counts still work), and keeps Secret values out of
    `shouldMatch`/`shouldMatchSnapshotOf` failure messages.
  - The built-in middleware (`LoggingMiddleware`, `TimingMiddleware`,
    `ProfilingMiddleware`, `ValidationMiddleware`) writes ids, state names,
    durations and exception messages, never a state's value. The library's
    own exception messages name states and never quote values; an exception
    your code throws is logged with the message you gave it.
  - `:holdfast-hallmark`'s `boxed(validator, codec, tags)` and
    `boxedHandle(validator, codec, tags)`, and `:holdfast-hallmark-coroutines`'
    `suspendValidateAndMutate`, withhold a rejected value from the
    `HallmarkException` for a Secret state (§14.11), and `shouldBeBoxedAs`
    shows neither value in its failure message for one. A
    `ValidatingTransformer` you construct yourself cannot know its state's
    tags and keeps hallmark's messages, which may quote the value.

  A captured snapshot still holds the raw value in memory, so
  `val s = snapshot(); …; restore(s)` puts a secret back: undo stays
  lossless.
- **Encryption is not redaction.** An `EncryptingTransformer` protects the
  stored value; it does not keep it out of anything. A non-Secret encrypted
  state is encoded as its ciphertext and read back as plaintext. Tag the state
  Secret as well to keep its value, cipher or plain, out of the text and the
  logs.
- **Scopes.** `snapshot()` is `snapshot(SnapshotScope.All)`: every declared
  state, Secret ones read as `Redacted`. `SnapshotScope.UserAuthored`
  captures exactly the `UserAuthored` states, runs only their never-read
  initializers and holds no `derived` state; restored, it leaves every other
  state as it is. `SnapshotScope.Raw` captures what `All` does and reads a
  Secret state's plaintext through `snapshot[state]`, in memory only: its
  encoded text, `render()` and `toString()` withhold Secret values as in any
  scope. The scope plays no part in equality.
- **Remote.** `encode()` leaves Remote states out, neither written nor listed
  as skipped, unless called with `includeRemote = true`, so stale synced data
  does not persist; a restore of that text leaves them as they are. Encoded
  text carries no tags, so a decoded snapshot writes back the text it holds,
  whatever `includeRemote` says.
- **Round trips.** `StoreSnapshot.decode(s.encode())` equals `s` on what
  `encode()` writes: the Secret values and, by default, the Remote states are
  not part of that projection.
- **Sterile restore.** `restore(snapshot, policy, sterile = true)` drops the
  snapshot's entries for Remote states (they are not issues, and they are
  never decoded or type-checked) and resets every Remote state the store
  declares to its initial value, in the restore's one transaction: each
  Remote initializer runs again, as `reset()` runs it (§16.1), and its result
  is staged raw only where it differs, so an unchanged state's observers do
  not fire. A never-read Remote state is materialized before the action
  opens. A Remote initializer reads the other Remote states at their reset
  values, this store's other declared states at the values the restore
  leaves them (restored, recomputed as below, else as the transaction holds
  them: an enclosing action's pending write, else the committed value), and
  other stores' states and `derived` states at committed values: a Remote
  state computed from restored states comes out as a fresh store holding the
  restored values computes it. So does a declared state the restore itself
  brings to life — one neither restored nor Remote that nothing had read
  before the restore, which the restore first materializes from pre-restore
  values (a target whose entry it skips or that holds no value, or a state a
  Remote initializer reads, directly or through other states): the same
  reset re-runs its initializer, reading as a Remote initializer does, so it
  holds what a first read after the restore computes, unless a write has
  committed to it since it came to life. A state that was live before the
  restore keeps its value, as a plain restore leaves it. With `prefs`
  (`UserAuthored`), `locale by state { prefs.value }` and `feed` (`Remote`,
  reading `locale`), a sterile restore of a `prefs` overlay into a fresh
  store sets `locale` and `feed` from the restored `prefs`, even though
  materializing `feed` first read `locale` from the default. `derived` states
  are not written back, even into the store that took the snapshot: they
  recompute from the restored sources, so none keeps a value computed from
  dropped data. `RestoreReport.sterilized` lists the reset Remote states. As
  after `reset()`, `removeState`/`clearStates` refuse a state the reset
  re-ran until the restore's transaction (or the action or frame it joined)
  ends. A throwing initializer, or a cycle, rolls the whole restore back.
- **Why a tag and not a `RedactingTransformer`.** Issue #20 asked whether
  redaction belongs in a transformer. It does not: a transformer changes what
  every read returns, and reads must stay plaintext for the app to work, while
  the transformer slot is what `EncryptingTransformer` needs. A tag is
  honoured where values are read out of a snapshot, rendered, encoded and
  recorded, and nowhere else.

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
