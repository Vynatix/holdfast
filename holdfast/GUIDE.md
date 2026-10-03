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
16. [Snapshots, persistence and boot (experimental)](#16-snapshots-persistence-and-boot-experimental) — reset, encoding snapshots, schema versions, state tags and redaction, derived states and `merged`, keyed state families, hydration, the persisted overlay
17. [The store tree (experimental)](#17-the-store-tree-experimental) — declaring children with `store { }`/`group { }`/`keyed { }`, keyed stores through a declared factory, dispose and detach, declaration rules, tree snapshots and typed reads, restore and reset over a subtree, encoding and the persisted-name self-check, the tree value, tree middleware, testing a tree, hydrating a tree

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
transaction is not committing still works. Calling `commit()` or `rollback()`
by hand on the committing transaction from there (`store.activeTransaction`)
is a no-op: its writes have applied, and its fanout — this one — belongs to
the commit that applied them.

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

`middlewares`/`clearMiddleware` register and clear only this
consumer-registered list. Library-installed middleware lives in a separate
outer ring that is always outermost of everything registered here —
`clearMiddleware()` never reaches it, and only `dispose()` tears it down
alongside the list above (§13). The store tree's `App.tree.middlewares(...)`
(§17.9, experimental) installs there: a `TreeMiddleware` sees every
transaction of `App` and of every store of its subtree, attached now or
later, with that store's node.

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

The other direction is refused: a nested `action` whose enclosing transaction
was committed or rolled back by hand while it ran (through `activeTransaction`
from its body, or from its middleware) returns `Error` — its writes cannot
merge into a finished transaction (`Cannot merge nested transaction '…' into
its enclosing transaction: …`) — and the enclosing action's own commit is
then a no-op, as after any hand commit (§12).

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
| `store { }`/`group { }`/`keyed { }` declaration, a child's registration when it attaches, `KeyedBranch.create` registration, a store's detach and its dispose's release of its children, `tree.children()`/`stores`/`nodeOf`, `KeyedBranch.get`/`entries()`, every subtree listing | child registry lock (per store) | yes | Taken after any store's `transactionLock` (a store disposing from inside its own action detaches from its parent's registry under it) and after a keyed entry's construction lock. One at a time: a listing takes a parent's, releases it, then a child's. Nothing is taken under it, and no store, lambda or listener is called while it is held — callers copy, release, then touch stores (§17) |
| First read of a `store { }`/`group { }` child (materialization) | none while the lambda runs (a per-thread mark only); the declaration's attach lock across attach phases 3–7 | the mark: no — re-entering it on the same thread is a cycle, which throws | The lambda runs holding no lock or latch of the tree's — only what the reading thread already holds — and racing first reads may each run it. The first to claim the declaration holds its attach lock across the attach (no user code there); another reader parks on it, then takes the winner's child |
| Linking a child to its parent; releasing a disposed parent's children | tree structure lock (one per process) | yes | Held only for the parent link, the cycle check, the node's fields and the ancestor chain: never with a child registry lock, never across a lambda, a factory, a listener or a middleware sync |
| `KeyedBranch.create`/`getOrCreate` factory | none while the factory runs (a per-thread mark only); the per-entry construction lock across the attach | yes | Held only by the creator whose store claimed the key, from the claim through the attach (no user code there); a lookup or losing `getOrCreate` on another thread parks on it. A same-thread re-entry of the factory for its own key is a cycle error (§17.2) |
| `tree.middlewares(...)`/`removeMiddleware` and the ring sync of a store that attaches or detaches | a store's tree install lock, then each member's sync lock in turn | yes | The install lock guards only the installing store's own list; a member's sync lock is taken alone, never nested with another's or under a registry lock, and holds that member's outer middleware ring lock while it replaces the ring (§17.9) |

### 10.2 Lock ordering

The library acquires locks in this consistent global order; respect it
when extending:

```
transactionLock  →  middlewareLock  →  pendingLock  →  initializer latch  →  attachment slot lock  →  propertiesLock  →  bridgeLock  →  stateLock  →  observersLock
```

The attachment slot lock is internal: companion modules take it through the
`@StoreInternalApi` `internalAttachIfAbsent`, whose `create` runs under it
and may take only the locks to its right (registering an internal state
takes `propertiesLock`).

`pendingLock` is a transaction's write-buffer lock: every stage into, read of
and consumption of its pending writes takes it. A commit's apply pass holds
it while it assigns each written state under `stateLock`, unlinks evicted
keyed entries under `propertiesLock`, and may wait on an initializer latch (a
`distinct` state's `equals` that first-reads a state of the store) — so
nothing takes it under a latch, `propertiesLock` or `stateLock`: a read's peek
at the owner's pending writes runs before the read takes `stateLock` and
never inside an initializer, and `removeState`/`clearStates` take the active
chain's pending locks before `propertiesLock`.

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

**The tree's host locks** (§17.8). Every store's `tree` handle hosts its
value on a private store of its own, created lazily with the value — on the
first read or observation of that store's `tree.value`, never by
`snapshot()`, `stores`, `middlewares`, `track(tree)` or `hydrateAll` — so
one host per value in use. That store's `transactionLock` — a host
lock — is taken only by a settle of the value and by the host's dispose. A
settle is a top-level action on the host that runs after the entry being
settled has released every store lock it took, or inline where a change
reaches the subtree outside any entry. Nothing takes a host lock under
another store's lock: a read of the value takes no lock at all (it reads
the value's backing, or takes a fresh lock-free capture when a store has
just joined or left), and inside an open entry it never opens a transaction
on the host. A settle that finds the host busy hands its recompute to the
host's holder and returns; it never waits. The host's dispose, when its
store is disposed, is inline only from a dispose outside every entry and
every lock of that store (and inside a `:holdfast-testing` open-transaction
body, which holds none); from inside an entry it runs when that entry
settles, and from inside an action holding the store's lock after that
action releases it. A value observer runs under its host lock, so an
action it opens on a store of the subtree nests host → store, the one
direction the graph allows — and it is an ordinary observer: one that
writes to a store another thread is committing carries the cross-store
hazard every observer does. A bridge attached to a state of the subtree
that replays a value at attach (an inbound write under that state's
`bridgeLock`) queues the value's recompute like any inbound write; outside
an entry the recompute runs inline there, so a bridge whose replay must not
run tree observers should be attached from inside an action, where the
recompute waits for the settle.

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

### 11.6 Testing a tree

`track(store.tree)` (§17.10, experimental) tracks a store and every store
of its subtree, keyed stores created mid-test included, records every
transaction with its node into one tree timeline, and resets the store and
its subtree at teardown so the next test finds the initial values.

## 12. Common Pitfalls

| Symptom | Cause | Fix |
|---|---|---|
| Observer fires twice for one logical event | Subscribed via `effect` AND wired through a bridge | Pick one |
| Test sees `expected=N, actual=N+1` for first event | Forgot the initial-fire on subscribe | `seen.clear()` before the assertion |
| `IllegalStateException: State must be created by this Store instance` | Mutating a state owned by a different store | The state belongs to a different store — pass the state declared on the store you're acting on |
| `IllegalStateException: Cannot write S.x: S's transaction '…' has already been rolled back (status: RolledBack) …` (or `… has already applied its writes (status: Committed) …`; or `Cannot merge nested transaction '…' into its enclosing transaction: …` as the cause of a nested `action`'s `Error`; or `Cannot restore S …` as the cause of a `restore()`'s `Error`) | Mutating — or committing a nested `action`, or running `restore()` — after manually calling `rollback()` (or `commit()`) on the active transaction inside the action body or from a middleware hook; or writing into an `atomic` participant from a middleware's `onTransactionError` or a `FrameObserver` while the frame unwinds | Let `action` manage commit/rollback; start a new `store action { … }` for further writes. (A hand `commit()`/`rollback()` on a transaction whose writes have already applied — from its own fanout, or on a frame participant awaiting its fanout — is a no-op, §15.3) |
| `IllegalStateException: Cannot write S.x: S's transaction '…' has already applied its writes …` (or `emit an event on S`, or an `Error` from a nested `action`/`atomic`) | An effect/observer writes back into the store whose commit is notifying it — or into any participant of the `atomic`/`suspendAtomic` frame that is committing, since every participant applies before any fans out; the write could never commit | Write in the action (or frame body) itself, derive the value (`computed`/`derived`/`derivedState`), or run a follow-up action after the commit (as `store action { … }` on another thread, or launched on a dispatching scope with `.getOrThrow()`) — see §4.4, §15.3 |
| `IllegalStateException: Cannot write S.x: a suspendAction or suspendAtomic holds S …` | A bare `mutate`/`update` from another thread while a `suspendAction`/`suspendAtomic` on S is committing | Write through `S action { … }`, which waits for the store — see §8.2 |
| `Holdfast: a post-commit side effect of S failed …` on standard error (JVM/Android) or standard output (iOS/wasmJs) | An effect, bridge publish or `derived` recompute threw after its commit; with no `uncaughtObserverHandler` set, the failure is logged | Fix the thrower, or set `uncaughtObserverHandler` to route (or `{ }` to silence) these failures |
| `Holdfast: library machinery attached to S (…) failed in onStoreDisposed …` on standard error (JVM/Android) or standard output (iOS/wasmJs) | Library machinery attached to S (an internal `StoreAttachment` of a companion module) threw while `S.dispose()` told it; with no `uncaughtObserverHandler` set, the failure is logged. S is disposed regardless | Report it to the module the named attachment comes from, or set `uncaughtObserverHandler` to route (or `{ }` to silence) these failures |
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

The self-typed base every store extends (`class CounterStore : Store<CounterStore>()`; `EventfulStore<Self, E>` adds `events`/`emit`). `NodeStore` *(experimental)* is the non-recursive base for an anonymous inline child (`object : NodeStore(), Draft { override val text by state { "" } }`), since `object : Store<X>()` cannot be written without a name; code holding only the consumer interface writes through the interface's own methods, or casts to the store: `(draft as NodeStore) action { draft.text mutate "x" }` (§17.1).

| Member | Signature | Description |
|---|---|---|
| `state` | `fun <T : Any> state(transformer: Transformer<T>? = null, distinct: Boolean = false, initialize: Initializer<T>): StateDelegate<T>` | Declares a state property when the store is constructed; `initialize` runs on first need (§4.1); `distinct=true` opts into same-value commit dedup |
| `state` (with a codec or tags) | `fun <T : Any> state(transformer: Transformer<T>? = null, distinct: Boolean = false, codec: StateCodec<T>? = null, tags: Set<StateTag> = emptySet(), initialize: Initializer<T>): StateDelegate<T>` *(experimental)* | The stable `state` plus a `StateCodec` that `snapshot().encode()` writes the state's raw value with (§16.2) and `StateTag`s the library enforces — `Secret`, `UserAuthored`, `Remote` (§16.4); a call that passes neither resolves to the stable overload and needs no opt-in; throws on a disposed store, and a refused tag combination fails the declaration with `IllegalArgumentException` |
| `action` | `infix fun <R> action(body: Self.() -> R): TransactionResult<R>` | Runs body in a transaction; body's return value carried in `Success<R>` |
| `invoke` | `operator fun <R> invoke(block: Self.() -> R): R` | Plain context block |
| `middlewares` | `fun middlewares(vararg middleware: Middleware<Self>)` | Registers middleware (LAST argument is outermost) |
| `clearMiddleware` | `fun clearMiddleware()` | Removes all registered middleware |
| `activeTransaction` | `val activeTransaction: Transaction?` | Volatile read of in-flight transaction |
| `uncaughtObserverHandler` | `var uncaughtObserverHandler: ((Throwable) -> Unit)?` | Handler for post-commit failures: observer callbacks (including a write back into this store during its own commit fanout), fanout `Transformer.get`, `Bridge.publish` / `SuspendingBridge.publishAwaited`, `derived` recomputes. Default null = logged to standard error (JVM/Android) or standard output (iOS/wasmJs), naming the store; `{ }` silences. Observer/`Transformer.get`/bridge failures are reported on the committing thread inside the fanout, where a throwing handler ends the fanout and fails the action; `derived` failures are reported after the recompute releases the store, where a throwing handler fails no action. Also receives a throwing dispose notification of library machinery attached to the store (an internal `StoreAttachment`), reported as `dispose()`'s last step on the disposing thread, holding no lock `dispose()` took; a throwing handler is ignored there, as `dispose()` never throws. And a `:holdfast-coroutines` hydration refresh whose outcome could not be recorded (a middleware rejected its `HydrationFailure` record too, or its settle threw), reported on the refresh's coroutine once the hydration gate has released the store; the hydration stays `Seeded` with nothing in flight, the next `hydrate()` retries it, and a throwing handler is ignored there (§16.7). And what a hydrator's persisted overlay could not do — apply the blob it read (kept, never written over), or write one (over its size limit, a failing capture or codec, a failing `put`, a cancelled scope) — as an `OverlayException`, never under the hydration gate; a throwing handler is ignored there too (§16.8) |
| `lockOrderKey` | `val lockOrderKey: Long` *(opt-in)* | Process-monotonic ordering key used by `atomic(...)` for deadlock-safe lock acquisition |
| `scope` | `open val scope: CoroutineScope` | Scope for the store's async work; resolution order: per-call parameter → subclass override → `bindToScope` binding → `Store.defaultScope` |
| `bindToScope` | `fun bindToScope(scope: CoroutineScope)` | Binds the store to a scope (level 3 of the resolution chain); rebindable, never cancels the previous or new scope |
| `clock` | `open val clock: Clock` *(experimental — `@ExperimentalStoreApi`)* | The `kotlin.time.Clock` store code reads time through; resolution order: subclass getter override → `bindClock` binding → `Clock.System`. Initializers read it lazily, when a state is first needed (its first read, or `snapshot()`/`restore()`), and again at every `reset()` (§16.1), and a `Remote` state's at every sterile `restore()` (§16.4). Library timestamps (`Transaction.endTime`, timing middleware) don't use it |
| `bindClock` | `fun bindClock(clock: Clock?)` *(experimental)* | Binds a clock (level 2), e.g. a fixed test clock; `null` unbinds. Throws on a disposed store. `storeTest` restores each tracked store's binding to its value at first `track` (`store.action {}` doesn't auto-track), so bind after tracking |
| `reset` | `fun <V : Store<V>> V.reset(): TransactionResult<Unit>` *(experimental, extension)* | Puts every declared state, and every live keyed-state entry (never evicting one, §16.6), back to its initializer's value in one transaction: initializers re-run (reading each other's reset values), results staged raw, only changed states staged and fired; a throwing initializer rolls it all back (§16.1) |
| `restore` (with a policy) | `fun <V : Store<V>> V.restore(snapshot: StoreSnapshot, policy: RestorePolicy, sterile: Boolean = false): TransactionResult<RestoreReport>` *(experimental, extension)* | `restore` (§14.1) under `Strict`, `IgnoreUnknown` or `BestEffort`, reporting the restored states, the declared states the snapshot holds no value for, and each skipped entry; a rejected restore changes nothing (§16.2). A snapshot of another schema version is migrated first, or refused (§16.3). `sterile = true` drops the snapshot's `Remote` entries and resets every `Remote` state to its initial value in the same transaction (§16.4) |
| `snapshot` (with a scope) | `fun <V : Store<V>> V.snapshot(scope: SnapshotScope): StoreSnapshot` *(experimental, extension)* | `All` is `snapshot()`; `UserAuthored` captures only the states and keyed state families (§16.6) tagged `UserAuthored`; `Raw` lets typed reads return a `Secret` state's plaintext, in memory only (§16.4) |
| `taggedStates` / `tags` | `fun Store<*>.taggedStates(tag: StateTag): List<State<*>>`; `val State<*>.tags: Set<StateTag>` *(experimental, extensions)* | The one tag lookup: a state's tags (a `derived` with a `Secret` source is `Secret`; a keyed-state entry's are its family's), and the store's states carrying a tag in declaration order, never-read ones materialized first, then the live entries of the keyed state families carrying it (none created, §16.6); `taggedStates` throws on a disposed store, `tags` keeps answering (§16.4) |
| `derivedState` | `fun <V : Store<V>, T : Any> V.derivedState(vararg sources: State<*>, compute: V.() -> T): DerivedState<T>` *(experimental, extension)* | A read-only `DerivedState` that settles: recomputed once per outermost action or frame that changes a source (sources on any store; not a `computed` one), after it releases every store, from a committed cut of the sources, in a transaction of its own on this store; never waits for a busy store (hands off). Observable like a declared state, usable as a source, declared with `by` or `=`; `mutate`/`update`/`bridge`/`observeFrom` on it throw; not in snapshots, `properties` or `taggedStates`; `dispose()` stops it (§16.5) |
| `merged` | `fun <V : Store<V>, L : Any, R : Any, T : Any> V.merged(local: State<L>, remote: State<R>, merge: (L, R) -> T): DerivedState<T>` *(experimental, extension)* | A `DerivedState` over two different states this store declares — the user's side (`local`, tag it `UserAuthored`) and sync's (`remote`, tag it `Remote`) — so an adoption writing `remote` never touches `local` and recomputes the merge once; carries neither tag; the input tags are not checked. Another store's, a derived, `computed` or internal input, or the same state twice, is an `IllegalArgumentException` (§16.5) |
| `keyedState` | `fun <K : Any, T : Any> Store<*>.keyedState(transformer: Transformer<T>? = null, distinct: Boolean = false, codec: StateCodec<T>? = null, keyCodec: StateCodec<K>? = null, tags: Set<StateTag> = emptySet(), initialize: (K) -> T): KeyedStateProvider<K, T>` *(experimental, extension)* | Declares a keyed state family with `val docs by keyedState<K, T> { key -> … }`: one state per key, created from the initializer at the key's first `docs[key]` and the same `State` while it lives; `getOrNull`/`contains`/`entries` never create one. `evict(key)`/`evictAll()` are staged like writes (from this store's own commit fanout, deferred until the commit ends) and leave a stale handle whose writes throw; other entries keep their observers and bridges. Snapshots capture every live entry (`keysOf`), `encode()` writes a family with a `codec` and a `keyCodec` as an object under its name, `restore` creates entries and never evicts, `reset()` re-runs live entries' initializers. Families and states share names; throws on a disposed store (§16.6) |
| `hydrator` | `fun <V : Store<V>> V.hydrator(spec: HydrationSpec<V>.() -> Unit): Hydrator<V>` *(experimental, `:holdfast-coroutines` extension)* | Gives the store its one hydrator: `base { }` seeds it in one transaction that also marks a refresh in flight, `refresh { } adopt { }` fetches once that has committed and adopts into `Remote` states only (a savepoint; any other write fails the adoption, naming the state). `hydrate()` does nothing while `Seeded` or `Hydrated`, retries only the refresh from `Failed`, and seeds and fetches once under concurrent calls; `invalidate()`/`stageInvalidate()` and `reset()` go back to `Detached`. `state` is a read-only, observable `State<Hydration>` no snapshot sees; `hydrate()` and `awaitSettled()` throw inside any action, frame or suspending entry; `hydratorOrNull()`, `hydrateEach(…)`; a second `hydrator { }` throws (§16.7) |
| `schemaVersion` / `migrate` | `interface SchemaVersioned { val schemaVersion: Int; fun migrate(from: Int, view: EncodedSnapshotView) }` *(experimental; a store subclass implements it)* | Numbers the store's schema (a store without it is version 1) and upcasts an older decoded snapshot's encoded text before a restore reads it; a newer snapshot, a captured one of another version, or a throwing `migrate` fails the restore with `SnapshotMigrationException`, changing nothing. `migrate` may read states but not write any store (§16.3) |
| `dispose` | `fun dispose()` | Terminal, idempotent teardown — drops observers, detaches bridges, clears middleware; subsequent state APIs throw `IllegalStateException("store disposed")`. A `derivedState`/`merged` recompute still waiting on this store, for a live store, runs inside it, on the calling thread (§16.5) |
| `isDisposed` | `val isDisposed: Boolean` | Whether `dispose()` has been called |
| `properties` | `val properties: Map<String, State<*>>` | Snapshot of the materialized states (a never-read state is absent until something needs it); the entries of a keyed state family are not properties (§16.6) |
| `getState` / `hasState` / `removeState` / `clearStates` | … | Reflection over the materialized states; `removeState`/`clearStates` dispose observers + bridge silently and keep the declaration, so the next read (or `snapshot()`/`restore()`/`reset()`) recreates the state from its initializer (derived backing and internal states have no initializer and go with their declarations). Both throw `IllegalStateException` for a state with a pending write in the active transaction or one enclosing it, or held by an open `reset()` (§16.1) or sterile `restore()` (§16.4), and from inside a `:holdfast-coroutines` hydrator's `adopt { }` (§16.7), whose rollback could not bring a dropped state back |

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
| `stagedEvictions` | `val stagedEvictions: Set<State<*>>` *(experimental)* | The keyed-state entries this transaction evicts when it commits (owner-thread only); disjoint from `modifiedStates` (§16.6) |
| `commit` | `fun commit()` | Idempotent. No-op if not Active, or once its writes have applied (a frame participant awaiting its fanout, or the transaction whose fanout is running — §15.3). A savepoint's commit into an enclosing transaction that has applied or ended by hand throws `TransactionException`; the nested `action` returns it as `Error` (§9.8) |
| `rollback` | `fun rollback()` | Idempotent. No-op if not Active, or once its writes have applied (as `commit`) |

You normally do not call `commit` / `rollback` yourself — `action`
manages them.

### `MutableState<T>`

| Member | Signature | Description |
|---|---|---|
| `value` | `override val value: T` | Post-`get` view; read-your-own-writes for owner thread (except inside a state initializer or a schema migration (`SchemaVersioned.migrate`), which read committed values only, or, in an initializer re-run by `reset()`, its store's reset values; in an initializer re-run by a sterile `restore()`, the `Remote` states' reset values and its store's other declared states at the values the restore's transaction holds for them, restored or an enclosing action's pending writes — §4.1/§9.7/§16.1/§16.3/§16.4) |
| `observe` | `fun observe(observer: (T) -> Unit): Disposable` | Subscribe with initial fire |
| `bridge` | `var bridge: Bridge<T>?` | Get/set the bridge; setting installs an observer on it |
| `toString` | `override fun toString(): String` | Names the state (`MutableState(CounterStore.count)`), never its value (§16.4); a keyed-state entry by its family, never its key (`MutableState(DocsStore.docs[*])`, §16.6) |

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

// Over a store tree (§17.9, experimental): the same three hooks with the store's node.
@ExperimentalStoreApi abstract class TreeMiddleware {
    protected open fun onTransactionStarted(node: StoreNode, context: Middleware.MiddlewareContext<*>) {}
    protected open fun onTransactionCompleted(node: StoreNode, context: Middleware.MiddlewareContext<*>) {}
    protected open fun onTransactionError(node: StoreNode, context: Middleware.MiddlewareContext<*>, error: Throwable) {}
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

> A store and its children are captured as one consistent cut through `store.tree.snapshot(node, scope)` (§17.5), put back or reset as one frame through `store.tree.restore(tree)`/`store.tree.reset(node)` (§17.6), and carried as text through `TreeSnapshot.encode()`/`store.tree.decode(text)` (§17.7) — all experimental.

```kotlin
class StoreSnapshot internal constructor(…) {
    val stateNames: Set<String>   // the states its scope captured (every declared state for snapshot()); not the backing states of derived
    val size: Int
    // equals/hashCode compare values; toString lists state names only.
    // Experimental (§16.2): schemaVersion, unencodableStateNames, encode, entry, get,
    // render, and StoreSnapshot.decode(text); keysOf(family) for keyed state families (§16.6).
}

fun <V : Store<V>> V.snapshot(): StoreSnapshot
fun <V : Store<V>> V.snapshot(scope: SnapshotScope): StoreSnapshot   // experimental, §16.4
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot): TransactionResult<Unit>   // RestorePolicy.IgnoreUnknown
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot, policy: RestorePolicy, sterile: Boolean = false): TransactionResult<RestoreReport>   // experimental, §16.2/§16.4
```

`snapshot` captures the raw stored value of every declared state (see §4.1
for delegates that wrap `state(…)`) — a state nobody has read yet is
materialized first (its initializer runs), so even an untouched store's
snapshot is complete — and of every live entry of a keyed state family
(experimental, §16.6). The values form one consistent cut: a commit applying
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

The experimental `derivedState(sources) { … }` and `merged(local, remote)`
(§16.5) return a read-only `DerivedState` instead of a `Pair`: it is its
own `Disposable`, cannot be written, stays out of snapshots, and settles —
it recomputes once per outermost action or frame that changes its sources,
on any stores, from a committed cut of them. `derived` keeps its per-commit
recompute (once per changed source for a source on another store while its
own store is idle) until the 0.7.0 triage; the post-commit work of an
`atomic`/`suspendAtomic` frame's stores runs once the outermost action or
frame on the thread has exited.

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
rolled back; on body return, every store applies its writes — all inside
one write bracket — and only then does each store fan out, in lock order.

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
it sees errors thrown by validation middleware placed earlier. A
`TreeMiddleware` installed through a store's `tree` (§17.9) is outermost of
all of these on that store and every store under it, whatever the order of
registration.

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

// Hydration (experimental, §16.7): seed once, fetch once, adopt into Remote states.
fun <V : Store<V>> V.hydrator(spec: HydrationSpec<V>.() -> Unit): Hydrator<V>
fun Store<*>.hydratorOrNull(): Hydrator<*>?
suspend fun hydrateEach(vararg hydrators: Hydrator<*>)
suspend fun StoreTree.hydrateAll(node: StoreNode = this.node, scope: CoroutineScope? = null, awaitSettled: Boolean = true): HydrateAllReport   // §17.11
class Hydrator<V : Store<V>> {                       // state / current / hydrate / invalidate / stageInvalidate / awaitSettled
    suspend fun hydrate(scope: CoroutineScope = …)   // defaults to the store's Store.scope
    val overlayKey: String?                          // the persisted overlay's key (§16.8)
    suspend fun clearOverlay()                       // remove what the overlay wrote
}
sealed interface Hydration                           // Detached / Seeded / Hydrated / Failed(cause)
// In hydrator { }: overlay(kv, key, sizeLimit = 8192) persists the UserAuthored states (§16.8).
class OverlayException : IllegalStateException       // what the overlay reports; val key: String
```

`suspendAction` allows the body to suspend (`delay`, `await`, `withContext`).
Mutually exclusive with blocking `Store.action` on the same store via an
internal coroutine `Mutex` installed lazily — also while it is being
installed: the first `suspendAction` or `suspendAtomic` on a store waits,
suspending and holding no thread, for a blocking `action` or `atomic` that
took the store before the `Mutex` existed, and a blocking one that finds it
installed once it has the store waits for it. Cancellation of the body
rolls back the transaction; commit phase wraps in `NonCancellable` so
observer/bridge fanout completes cleanly even if the surrounding scope
cancels mid-commit, and the call then returns the commit's
`TransactionResult` (the caller sees its cancellation at its next suspension
point). Since 0.6.0 an outermost `suspendAction` or `suspendAtomic` called
from an already-cancelled coroutine throws that `CancellationException`
before taking the store, on every platform.

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
  commit by writing to a store outside the frame is post-commit and stays
  legal. (Writing to a participant from there is refused: every participant
  has already applied, §15.3.)
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
5. **Apply** — EVERY store's writes are assigned, inside one write bracket
   spanning all the participants' states, before any store fans out. A
   consistent read across the participants — from any thread — sees the
   whole frame or none of it: never `a`'s new value with `b`'s old one. This
   holds when every participant opens a fresh top-level root: an outermost
   frame, or a nested one that shares no store with the action or frame it
   is nested in. A participant the frame shares with an enclosing action or
   frame is a savepoint: its writes merge into the enclosing transaction and
   apply when that one commits, while the frame's other participants apply
   when the frame exits. Until then a consistent read can see those stores
   new and the shared one old — and for good if the enclosing entry rolls
   back (§15.4). Enroll every store in the outermost frame to keep the
   guarantee.
6. **Fanout** — per store, in lock order: store `a`'s observers, then its
   bridge publishes, then its events; then `b`'s; then `c`'s. Every
   participant has already applied, so an observer on `a`, on any thread,
   reads `b`'s committed value. And an observer may no longer write to any
   participant — `b` while `a` fans out as much as `a` while `b` does: that
   throws, and a nested `action`/`atomic` on it returns an `Error` the
   observer must check, exactly like a write back into a store from its own
   fanout (§4.4). Write in the frame body, or derive the value (§16.5). Nor
   may an observer finish a participant by hand: `commit()` or `rollback()`
   on `b`'s transaction (through `b.activeTransaction`, or a middleware
   context) while `a` fans out is a no-op — `b` has applied, and its fanout
   belongs to the frame, which runs it in its turn. A
   store whose fanout fails (only a throwing `uncaughtObserverHandler` can
   make it) does not keep the later ones from fanning out; the frame returns
   the failure as an `Error`, and `FrameObserver.onFrameRolledBack` fires
   instead of `onFrameCommitted`, although every participant's values stand.
   No participant's middleware gets `onTransactionError` then: all of them
   committed, and a single `action`'s commit failure reaches no middleware
   either. Otherwise `FrameObserver.onFrameCommitted` fires after the last
   store.
7. **Rollback** (body throw, `started`/`completed` throw, or inner-error
   escalation) — REVERSE lock order, `onTransactionError` per store first,
   then rollback. Rollback never touches state and never re-runs
   `Transformer.set`.
8. **Settle** — once the outermost action or frame on the thread has exited
   and released every store it took (this frame, unless it is nested in an
   action or another frame), derived states settle: every `derivedState`/
   `merged` whose sources the frame changed recomputes once, from a committed
   cut of its sources, and the post-commit work (`derived` recomputes) of each
   store whose root the frame opened runs.

`suspendAtomic` follows the same phases with the suspending machinery: the
per-store `AsyncSerializer` mutex instead of the blocking lock, apply and
fanout under `withContext(NonCancellable)`, each store's
`SuspendingBridge.publishAwaited` awaited and its event drain honoring
`BufferOverflow.SUSPEND` back-pressure before the next store fans out — all
after every store has applied, so no reader ever sees one participant applied
while another's publish is still in flight. A `CancellationException` from
one store's publish (a `publishAwaited` that times out, say) does not keep
the later stores from fanning out either; the frame returns it as an `Error`
once they have. A blocking `action`/`atomic` on any participant from inside
the commit returns an `Error` at once.
(One caveat: `SuspendingMiddlewareHooks` async hooks do not fire for frame
roots yet — sync hooks do.)

**Durability non-goal:** a frame is in-memory 2PC across stores in ONE
process. Bridge/persistence publishes remain per-store post-commit fanout;
there is no crash-consistency across external stores. If the apply itself
throws partway through phase 5 (only a `distinct` state's `equals` can), the
stores applied before it stay committed and fan out, and the rest roll back —
as does every participant the frame joined as a savepoint of an enclosing
transaction, so none of its writes survives there. Only the participants
that roll back get `onTransactionError`.

### 15.4 Nesting and interop

- A frame nested inside an `action` or another frame on the same
  thread/coroutine opens SAVEPOINTS for shared stores: the nested frame's
  commit merges their writes into the enclosing scope, and an enclosing
  rollback discards them. The stores the nested frame introduces get fresh
  roots, which commit when the nested frame exits: an enclosing rollback
  cannot undo those. A nested frame's `Error` escalates to the enclosing
  frame like an inner action's (same `TolerateInnerErrors` opt-out).
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
- `tree.restore(capture)` and `tree.reset(node)` (§17.6) are each ONE
  outermost `atomic` over the subtree's live stores — the write half of
  `tree.snapshot`'s cut — and refuse to run inside an action, a frame, a
  `suspendAction`/`suspendAtomic` body, a commit's fanout or a derived
  state's recompute (a nested frame is not one frame: the stores the
  enclosing entry holds would join as savepoints and apply with it, the
  rest at the nested exit). Call them from outside every entry.

### 15.5 Frame observability

Per-store middleware already sees every frame root (correlate the N
per-store transactions of one frame via `Transaction.frameId`); over a
store tree, one `TreeMiddleware` (§17.9) sees every root of a frame with
its store's node and the shared `frameId`. For app-level audit/telemetry
that wants the frame as ONE event, register a `FrameObserver`
(experimental — `@ExperimentalStoreApi`):

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
value (§16.2) — unless a keyed state family has live entries: `reset()`
resets them but never evicts them, so `evictAll()` in the same action to
match a new store (§16.6).

The one exception is an initializer that reads a `derived` state (or a
`derivedState`/`merged` one, §16.5) computed from states this reset changes. A `derived` recomputes only after the reset
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
  A middleware that commits or rolls that transaction back by hand from
  `onTransactionStarted` — or an initializer re-run while the restore
  stages — fails the restore: it returns an `Error` carrying the
  `IllegalStateException` a `mutate` into a finished transaction throws
  (§12), and nothing is staged into the finished transaction.
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
  the same state names with `==` raw values, and the same keyed state
  families with the same keys and `==` raw values (§16.6), whichever instances took them
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
    val families: Families                      // keyed state families, by name: editable (§16.6)
    operator fun contains(name: String): Boolean
    operator fun get(name: String): String?     // codec text; null when withheld or absent
    fun put(name: String, text: String?)        // null withholds the value
    fun remove(name: String): Boolean
    fun rename(from: String, to: String): Boolean
    class Families { val names: Set<String>; get/put/remove/rename/contains (§16.6) }
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
  it. `families` holds the keyed state families the text holds, each one's
  entries as encoded key to text: read one with `view.families["docs"]` (a
  copy), and `put`, `remove` or `rename` a family (§16.6). A name holds
  either a state's entry or a family, and an edit that would give one name
  both throws `IllegalArgumentException`. The view is valid only while
  `migrate` runs: an edit after it returns throws `IllegalStateException`.
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
  declaration order, materializing never-read ones first, then the live
  entries of the keyed state families that carry it (§16.6). These two are the
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
  captures exactly the states and keyed state families (§16.6) tagged
  `UserAuthored`, runs only their never-read initializers and holds no
  `derived` state; restored, it leaves every other state as it is.
  `SnapshotScope.Raw` captures what `All` does and reads a
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

### 16.5 Derived states and `merged`

```kotlin
@ExperimentalStoreApi
sealed interface DerivedState<T : Any> : State<T>, Disposable {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): DerivedState<T>   // `by` yields itself
}

@ExperimentalStoreApi
fun <V : Store<V>, T : Any> V.derivedState(vararg sources: State<*>, compute: V.() -> T): DerivedState<T>

@ExperimentalStoreApi
fun <V : Store<V>, L : Any, R : Any, T : Any> V.merged(
    local: State<L>,
    remote: State<R>,
    merge: (L, R) -> T,
): DerivedState<T>
```

A derived state is a read-only state the library keeps computed from other
states. `merged(local, remote)` is the one a synced store needs: the user
writes `local`, sync writes `remote`, and the screen reads the merge, so
adopting a fetch can never overwrite what the user wrote — not by
convention, but because the adoption only ever writes `remote`.

```kotlin
@OptIn(ExperimentalStoreApi::class)
class NotesStore : Store<NotesStore>() {
    val pinned by state(tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val fetched by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val shown by merged(pinned, fetched) { pins, all -> all.sortedByDescending { it in pins } }
    val count by derivedState(shown) { shown.value.size }
}

@OptIn(ExperimentalStoreApi::class)
fun pinThenAdopt(notes: NotesStore) {
    val sub = notes.shown effect { println(this) }   // "[]"
    notes action { pinned mutate setOf("b") }         // shown is still [], so nothing prints
    notes action {                                    // an adoption: sync writes fetched only
        fetched mutate listOf("a")
        fetched update { it + "b" }
    }                                                 // "[b, a]": one recompute for the commit
    println(notes.count.value)                        // "2"
    sub.dispose()
}
```

- **Declaring.** `derivedState(sources) { … }` computes its value from its
  `sources` — declared states, `derived` states or other derived states, of
  this store or another, but not a `computed { }` one — and `merged(local,
  remote) { l, r -> … }` from two states of its store. Both compute the
  initial value at once, on the calling thread, reading its sources from one
  committed cut except where that thread holds a pending write, which it
  reads as any read there does: created inside an action (or frame) that has
  written a state it reads — a source or not — it sees that write, and
  recomputes from committed values once the outermost action or frame on the
  thread ends — so a rollback leaves it explained by its sources.
  Declare one with `by`, which yields the `DerivedState` itself (its
  `getValue` returns it, as `val x by state { … }` yields its `State`), or
  with `=`. A state `compute`
  reads without listing it as a source does not trigger a recompute. The
  legacy `derived(...)` keeps its `Pair` until the 0.7.0 triage (§14.2).
- **Once per entry: it settles.** An `action`, `atomic` frame,
  `suspendAction` or `suspendAtomic` is an entry; one nested in another
  joins the outermost one on its thread. Once the outermost entry has exited
  and released every store it took, each derived state whose sources it
  changed recomputes once — however many sources, on however many stores, in
  however many nested actions or frame participants — and a chain of derived
  states settles in the same pass, each after the derived states it reads, so
  none recomputes from another's previous value. Coalescing is not a mode:
  derived states always settle (the legacy `derived` keeps its per-commit
  recompute until the 0.7.0 triage, §14.2). Inside an entry, a derived state
  therefore reflects none of that entry's writes yet. The recompute commits
  in a transaction of its own on the store the derived state was created on
  (its host): the host's middleware sees it, and its observers fire in the
  normal commit order, and only when the value changes (`==`), as for a
  `distinct` state. It never waits for the host: when another action, frame
  or `suspendAction` holds the host, the recompute is handed to that holder
  and runs when it releases. Nor does it commit writes that may still roll
  back, or a torn pair: a recompute's `compute` reads its sources from one
  committed cut, and any other state at its committed value — never the
  pending writes of an action on its thread — and may read states but not
  write them (a write, `action`, `atomic`, `reset()`, `restore` or `emit` from
  it throws, and is reported like any failing recompute). A `suspendAction`
  or `suspendAtomic` settles on whatever thread it ends on, also when its
  body or its commit resumed elsewhere (on iOS and wasmJs, not inside a
  nested `withContext(dispatcher)` in it: a commit there does not see the
  entry's scope, so the derived states it changes recompute after that
  commit, once per commit rather than once per entry); one holding a
  source's store never holds another entry's recompute back, even one
  parked on the recomputing thread — Android's main thread, `runBlocking`,
  any thread on wasmJs: that recompute runs from committed values, and
  again after the parked action commits. A source value that arrives
  outside any entry — through `bridge` or `observeFrom` — recomputes it at
  once on an idle host. So after the committing call returns the value can
  briefly lag its sources when its host is busy. Read the sources, or a
  `computed { }` state, when you need your own write at once. A throwing
  `compute` (or a middleware rejecting the recompute) rolls that recompute
  back, is reported through `uncaughtObserverHandler`, and leaves the value
  as it was until the next source commit. A feedback loop — an observer of
  a derived state writing one of its sources — is cut after 1,000
  recomputes in one settle and reported through the host's
  `uncaughtObserverHandler`; the next recompute then waits in the host's
  post-commit queue, so the value may lag its sources until the host is
  next used (its next action or frame runs it) or a source changes again.
- **`merged`'s inputs.** `local` and `remote` must be two different states
  its store declares: another store's state, a `derived` or derived state, a
  `computed { }` one or an internal one is refused with an
  `IllegalArgumentException` naming it. Tag `local` `UserAuthored` and
  `remote` `Remote` (§16.4) — each is tagged on its own — or leave them
  untagged: `merged` does not check the tags, they are yours to choose. The
  merged state carries neither tag. A derived state with a `Secret` source
  is `Secret` (its value is withheld like its source's), and never
  `UserAuthored` or `Remote`.
- **Read-only.** `mutate`, `update`, `bridge` and `observeFrom` on a derived
  state throw `IllegalStateException`: its value is what its sources explain.
  Write a source instead.
- **Observing.** `effect`, `:holdfast-coroutines`' `asFlow`, `asStateFlow`
  (whose default scope is the host's), `first` and `awaitValue`,
  `:holdfast-compose`'s `collectAsState` (a recompute that changes the value
  recomposes its readers once; one that leaves it as it was, not at all), and `derived`, `derivedState` and `suspendDerived`
  sources all accept a derived state. In a `:holdfast-testing` timeline its
  recompute is a transaction of its own, whose `EmissionEvent` names its
  backing state; `handle.emissions(NotesStore::shown)` and
  `emitted(NotesStore::shown)` resolve the property to it.
- **Not store state.** A derived state lives in memory only, computed from
  its sources: `snapshot()` does not capture it (a typed read of it from a
  snapshot is refused), `encode()` never writes it, `properties` and
  `taggedStates` do not list it, and `restore` and `reset()` never write it
  — they write its sources, and it recomputes after their commit. As for
  `derived`, an initializer that `reset()` re-runs reads a derived state at
  its pre-reset value (§16.1).
- **Across stores.** Sources on two stores written in one `atomic(...)` or
  `suspendAtomic(...)` frame recompute the derived state once, after the
  frame, and a derived state never holds, nor shows its observers, a torn
  pair — one participant's new value with the other's old one: a frame
  applies every participant inside one write bracket before any of them fans
  out, and every compute — the initial one too — reads its sources from one
  committed cut, so a recompute racing another thread's frame reads that
  frame whole or not at all — for an outermost frame, or one whose
  participants all open a root of their own; a participant that a nested
  frame shares with its enclosing action or frame applies with that one
  (§15.3, §15.4). (Before 0.6.0 such a race could commit a torn
  pair until the frame's own recompute corrected it, and an initial compute
  that read another action's uncommitted write of a state it does not list
  kept that value after a rollback.) The cut covers the sources: a state
  `compute` reads without listing it is read at its committed value of that
  moment, so list what `compute` reads as sources.
- **Disposing.** `dispose()` stops recomputation and releases the source
  subscriptions; a recompute already queued does not commit, and the value
  stays readable, frozen at its last recompute. Disposing the host store
  stops recomputation too, and the next commit of a source on another store
  drops that subscription. Disposing a source's store from one of its own
  observers still lets that commit's recompute run when the entry settles,
  and a recompute still waiting on the disposed store (deferred to its
  holder) runs inside `dispose()`, on the calling thread. Disposing twice is
  safe.

### 16.6 Keyed state families

```kotlin
// Store extension: a family of states, one per key; declare it with `by`.
@ExperimentalStoreApi
fun <K : Any, T : Any> Store<*>.keyedState(
    transformer: Transformer<T>? = null,
    distinct: Boolean = false,
    codec: StateCodec<T>? = null,        // encodes an entry's raw value
    keyCodec: StateCodec<K>? = null,     // encodes its key: a family needs both to be encoded
    tags: Set<StateTag> = emptySet(),    // every entry's tags
    initialize: (K) -> T,
): KeyedStateProvider<K, T>              // provideDelegate declares the family, yields a KeyedState

@ExperimentalStoreApi
sealed interface KeyedState<K : Any, T : Any> {
    operator fun get(key: K): State<T>   // creates the entry on first get; the same State while it lives
    fun getOrNull(key: K): State<T>?     // never creates one
    operator fun contains(key: K): Boolean
    val entries: Map<K, State<T>>        // the live entries, in creation order
    fun evict(key: K)                    // staged like a write: commits or rolls back with its transaction
    fun evictAll()
}

class StoreSnapshot { fun <K : Any> keysOf(family: KeyedState<K, *>): Set<K> }   // added to §16.2's
class Transaction { val stagedEvictions: Set<State<*>> }                         // next to modifiedStates
class EncodedSnapshotView.Families {                                             // §16.3's, now editable
    operator fun get(name: String): Map<String, String?>?                        // encoded key -> text (null: withheld)
    fun put(name: String, entries: Map<String, String?>)
    fun remove(name: String): Boolean
    fun rename(from: String, to: String): Boolean
    operator fun contains(name: String): Boolean
}
```

A keyed state family is one state per key — a draft per document id, a
cursor per feed — declared once, with one initializer that is given the key.
Each entry is created the first time its key is asked for and lives until it
is evicted; while it lives it is an ordinary state, so actions, rollback,
frames, `effect`, `derived`, `derivedState` and bridges work on it unchanged.

```kotlin
@OptIn(ExperimentalStoreApi::class)
class DraftsStore : Store<DraftsStore>() {
    val drafts by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }
}

@OptIn(ExperimentalStoreApi::class)
fun editThenClose(store: DraftsStore) {
    val a = store.drafts["a"]
    a effect { println("a = $this") }                      // "a = "
    store action {
        drafts["a"] mutate "hello"
        drafts["b"] mutate "world"
    }                                                       // "a = hello"
    println(store.snapshot().encode())
    // {"format":"holdfast.store","v":1,"schema":1,"states":{"drafts":{"a":"hello","b":"world"}},"skipped":[]}
    store.drafts.evict("a")                                 // a's observer is dropped, silently
    println(store.drafts.entries.keys)                      // "[b]"
    println(store.drafts["a"] === a)                        // "false": a new entry, from the initializer
}
```

- **Declaring.** `val docs by keyedState<K, T> { key -> … }` declares the
  family under the property's name when the store is constructed, running no
  initializer. Families and states share the store's names: declaring a
  family under a state's name (or the reverse) fails, as a second state of
  one name does, and the same declaration running again (a local delegated
  property, a helper class instantiated twice) binds to the family declared
  first. `transformer`, `distinct`, `codec` and `tags` mean what they mean
  for `state(...)`, for every entry; the refused tag combinations (§16.4) fail
  the family's declaration.
- **Entries.** `docs[key]` returns the key's entry, creating it from the
  initializer the first time — as a declared state is created at its first
  read: the initializer runs once, on the calling thread, reads committed
  values only and may not write (a write, action, `evict` or `reset()` from
  it throws), a cycle through entries throws, and a throwing initializer
  creates nothing and runs again next time. While the entry lives, every
  `docs[key]` returns the same `State`. Creating an entry is not a write: it
  happens at once, even inside an action, and a rollback leaves the entry
  live at its initial value. `getOrNull`, `contains` and `entries` never
  create one. An entry is not a property: `properties`, `getState`,
  `removeState` and `clearStates` do not see it.
- **Eviction is transactional.** `evict(key)` and `evictAll()` stage like
  `mutate` (see the tables below) and drop the transaction's pending write to
  the entry. The transaction's own reads see its staged evictions —
  `contains`, `getOrNull` and `entries` leave the entry out — and the last
  operation on an entry wins: `docs[key]`, or a write to the entry, after
  `evict` in the same transaction cancels the eviction (the dropped write
  stays dropped). Inside a `suspendAction`/`suspendAtomic` body only a write
  cancels it: `docs[key]` there leaves the eviction staged, since nothing
  tells the body's thread from another coroutine's on the same thread (any
  coroutine at all on wasmJs). So does `docs[key]` from an `atomic` frame
  body that does not enroll the store (unless the frame's policy allows
  unenrolled writes): the eviction belongs to an enclosing action, which
  commits whatever the frame does, so a cancel there would escape the
  frame's rollback. A savepoint's evictions merge into its parent on commit and
  vanish on rollback. The commit applies them with its writes, inside one
  write bracket, so a `snapshot()` taken on another thread sees an eviction
  exactly when it sees that commit's writes. `Transaction.stagedEvictions`
  lists what a transaction evicts, for middleware; it is disjoint from
  `modifiedStates`.
- **Snapshots.** `snapshot()` captures every live entry of every family (a
  `SnapshotScope.UserAuthored` capture, of the `UserAuthored` families), and
  `snapshot.keysOf(docs)` lists its keys. Read one entry through its state:
  `snapshot[docs[key]]` (which creates the entry in the store if it is not
  live). `encode()` writes a family with both codecs as a JSON object under
  its name, one member per entry — key text to value text, sorted — and
  lists a family without both as skipped; two keys encoding to one text fail
  the encode. `restore` creates the entries a snapshot holds that are not
  live and stages their values raw; its policy applies to each entry (an
  entry that cannot restore is an issue naming the family, never the key).
  A restore never evicts: to make one exact, `docs.evictAll()` and `restore`
  in one action — the restore's writes cancel the evictions of the entries
  it holds. Entries a failed restore created stay live, at their initial
  values, as states it materialized do. Creating an entry is no commit, so a
  capture lists the entries again when some came to life while commits
  applied; after a few such retries it holds the creation of new entries
  back for the moment it takes to list them and read its cut. It never
  blocks a writer, and a `docs[key]` creating an entry waits only for that
  moment.
- **Tags** apply to every entry, and `State.tags` of an entry is its
  family's. A `Secret` family's values are withheld as a Secret state's are
  (`null` on the wire, `Redacted` when read, `<redacted>` in `render()` and
  test timelines) — but not its keys: keys are addresses, written by
  `encode()`, shown by `render()` and returned by `keysOf`, so never make a
  secret a key. A
  `Remote` family is left out of `encode()` unless `includeRemote`, and a
  sterile restore drops its entries from the snapshot and resets its live
  ones instead. `taggedStates(tag)` lists the live entries of families
  carrying `tag`.
- **`reset()`** re-runs the initializer of every live entry — reading the
  other states' reset values, as a declared state's does — stages the result
  raw where it differs, and never evicts; an entry the same transaction has
  staged for eviction is left to its eviction. An entry an initializer
  creates during the reset (`docs[key]` of a key with no live entry) is
  reset too, so its initializer runs twice: once as it comes to life, from
  committed values, then again from the reset values.
- **`migrate`** (§16.3) edits families as text: `view.families["notes"]`
  returns a copy of a family's entries, and `put`, `remove` and `rename`
  change them. The restore then reads each family with the store family's
  `keyCodec` and `codec`.
- **Names, never keys.** A key can be data (an email, an account id), so the
  library never writes one into a message, a `toString` or a middleware
  sample: an entry prints as `MutableState(Store.docs[*])`, and
  `ProfilingMiddleware` counts writes to any entry under `docs[*]`. (A
  snapshot's `encode()`, `render()` and `keysOf` are where keys do appear:
  they are the snapshot's content, not a message.) In a `:holdfast-testing`
  timeline an entry's write is an `EmissionEvent` naming the entry's `State`;
  find it by identity (`it.state === store.docs["a"]`). A bridge attached to
  an entry is not wrapped by the harness, so its publishes and inbound values
  are not recorded. `shouldMatchSnapshotOf` compares families entry by entry
  and never prints a key.

When `evict` (or `evictAll`) is called:

| Called | What happens |
|---|---|
| inside an action or frame of the store, on its thread | staged in that transaction: it commits, or rolls back, with it |
| outside any action | runs as a one-shot action of its own, as `mutate` does |
| while another thread's action holds the store | waits for the store, then runs as a one-shot action |
| from this store's commit fanout (an observer, a bridge, an event collector) | deferred: once the commit has released the store, the entries live at the call (not evicted meanwhile) are evicted by a transaction of their own, id `Evict`, that never waits for the store — handed to the store's next holder, which runs it once it releases, when the store is busy — the one write that defers rather than failing (§4.4) |
| from another thread while a `suspendAction`/`suspendAtomic` holds the store | joins that transaction before it applies, and throws `IllegalStateException` after — a known gap, closed when the planned "body is running" marker lands |
| inside an `atomic` frame that does not enroll the store | throws `UnenrolledStoreException` (unless the policy allows unenrolled writes) |
| from a state initializer, `migrate` or a derived state's compute | throws `IllegalStateException` |

What an eviction's commit does:

| | The evicted entry | Every other entry |
|---|---|---|
| its `State` | a stale handle: reads its last value; `mutate`, `update`, `bridge` and `observeFrom` throw `IllegalStateException` | live |
| its observers | dropped, with no last notification | untouched |
| its bridge | detached | untouched |
| its `observeFrom` subscriptions | disposed | untouched |
| the next `docs[key]` | creates a new entry, a new `State`, from the initializer | returns the same `State` |

### 16.7 Hydration (`:holdfast-coroutines`)

```kotlin
// :holdfast-coroutines. A store's hydration: seed it, fetch, adopt — once.
@ExperimentalStoreApi
fun <V : Store<V>> V.hydrator(spec: HydrationSpec<V>.() -> Unit): Hydrator<V>   // one per store
@ExperimentalStoreApi
fun Store<*>.hydratorOrNull(): Hydrator<*>?
@ExperimentalStoreApi
suspend fun hydrateEach(vararg hydrators: Hydrator<*>)       // in order; a whole subtree: tree.hydrateAll() (§17.11)

@ExperimentalStoreApi
class HydrationSpec<V : Store<V>> {
    fun base(block: V.() -> Unit)                           // runs in the seed transaction
    fun <F> refresh(fetch: suspend (V) -> F): HydrationRefresh<V, F>
}

@ExperimentalStoreApi
class HydrationRefresh<V : Store<V>, F> {
    infix fun adopt(block: V.(fetched: F) -> Unit)          // a savepoint that may write Remote states only
}

@ExperimentalStoreApi
class Hydrator<V : Store<V>> {
    val state: State<Hydration>                             // read-only, observable, a derivedState source
    val current: Hydration
    suspend fun hydrate(scope: CoroutineScope = …)          // defaults to the store's Store.scope
    fun invalidate(): TransactionResult<Unit>               // back to Detached
    fun stageInvalidate()                                   // the same, staged into the caller's action
    suspend fun awaitSettled(): Hydration                   // once no refresh is in flight
}

@ExperimentalStoreApi
sealed interface Hydration {
    data object Detached : Hydration
    data object Seeded : Hydration                          // seeded; a refresh is in flight
    data object Hydrated : Hydration
    data class Failed(val cause: Throwable) : Hydration
}
```

A hydrator turns a screen's "seed the store, then sync it" into one call that
is safe to make on every entry. `base { }` seeds the store — typically a
`restore` of bundled seed data (§16.2) — `refresh { }` fetches, and
`adopt { }` writes what it fetched into the store's `Remote` states (§16.4).
`hydrate()` seeds once and fetches once, and does nothing while that is in
flight or done, however many callers ask at once. It lives in
`:holdfast-coroutines`, and is experimental like the rest of this chapter.

```kotlin
interface HistoryApi {
    suspend fun fetchHistory(): List<String>
}

@OptIn(ExperimentalStoreApi::class)
class HistoryStore(api: HistoryApi) : Store<HistoryStore>() {
    val pinned by state(tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val entries by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { entries mutate listOf("bundled") }          // in the seed transaction
            refresh { api.fetchHistory() } adopt { fetched ->   // on hydrate()'s scope, once seeded
                entries mutate fetched                         // Remote states only
            }
        }
}

@OptIn(ExperimentalStoreApi::class)
suspend fun openHistory(store: HistoryStore, scope: CoroutineScope) {
    store.hydration.state effect { println("phase: $this") }   // "phase: Detached"
    store.hydration.hydrate(scope)                              // "phase: Seeded"
    store.hydration.hydrate(scope)                              // in flight already: does nothing
    println(store.entries.value)                                // "[bundled]"
    println(store.hydration.awaitSettled())                     // "phase: Hydrated", then "Hydrated"
    println(store.entries.value)                                // "[a, b]"
}
```

What `hydrate()` does, by phase:

| Phase | `hydrate()` |
|---|---|
| `Detached` | runs `base { }` and moves to `Seeded` in ONE transaction (id `HydrationSeed`), which also marks the refresh in flight; once that transaction has committed — never when it rolled back — launches the refresh on `scope` and returns |
| `Seeded` | nothing: a refresh is in flight (unless its failure could not be recorded — see below — which it retries as from `Failed`) |
| `Hydrated` | nothing |
| `Failed` | moves back to `Seeded` in one transaction (id `HydrationRetry`), WITHOUT running `base { }`; once it has committed, launches the refresh again |

What moves the phase, and the transaction middleware sees it in:

| From | When | To | Transaction |
|---|---|---|---|
| `Detached` | `hydrate()` | `Seeded` | `HydrationSeed`: `base { }`, then the persisted overlay if there is one (§16.8), then the phase |
| `Failed` | `hydrate()` | `Seeded` | `HydrationRetry` |
| `Seeded` | the refresh returned, and `adopt { }` succeeded | `Hydrated` | `HydrationAdopt`, with `adopt { }` as its savepoint `Adopt` |
| `Seeded` | `adopt { }` threw, or wrote a state that is not `Remote` | `Failed(cause)` | `HydrationAdopt` (its savepoint rolled back whole) |
| `Seeded` | the refresh threw (a cancelled `scope` included), or a middleware rejected `HydrationAdopt` | `Failed(cause)` | `HydrationFailure` |
| `Seeded` | as above, and a middleware rejected `HydrationFailure` too | stays `Seeded`, nothing in flight | none: the rejection goes to the store's `uncaughtObserverHandler` (reported once the gate has released the store; a throwing handler is ignored), `awaitSettled()` returns `Failed(cause)`, and the next `hydrate()` retries (`HydrationRetry`) |
| any | `invalidate()`, `stageInvalidate()`, the store's `reset()` | `Detached` | `HydrationInvalidate` (a savepoint inside an action), the caller's action, `Reset` |

- **One decision at a time.** Every hydration decision — seed, retry, adopt,
  record a failure — reads the phase and commits the next one while the
  hydration gate holds the store: its serializer, the lock `suspendAction`,
  `suspendAtomic` and blocking actions share, held only across that
  decision's transaction. So no two decisions on one store interleave, and
  none acts on a phase another transaction has since moved (each decision's
  transaction reads the phase again, under the store's transaction lock):
  two concurrent `hydrate()` calls seed once and fetch once, and fifty on a
  failed hydration refetch once — the in-flight mark commits in the very
  transaction that decides to fetch. The gate takes the store politely: when it is busy, it
  backs off — a yield, then delays doubling from 1 ms to 32 ms, in coroutine
  time — instead of queueing on the store's mutex (and waits the same way,
  before deciding, for a blocking action that took the store before the
  store's mutex was installed), so it never spins, never
  holds up the store's other callers, and never reads the store's clock. It
  drains the store's post-commit queue once it releases the store, like every
  holder of the store, and the derived states a decision changes settle once
  it has (§16.5).
- **Where to call `hydrate()` and `awaitSettled()`.** From a coroutine,
  outside every transaction. Both throw `IllegalStateException` inside an
  action, an `atomic` frame, a `suspendAction` or a `suspendAtomic` of any
  store — body or commit, an observer included, and a child coroutine of a
  `suspendAction`/`suspendAtomic` body (or a plain `runBlocking { }` on a
  blocking body's own thread). On its own store, `hydrate()` would wait for
  itself forever, and on another its seed would commit outside the
  enclosing transaction and its rollback; `awaitSettled()` waits for a
  refresh whose settle must take the store, which the enclosing transaction
  holds, so it would wait forever too — await it before opening the
  transaction. Call `hydrate()` after the action returns, or launch it on a
  dispatcher that does not run it inline:
  `store.scope.launch(Dispatchers.Default) { hydration.hydrate() }`. On
  `Dispatchers.Unconfined`, or `Dispatchers.Main.immediate` on the main
  thread, a `hydrate()` launched from inside the action runs inline, still
  inside the transaction, and throws — failing that coroutine through its
  scope's exception handler. Not detected: a coroutine launched on another
  scope that a body then waits for
  (`store.scope.launch { hydration.hydrate() }.join()` inside
  `store.suspendAction { }`), and a coroutine a blocking `action`/`atomic`
  body runs on another thread (`runBlocking(Dispatchers.Default) { … }`, or
  a `launch(Dispatchers.X)` inside the body's `runBlocking`) — a blocking
  entry marks only its own thread. On the same store these wait for the
  body forever; on another store the seed commits outside the enclosing
  transaction. To detach inside an action, call `stageInvalidate()`.
- **The scope.** `hydrate(scope)` runs the refresh on `scope`, which defaults
  to the store's `Store.scope` — its override, else its `bindToScope`
  binding, else `Store.defaultScope`. There is no `context(CoroutineScope)`
  overload, so inside a coroutine the default is still the store's scope,
  never the ambient one. The refresh is launched with `CoroutineStart.ATOMIC`
  once the deciding transaction has committed, so a hydration marked in
  flight always gets its refresh: a caller cancelled after that transaction
  committed still launches it, and a scope already cancelled runs it into its
  cancellation. A cancellation of `scope` while the refresh runs fails the
  hydration with that `CancellationException`; the next `hydrate()` retries.
  A caller cancelled while it waits for the store changes nothing.
- **Adopt writes `Remote` only.** `adopt { }` runs as a savepoint of the adopt
  transaction and is checked when it returns: every write it staged into the
  store — directly, in nested actions, through a `restore` or a `reset()` —
  and every keyed-entry eviction must be of a `StateTag.Remote` state (§16.4)
  or family (§16.6). Anything else fails the adoption with an
  `IllegalStateException` naming the states (never a value or a key), rolls
  it back whole, its `Remote` writes included, and moves the phase to
  `Failed` in the same adopt transaction. `removeState`/`clearStates` throw
  inside it: they drop states at once, where no rollback reaches. A write to
  another store is not part of the adoption; it commits on its own, as from
  any action. Pair it with `merged(local, remote)` (§16.5), so that what the
  user writes is never what sync adopts.
- **Back to `Detached`** only through `invalidate()` (an action of its own,
  id `HydrationInvalidate`; a savepoint inside an action), `stageInvalidate()`
  (staged into the caller's action on this thread) and the store's `reset()`
  (§16.1), which detaches inside its own transaction, since initial values are
  not hydrated ones — each commits, or rolls back, with its transaction. The
  store's states keep their values, and the next `hydrate()` runs
  `base { }` again. A refresh still in flight is cancelled once the detach
  commits, and whatever it brings is discarded, even when it ignores the
  cancellation. A sterile `restore` (§16.4) resets the `Remote` states but
  does not detach: `stageInvalidate()` in the same action to fetch again.
- **Not store state.** `state` is a state of the store — observe it with
  `effect`, the coroutines flows or `collectAsState`, and use it as a
  `derivedState` source — but it is the hydrator's own: `mutate`, `update`,
  `bridge` and `observeFrom` on it throw, naming the hydrator, and no
  `snapshot()`, `restore`, `properties` or `taggedStates` sees it
  (`snapshot[hydration.state]` throws); `reset()` re-runs no initializer for
  it, and only detaches it (above). `current` is its value. A commit notifies
  its observers before those of the store's other states, so the hydrator's
  own bookkeeping sees every commit that applied, even one whose fanout a
  throwing observer ends through a rethrowing `uncaughtObserverHandler`: a
  `hydrate()` whose seed or retry applied but whose fanout failed that way
  still launches the refresh, then throws.
- **Health flags need no frame.** Each hydration commits on its own store, so
  a flag over several stores' phases is a `derivedState` of their `state`s,
  which settles after every change of either (§16.5):

```kotlin
@OptIn(ExperimentalStoreApi::class)
fun sessionExpired(app: AppStore, history: HistoryStore, profile: ProfileStore): DerivedState<Boolean> =
    app.derivedState(history.hydration.state, profile.hydration.state) {
        listOf(history.hydration.current, profile.hydration.current)
            .any { (it as? Hydration.Failed)?.cause is SessionExpired }
    }
```

- **Time.** `base`, `refresh` and `adopt` read time through the store's
  `clock` (§13), so a bound fixed clock makes what they stamp deterministic.
  The hydrator never reads that clock itself.
- **Dispose.** After `dispose()`, `hydrate()`, `invalidate()`,
  `stageInvalidate()` and `awaitSettled()` throw `IllegalStateException`; a
  refresh in flight is cancelled and never adopted (nor is one that finishes
  anyway); an `awaitSettled()` waiting then throws; and `state` and `current`
  keep their last value.
- **One per store.** A second `hydrator { }` on a store throws;
  `hydratorOrNull()` finds the one it has, on any `Store<*>`. `hydrateEach(a, b, …)` hydrates
  several, in order, each on its store's scope — every seed committed and
  every refresh launched when it returns — carrying on past a failure and
  throwing the first once all have run. `store.tree.hydrateAll()` (§17.11)
  hydrates a store and its whole subtree with one report.
- **`awaitSettled()`** waits until no refresh is in flight and returns the
  phase then — `Hydrated`, `Failed`, or `Detached` (never hydrated, or
  invalidated meanwhile) — reading committed phases only. The one exception
  is a refresh whose failure a middleware refused to let the hydrator record
  (the `HydrationFailure` row above): nothing is in flight, so it returns
  `Failed(cause)` while `current` still reads `Seeded`, and the next
  `hydrate()` retries that refresh.

### 16.8 The persisted overlay (`:holdfast-coroutines`)

```kotlin
// :holdfast-coroutines. What the user authored, persisted under one key and put back over base { }.
@ExperimentalStoreApi
class HydrationSpec<V : Store<V>> {
    fun overlay(kv: SuspendingKvStore, key: String, sizeLimit: Int = 8192)   // once; the key is pinned
}

@ExperimentalStoreApi
class Hydrator<V : Store<V>> {
    val overlayKey: String?                                 // the pinned key; null without an overlay
    suspend fun clearOverlay()                              // remove what it wrote; nothing else stops
}

@ExperimentalStoreApi
class OverlayException : IllegalStateException {          // reported through uncaughtObserverHandler
    val key: String
}
```

A hydrator's overlay keeps what the user authored — the store's
`UserAuthored` states and keyed families (§16.4, §16.6) — across process
death. It writes them under one key of a `SuspendingKvStore` after every
commit that changes one, and puts them back in the seed transaction, after
`base { }`. So a boot runs in the order issue #20 asks for: base, then the
overlay, then the refresh. The overlay wins even when `base { }` restores a
snapshot that holds the same states — and it replaces each `UserAuthored`
keyed family it holds, so an entry the user evicted never comes back from
base's seed data — and the refresh adopts into `Remote` states only
(§16.7), so it can never overwrite what the overlay put back.

```kotlin
interface InboxApi {
    suspend fun fetchInbox(): List<String>
}

@OptIn(ExperimentalStoreApi::class)
class ComposerStore(prefs: SuspendingKvStore, api: InboxApi) : Store<ComposerStore>() {
    val draft by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val inbox by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { restore(Seeds.composer) }                    // bundled seed data, a draft included
            overlay(prefs, key = "composer.overlay")            // then what the user wrote, over it
            refresh { api.fetchInbox() } adopt { fetched -> inbox mutate fetched }
        }
}

// First run: no blob yet, so the seed is base { } alone.
//   composer.hydration.hydrate()                   // draft: "Hello," (bundled)
//   composer action { draft mutate "Dear Ada," }   // written under "composer.overlay" soon after
// After process death, a new ComposerStore on the same prefs:
//   composer.hydration.hydrate()                   // base restores "Hello,"; the overlay puts "Dear Ada," over it
```

Where the overlay stands decides both directions:

| Standing | Since | A seed puts back | A commit that changes a `UserAuthored` state |
|---|---|---|---|
| not loaded | the hydrator was created (a seed that rolled back changes nothing) | the blob it reads under the key, before taking the store | is not written |
| loaded | a seed applied the blob, or found none | the `UserAuthored` values the store held when the seed began, into what `base { }` changed — the store is what the overlay persists, so the key is not read again | is written |
| kept | a seed could not apply the blob | the blob it reads again | is not written: the blob is never written over |

- **Inbound.** The seed transaction (`HydrationSeed`) runs `base { }`, then
  restores the overlay as a savepoint (id `Restore`, which middleware sees),
  then moves the phase to `Seeded`: all of it commits together, or none of
  it. Only the entries of states and keyed families the store declares
  `UserAuthored` are restored — after `migrate` has upcast an older blob
  (§16.3), so a renamed state is restored under its new name — and a name
  the store no longer declares is ignored. A blob holds no `Remote` state's
  value, and a `Remote` or untagged state's text from another writer is never
  restored — nor is an entry `migrate` renames into one, since names are
  matched after it. The overlay replaces the live entries of each
  `UserAuthored` keyed family the blob holds: an entry `base { }` created
  (or restored) that the blob does not hold is evicted in the seed, so the
  family holds the blob's keys and no others; a family the blob does not
  list — one it skipped for want of a codec — keeps base's entries, and so
  does every family when the blob is kept. A later seed of a loaded overlay
  puts the held values back only into what `base { }` changed — the states
  it wrote, the entries it evicted — and evicts the entries it created, so a
  value a bridge delivered from another thread while the seed ran is kept.
  What the store held before the first seed is not what the user authored,
  and the seed puts the overlay over it: hydrate before the user edits.
- **Outbound.** From the seed that loads it on, every commit that changes a
  `UserAuthored` state, or evicts an entry of a `UserAuthored` family, is
  written — an action's, a frame's, a `suspendAction`'s, a `reset()`'s, a
  bridge's inbound value (one that lands while a seed runs, too) — except a
  seed's own, which holds what the overlay (or `base { }`) put there. The
  writer learns of a change from the commit's observer fanout, so a commit
  whose fanout skips the overlay's observer is written only with the next
  change of a `UserAuthored` state: one whose `Transformer.get` throws for
  the new value (in a commit's fanout, or on a bridge's inbound value, which
  then notifies no observer), or one where an earlier observer fails through
  an `uncaughtObserverHandler` that throws, which ends the fanout. The blob
  is exactly what `snapshot(SnapshotScope.UserAuthored).encode()` returns:
  the R1 store envelope (§16.2) of the `UserAuthored` states. A state needs
  a codec to be written
  (`state(codec = …, tags = setOf(StateTag.UserAuthored))`): the blob lists
  one without a codec as skipped, and a restore leaves it as it is. `Remote`
  states are never written, and a `Secret` state cannot be `UserAuthored`.
  (A `UserAuthored` state `removeState`/`clearStates` dropped and a read
  brought back is watched again from the next seed.)
- **The writer.** One coroutine at a time, launched on the store's
  `Store.scope` — never the scope `hydrate()` was called with, so cancelling
  that scope stops the refresh, never the writer. It coalesces: while it
  writes, later commits wait for one more write, of the latest values, and it
  skips a blob identical to the last one it put. Between bursts nothing of
  it runs, so a scope has nothing of the overlay to wait for. It writes
  behind the commit: a process that dies before the writer has run loses
  that commit's change. `dispose()` stops it.
- **A blob that cannot be applied is kept.** Text `StoreSnapshot.decode`
  rejects, a snapshot of a newer schema than the store's (or one its
  `migrate` throws on), or one the restore rejects (a type mismatch, a codec
  that cannot decode an entry) is reported as an `OverlayException` through
  the store's `uncaughtObserverHandler` — naming the store and the key, never
  a value; its cause is the value-free `SnapshotFormatException`,
  `SnapshotMigrationException` or `RestoreRejectedException` — and the seed
  commits `base { }` without it. Nothing is written under the key until
  `clearOverlay()` removes the blob, or a later seed (after `invalidate()`)
  reads one it can apply; until then the user's changes live in memory only.
- **A failing read** of the key makes `hydrate()` throw what the key-value
  store threw, before anything is decided: the phase stays `Detached`, and
  the next `hydrate()` reads again.
- **Size.** A blob longer than `sizeLimit` characters — 8192 by default, the
  most a `java.util.prefs` value holds — is reported and not written, so a
  size-limited store never throws; the key keeps the last blob written. A
  failing `put` is reported naming only the exception's class (a store's
  message may quote the value it failed to write), and a write whose scope
  was cancelled is reported too; the next change writes again. A capture or
  codec that fails while writing (an initializer, or a `StateCodec.encode`,
  that throws) is reported the same way — the codec's
  `IllegalStateException` from `encode()` is the only cause chained — the
  key keeps the last blob written, and the next change writes again. A
  handler that throws for any of these reports is ignored. No report is made
  while the hydration gate holds the store, so a handler may open an action
  on it (inside a commit's fanout, where a writer a dispatcher runs inline
  reports, a blocking action on the store returns an `Error`).
- **`clearOverlay()`** removes the key and drops every write not made yet.
  Nothing else stops and no state changes: the store keeps its values, the
  phase stays as it is, and the next commit that changes a `UserAuthored`
  state writes the blob again. A kept blob is gone with it, so the overlay
  writes again. To forget what the user authored, `reset()` the store, then
  `clearOverlay()`: the `UserAuthored` states hold their initial values and
  the key is empty. The overlay stays loaded, so the seed that follows (the
  `hydrate()` a `reset()` calls for) puts those initial values back over
  `base { }`, where a fresh install shows base's own. A new process, finding
  no blob, seeds `base { }` alone, so base's own `UserAuthored` values come
  back only then — unless a commit that changes a `UserAuthored` state
  writes the blob first. It takes the store under the hydration gate,
  briefly, so it throws inside an action, frame or suspending entry, as
  `hydrate()` does; once the key is removed, the rest runs even if the
  caller is cancelled.
- **The key is pinned.** It never derives from the store's class or state
  names, so renaming either cannot orphan a blob. `overlayKey` returns it,
  and so does the hydrator's store attachment (`persistenceKeys`), which
  the store tree's persisted-name self-check reads (§17.7).
- **Dispose.** After `dispose()`, `clearOverlay()` throws
  `IllegalStateException` like the other entrypoints, and `overlayKey` keeps
  answering.

## 17. The store tree (experimental)

Issue #21 lets a store declare other stores as its children, next to its
states: `val settings by store { SettingsStore() }` beside
`val theme by state { "light" }`. A parent is an ordinary store — it has
states, actions, middleware and a hydrator of its own — and every child
keeps its own actions, middleware and locks. Any store's `tree` is its view
of the subtree under it, and the tree adds what a single store cannot do
alone: consistent captures of a subtree, restore and reset over a subtree
as one frame, a text format, a value kept current, tree middleware, a test
fixture and hydration. Everything is addressed by node and state identity;
strings appear only in `encode()` and `render()`. Everything in this
chapter is `@ExperimentalStoreApi`: every declaring store and call site
opts in (`@OptIn(ExperimentalStoreApi::class)`, or the module-wide
`-opt-in=com.vynatix.holdfast.ExperimentalStoreApi` compiler flag, which
the examples below assume).

### 17.1 Declaring children

```kotlin
class SettingsStore : Store<SettingsStore>() {
    val theme by state { "light" }
}

class SignInStore : Store<SignInStore>() {
    val email by state { "" }
}

class ProfileStore : Store<ProfileStore>() {
    val bio by state { "" }
}

class ThreadStore(val id: String) : Store<ThreadStore>() {       // an ordinary store: no token, no tree argument
    val title by state { "thread $id" }
}

interface Draft {                                                // the typed face of an inline child: a plain interface
    val text: State<String>

    fun edit(value: String)
}

object App : Store<App>() {
    val openThreads by state { emptySet<String>() }                         // a parent is a store: it has states
    val settings by store { SettingsStore() }                              // one child, node "settings"
    val session by group { listOf(SignInStore() named "sign-in", ProfileStore()) }   // leaves "sign-in" (pinned), "Profile"
    val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }    // keyed: the factory is declared once
    val draft by store<Draft> {                                            // the type argument is required
        object : NodeStore(), Draft {
            override val text by state { "" }

            override fun edit(value: String) {
                text mutate value
            }
        }
    }
}

fun useTheTree() {
    println(App.tree.children().map { it.name })            // "[settings, session, threads, draft]": the lambdas run now
    val t1 = App.threads.create("t1")                         // runs the factory; live once create returns
    println(App.threads.getOrCreate("t1") === t1)             // "true": the same store, the factory not run again
    println(App.tree.nodeOf(t1)?.name)                        // "t1"
    println(App.tree.stores(App.session).size)                // "2": sign-in, then Profile
    App.draft.edit("hi")                                      // an inline child, written through its own method
    println(App.draft.text.value)                             // "hi"
    println(App.tree.nodeOf(ThreadStore("bare")))             // "null": built outside the factory, it is in no tree
    t1.dispose()                                              // leaves the tree
    println(App.threads["t1"])                                // "null"
}
```

- **Declared at construction, built on first use.** A `store { }` or
  `group { }` declaration registers a child entry when the property binds
  — in the parent's constructor, or an `object`'s initializer — and runs
  nothing. The lambda runs on the property's first read, or when a
  tree operation needs the subtree (`tree.children()`, `stores`, `nodeOf`,
  `snapshot`, `restore`, `reset`, `decode`, `verifyPersistedNames`,
  `middlewares`, the first read of the tree's value, `hydrateAll`,
  `track(tree)`). `children()` runs the child declarations if they have not
  run yet; materializing a child runs no state initializer by itself — but
  once a tree value at or above the child is in use (read or observed),
  the settle that follows the attach captures the new store, which
  materializes its never-read states as `snapshot()` does (§17.8).
  Concurrent first reads may EACH run the lambda: the first result to
  attach wins, every reader gets that one store, and each other run's
  stores — those built during that run that nothing else holds — are
  disposed, never attached or announced. A lambda that throws leaves the
  child unbuilt, and the next read runs it again. A keyed branch exists
  from its declaration on; its factory runs per key (§17.2).
- **What a child can be.** `store { }` must produce a store: a store
  class, or an inline `object : NodeStore(), Draft { … }` behind a plain
  consumer interface. Anything else fails the first read ("store { } must
  produce a Store; an inline child is object : NodeStore(),
  YourInterface { … }"). An inline child needs the type argument
  (`store<Draft> { … }`); without it the anonymous object's type would have
  to escape the property, and the declaration does not compile. From
  outside, write it through the interface's own methods (`App.draft.edit(…)`)
  or cast it to its store: `(App.draft as NodeStore) action { App.draft.text
  mutate "x" }`. Inside the object, its own `action { }` has the
  `NodeStore` as receiver and the `@StoreActionDsl` marker hides the
  object's members behind it, so a method that writes inside an action
  names the object explicitly: `val self = this; action { self.text mutate
  v }`. `group { }` lists stores; `keyed<K, S> { }` declares a keyed branch
  (§17.2).
- **One parent per store, and no cycles.** A store hangs under one parent.
  Declaring a store that already has a parent fails when the second
  declaration materializes, naming both parents ("… already belongs to
  App/settings; it cannot also be declared under Other/prefs — a store has
  one parent"); the first stays attached. A declaration that would make a
  store its own ancestor fails naming the path; so does a child lambda
  that returns a disposed store, and a group that lists a store twice or
  lists a disposed one. A failure before the child attaches leaves nothing
  attached, and the next read retries; when it comes after the lambda
  returned, the stores that run built (and nothing else holds) are
  disposed — a store that existed before the run never is.
- **Names come from the program, or from pins.** A `store { }` child, a
  `group { }` and a `keyed { }` branch are named by their property
  (`NameOrigin.Property`) or by a `named =` pin — `store(named =
  "prefs-v2") { … }`, `group(named = "session") { … }`, `keyed<K, S>(named
  = "threads") { … }` (`Pinned`). A group's leaves are named by their class
  minus `Store` (`ClassName`: `ProfileStore` → `Profile`) unless the
  lambda pins one: `SignInStore() named "sign-in"` (`Pinned`; `named` is an
  infix on the group lambda's receiver, pins that one instance and answers
  it, so pinned and plain stores mix in one list). A keyed leaf is named by
  its key's encoding (`Key`). A store with no parent — a `tree`'s receiver
  that nobody declared, or a subtree root — is named by its class (`App`).
  Sibling names must be unique, which the declaration checks when the
  property binds; two group leaves with one name — two stores of the same
  class, or of two classes whose simple names collide — or a listed class
  without a simple name (anonymous, local) need a pin ("… pin one of them
  with named"). `StoreNode.nameOrigin` records where a name came from; the
  persisted-name self-check reads it (§17.7).
- **Lookups take nodes and stores, never strings.** `App.tree.children()`
  lists the declared children in declaration order (each `store { }`
  child's `LeafNode`, each group's `Branch`, each keyed `KeyedBranch`, with
  or without entries); `App.tree.stores(node)` the live stores of the
  subtree at a node, in tree order (the receiver first when the node is its
  own); `App.tree.nodeOf(store)` a store's node when it is the receiver or
  under it; `App.threads[key]` and `App.threads.entries()` the keyed ones.
  Any store's own node is `store.tree.node`, and `store.tree.parent` is the
  node it hangs under (`null` for a subtree root). A `tree` member given a
  node outside the receiver's subtree throws `IllegalArgumentException`.
- **Cycles throw, they never deadlock.** A child lambda (and a keyed
  factory) runs holding NO lock or latch: the reading thread only marks it
  as running. A lambda that needs the property it is building on the same
  thread — directly, or through a state initializer, another child or a
  keyed factory — is a cycle and throws ("Materialization cycle: …",
  naming every declaration, factory and initializer in order). Across
  threads nothing waits on a lambda, so a mutual need cannot hang: each
  thread runs the other declaration's lambda itself and meets its own
  mark. State initializers keep their latch (and their cross-thread cycle
  report).
- **The lambda is ordinary code, run on the reading thread.** It holds
  nothing of the tree's — only what that thread already holds — so a
  child's constructor may run actions on any store (the parent included),
  register effects and install middleware, even while another thread
  reads the same child from inside one of the parent's actions. Inside an
  action the lambda runs IN that action: it sees the action's uncommitted
  writes. Attaching is structural, not transactional: a rollback of that
  action does not undo it. A child first read inside a state initializer,
  a `migrate` or a derived state's compute inherits that no-write region:
  a constructor that opens an action or writes fails there (the
  declaration stays retryable) — first-read such children outside that
  code. Because racing first reads may each run it, keep side effects
  other than building the child out of it.

### 17.2 Keyed stores: the factory is declared once

`val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }`
declares a keyed branch with its factory. `App.threads.create(id)` runs the
factory for a key and attaches the store it returns; `getOrCreate(id)`
answers the live store for the key, or creates it. The store class is an
ordinary store — no token, no constructor argument for the tree — and a
`ThreadStore(id)` constructed anywhere else is simply in no tree.

- **Liveness is the attach.** A keyed store is live — found by
  `threads[key]`, `entries()`, `tree.stores`, captures, the value, reset and
  tree middleware — once the factory has returned it and it attached.
  The factory runs on the calling thread holding no lock of the tree's
  (like a child lambda, §17.1: inside an action it sees that action's
  uncommitted writes, and a rollback does not undo the attach), so racing
  creators of one key may each run it: the first store to claim the key
  wins, `getOrCreate` on every other thread returns that store, a racing
  `create` fails with "already exists" (use `getOrCreate` to share one),
  and each loser disposes the store its own run built — never attached,
  never announced. A factory that needs its own key again on the same
  thread is a cycle error. The winner's attach ends only after it has
  synced the new store's tree middleware and told the membership
  listeners, so `threads[key]`, `entries()` and `getOrCreate` on another
  thread never hand out a store those do not cover yet (they park for
  that moment, which runs no user code); a listing (`tree.stores`, a
  capture) may include it from its registration on, a moment earlier.
- **A failing factory leaves no entry.** A throwing factory leaves no
  entry and no listener hears of it; whatever it built before throwing is
  its own. A factory that returns a store of another class, a disposed
  store, or a store that already has a parent — or whose parent disposed
  meanwhile — fails the call, and the store that run built is disposed
  (never a store that existed before the run, nor one another parent
  holds). Either way a retry runs the factory again.
- **Factory code is ordinary store code.** A constructor may run an
  `action` on the new store; inside a Strict `atomic` frame it may not (the
  new store is not enrolled), exactly as outside a tree. A factory may
  itself `create` another keyed store; the inner one attaches first.
- **A create racing the parent's dispose.** If the parent is disposed after
  the new store attached, `create` still returns it, live and detached: a
  subtree root (§17.3). Only a dispose that lands before the attach fails
  the `create` ("… disposed").
- **Keys.** `String` keys get a key codec by default; pass one for other
  key types (`keyed<Long, ThreadStore>(keyCodec = LongCodec) { … }`).
  Without one the branch works but is never encoded (§17.7). A key codec
  that throws fails `create`/`getOrCreate` with an `IllegalStateException`
  naming the parent and the branch, never the key.
- **Nothing in the tree opens a transaction.** `create`, lookups,
  materialization and `dispose` take only the tree's own locks (§10.1),
  and none of them is held while a factory or child lambda runs, so they
  work from inside actions, frames and observers. `threads[key]`,
  `entries()` and `tree.children()` are not plain field reads: they may
  park for a moment (another thread finishing an attach), and `children()`
  runs the child lambdas not run yet.
- **Disposing keyed stores.** `App.threads.dispose(id)` disposes the live
  store under a key (`false` when there is none) and `disposeAll()` every
  live one; a disposed keyed store leaves the branch either way.

### 17.3 Dispose and detach

Disposing a store detaches it from its parent and releases its
`store { }` and `group { }` children as subtree roots; its keyed branches
dispose the stores their factories built, unless declared with
`onParentDispose = KeyedDisposal.Release`.

- **A disposed child leaves its parent.** `threads[key]` and `nodeOf`
  answer `null` from then on; a group keeps listing the store in
  `Branch.stores` but no longer attaches it; a `store { }` property is a
  stable `val`: it keeps answering the disposed store (the lambda never
  runs again), so that instance stays reachable — and uncollectable — for
  as long as the parent is, while a disposed keyed store and a released
  child hold no such reference and can be collected. The child's
  `LeafNode` keeps the place it had — parent, name and key — so captures
  and test timelines taken while it was live still identify it. This
  holds when the child is disposed from inside its own action too.
- **A disposed parent releases its `store { }` and `group { }` children.**
  Each such direct child becomes a subtree root and keeps working: its
  store, states, own children and own middleware stay; its `tree.parent`
  is `null` and its name is its class-derived one again. The parent's
  listeners, value and tree middleware stop covering it — a child keeps the
  middleware its remaining ancestors installed. A released child can be
  declared under another parent; a disposed store never can. The lambdas
  that built these children may have handed out or shared what they
  built, so the tree does not assume it owns them.
- **A disposed parent disposes its keyed stores.** Every store of a keyed
  branch was built by the branch's factory, so the branch owns it: when
  the declaring store disposes, each live keyed store is first released
  like any child, then disposed — never under a lock of the tree's: inside
  an action or frame when that entry settles (after it released every
  lock, so a parent disposed from inside its own action never deadlocks on
  its children), else right after the parent's dispose. Declare a branch
  with `keyed<K, S>(onParentDispose = KeyedDisposal.Release) { … }` to
  release its stores as subtree roots instead.
- **After `dispose()`.** `store.tree` throws, and so does every member of a
  `StoreTree` handle taken before, except `node` and `parent` (the place
  the store had), `removeMiddleware` (`false`) and the value, which answers
  the last tree when it was read before the dispose. The parent's child
  properties throw too. A thousand keyed create/dispose cycles leave no
  entry, listener bookkeeping, observer or middleware behind.

### 17.4 Declaration rules, `object` stores and test hygiene

- **`object` stores.** `object App : Store<App>()` is the natural shape
  for an app's top store: its declarations register on the object's first
  access and no child code runs then. A child may be an `object` too
  (`store { SettingsStore }`), under one rule: a child object may reference
  its parent, never the parent's delegate for itself. With
  `object SelfApp : Store<SelfApp>() { val settings by store { SelfLeaf } }`
  and `object SelfLeaf : Store<SelfLeaf>() { val back = SelfApp.settings }`,
  reading `SelfApp.settings` first is a cycle — on the JVM an
  `ExceptionInInitializerError` whose cause names the child declaration
  `SelfApp.settings`, after which `SelfLeaf` is unusable for the process —
  and touching `SelfLeaf` first attaches a half-initialized object, because
  the JVM publishes an object's instance before its property initializers
  run. Reading anything else of the parent (`SelfApp.tree.node.name`) is
  fine. On the JVM a declaration that throws (two siblings with one name)
  poisons the object for the process; Kotlin/Native reports such failures
  differently.
- **Declare through the store itself.** A `store`/`group`/`keyed` call made on one
  store and delegated from another store's property fails when the
  property binds.
- **Tests.** Use per-instance class stores
  (`class TestApp : Store<TestApp>() { val settings by store { SettingsStore() } }`),
  one parent per test, so nothing leaks between tests: an `object` keeps
  its children, keyed entries and states for the whole process.
- **Opt-in.** A declaring store, a keyed store class and every call site
  need the experimental opt-in; prefer the module-wide `-opt-in` flag over
  per-site annotations.
- **For library code** (`@StoreInternalApi`): a `LeafMembershipListener`
  added through `store.internalAddMembershipListener(listener)` hears
  `onAttached(leaf)` for every store that joins the store's subtree, at any
  depth (a keyed store's after its factory returned, before `threads[key]`
  answers it on any other thread), and `onDetached(leaf)` for every store
  that leaves it —
  `leaf.store` is `null` when that store itself was disposed, and still set
  when an ancestor between them was. `onDetached` never precedes the
  matching `onAttached` and is never sent for a store whose construction
  failed, but a deep descendant can hear it twice when two disposes race,
  so it must be idempotent. A callback runs under whatever locks the
  attaching or disposing caller holds and may only mark and schedule:
  never open an action, frame, `reset` or `restore`, materialize a child,
  or re-adopt the store it is told about.

### 17.5 Tree snapshots and typed reads

`App.tree.snapshot(node = App.tree.node, scope = SnapshotScope.All)`
captures the subtree at a node — the store there, if any, and every live
store under it — as ONE consistent cut and returns a `TreeSnapshot`: the
nodes as of the capture, with a `StoreSnapshot` (§14.1, §16.2) for every
store. A parent's own states are captured with its children's. Reads are by
identity, never by name.

```kotlin
fun readTheTree() {
    val t2 = App.threads.create("t2")
    val title = t2.title
    t2 action { title mutate "hello" }
    val tree = App.tree.snapshot()                             // one consistent cut: App and every live store under it
    val read: String? = tree[title]                            // typed by the state; null outside the capture
    println(read)                                              // "hello"
    println(tree[App.threads]?.children?.map { it.name })      // "[t2]"
    println(App.tree.snapshot(App.threads)[title] == read)     // "true": a subtree capture
    println(tree[App.openThreads])                             // "[]": the parent's own state
    println(tree == App.tree.snapshot())                       // "true": value equality
    t2.dispose()
    println(tree[title])                                       // "hello": the capture outlives the store
}
```

- **Consistency (T4).** Every store is read from one lock-free cut
  (`captureConsistent`, §15.3): a commit, or an `atomic`/`suspendAtomic`
  frame applying while the capture runs, is seen whole or not at all —
  never one participant's new value with another's old one, and never a
  single store half-applied. The capture takes no store's transaction lock
  and blocks no writer: it retries while a write bracket is open, so it
  returns from inside an observer, a middleware hook, a frame body holding
  higher keys, or while a `suspendAction` body is parked holding the
  serializer. The subtree's shape is listed the same way: one registry
  lock at a time, retried while a child attaches or detaches anywhere
  under the receiver. On a committing thread the capture reads committed
  values, not the action's pending writes (an observer, running after the
  apply pass, sees the new values; `onTransactionCompleted`, running before
  it, the old). A store disposed while the capture runs is left out; a
  keyed store still inside its factory is never included. Never-read
  declared states are materialized first, as `Store.snapshot()` does. What
  it is NOT: `atomic(*stores)` — that would hold every serializer and
  transaction lock, fail nested lock order and deadlock from observers.
  Pinned gaps: a participant a frame joined as a savepoint applies with its
  enclosing transaction, so user-composed mixed nesting can still tear
  (issue #20 amendment (d)); a legacy `derived` backing may lag inside a
  cut.
- **Typed reads (T5).** `tree[state]` has the state's type and returns
  `null` for `Absent` or `Redacted` (a `Secret` state outside
  `SnapshotScope.Raw`); `tree.entry(state)` tells them apart. Membership is
  the receiver's subtree when the capture was taken: a state of a store
  that was anywhere under the receiver then reads `Absent` from a capture
  of a node that does not hold it, even after that store was disposed; a
  state of a store that was not under the receiver — an ancestor of the
  receiver, an unrelated store — or one no store declared (a
  `computed { }`, a `derivedState`) throws `IllegalArgumentException`.
  Two cases read `Absent` instead of throwing: a state of a store that
  joined the receiver's subtree after the capture, and — once the receiver
  itself is disposed — a state of any store the capture never listed. The
  capture holds each store's `StoreSnapshot`, so it keeps reading after the
  store is disposed — hold the `State` reference, since a disposed store's
  own delegates are gated. `tree[node]` is the subtree's capture, or `null`
  outside it; `tree.children`, `tree.node`, `tree.name`, `tree.hasStore`
  (a store was captured at the node), `tree.isLeaf` (no children) and
  `tree.scope` walk it. The tree's value (§17.8) keeps such a capture
  current.
- **A kept capture keeps the structure it captured.** `tree.name` is the
  node's name when the capture was taken. A node's live `name` can change
  afterwards — a store whose parent is disposed becomes a subtree root and
  takes its class-derived name again — but reads, `render()`, `encode()`,
  equality and restore reports all use the captured names and parent links.
- **Scopes.** `SnapshotScope.Raw` lets `Secret` values read; `UserAuthored`
  captures exactly the tagged states and prunes stores and groups with
  nothing captured (the requested node itself is always returned);
  `render()` and `toString()` never show a `Secret` value in any scope.
- **Equality.** `equals`/`hashCode` are full value equality over names,
  structure, scope and every store's values — `Secret`, `Remote` and
  codec-less states included — so a `distinct` consumer never drops a
  Secret-only change. `equalsEncodable(other, includeRemote = false)` is
  the round-trip contract with `decode(encode())` (§17.7): it ignores
  `Secret` values, `Remote` states unless included, codec-less states and
  keyed branches without a key codec. Both compare captures by name and
  structure, so captures taken through two instances of one class compare
  (the top node's name is not compared by `equalsEncodable`, which never
  writes it).

### 17.6 Restore and reset over a subtree

`App.tree.restore(tree, policy, sterile)` puts a `TreeSnapshot` back into
the stores at its nodes NOW, and `App.tree.reset(node)` re-runs the
initializers of every live store of the subtree at a node — the receiver's
own states included when the node is its own — each as ONE frame over the
subtree's stores (decision U10): every store is planned or materialized
first, outside every lock, then staged into its root of one outermost
`atomic`, so the stores apply in one write bracket and an observer sees the
whole subtree restored or reset, never half of it. Both return the frame's
`TransactionResult` carrying a report, and both refuse to nest (§15.4).

```kotlin
class PrefsStore : Store<PrefsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
    val etag by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "" }
}

class NoteStore(val id: String) : Store<NoteStore>() {
    val body by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
}

object Notes : Store<Notes>(), TreeIdentified {
    override val treeId get() = "notes"                                    // the receiver's identity in encode(): survives a class rename
    val folder by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "inbox" }   // the parent's own state
    val prefs by store(named = "prefs") { PrefsStore() }                   // node "prefs", pinned: survives a property rename
    val byId by keyed<String, NoteStore>(named = "byId") { id -> NoteStore(id) }
}

fun undoAndResetTheTree() {
    val n1 = Notes.byId.create("n1")
    n1 action { body mutate "draft" }
    val before = Notes.tree.snapshot()                             // one consistent cut: Notes, prefs, n1
    Notes action { folder mutate "archive" }
    Notes.prefs action { theme mutate "dark" }
    n1 action { body mutate "final" }

    val report = Notes.tree.restore(before).getOrThrow()           // one frame over all three
    println(Notes.folder.value)                                    // "inbox": the parent is restored with its children
    println(Notes.prefs.theme.value)                               // "light"
    println(n1.body.value)                                         // "draft"
    println(report.perNode.keys.map { it.name })                   // "[Notes, prefs, n1]"

    Notes.prefs action { theme mutate "dark" }
    Notes.tree.reset(Notes.byId).getOrThrow()                      // only the keyed subtree
    println(n1.body.value == "")                                   // "true": back to its initializer
    println(Notes.prefs.theme.value)                               // "dark": outside the subtree, untouched
    Notes.tree.reset().getOrThrow()                                // the parent and its whole subtree
    println(Notes.prefs.theme.value)                               // "light"
    n1.dispose()
}
```

- **Restore is addressed by node, resolved at restore time.** A captured
  store's place is its node: the receiver's own capture goes back into the
  receiver, a child's into the store at that node, and a keyed store's into
  whichever store lives under its key *today* — the captured instance, or
  one created since under the same key (listed in `report.rebound`). A
  capture with no live store at its place (a keyed store since disposed, a
  child that left the receiver's subtree) is skipped and listed in
  `report.skipped`. `report.perNode` holds each restored store's
  `RestoreReport` (§16.2: `restored`, `kept`, `issues`, `sterilized`) in
  tree order, `report.issues` flattens them. Under `RestorePolicy.Strict` a
  skipped store or an unresolved decoded path (§17.7) fails the restore
  before any store is touched, with a `RestoreRejectedException` naming the
  paths, relative to the receiver and as captured (`threads/t1`); under
  `IgnoreUnknown` (the default) they are tolerated. `sterile = true`
  restores every store sterile (its `Remote` states reset, §16.4).
  Everything `Store.restore` does per store — the schema check and
  `migrate` (§16.3), codec decoding, the policy, never re-running
  `Transformer.set` (an encrypted state goes back as its captured
  ciphertext) — happens per store, before the frame opens; a failing plan
  or a refused write fails the whole frame and nothing is written. A
  capture taken in `SnapshotScope.All` restores a `Secret` state losslessly
  (it holds the raw value); a decoded text never has one (encoded as
  `null`, so the state is `kept`).
- **Restore through the store it was captured from.** A capture whose node
  is not in the receiver's subtree is refused with an
  `IllegalArgumentException` ("the tree was captured under 'Notes' and its
  node … is not under this store …"): a capture of a child taken through
  the parent's `tree` restores through any `tree` whose subtree holds that
  child, but a capture through one instance never restores into another
  instance of the same class.
- **Reset is the subtree's initializers, in one frame.** Every live store
  of the subtree at `node` is reset as `Store.reset()` resets one store
  (§16.1): never-read states are materialized before the frame,
  initializers re-run in fresh-store order inside it, output is staged raw
  and only where it differs, so observers fire once per changed state and
  never for the rest; each store's attachments hear `onStoreReset` inside
  the frame (a hydrator goes back to `Detached`). A throwing initializer
  fails the whole frame. `report.reset` lists the stores reset,
  `report.skipped` those disposed after the reset began. An empty subtree
  (a keyed branch with no store) returns `Success` carrying a synthetic,
  already committed transaction that no store or middleware saw.
- **One frame, one transaction per store.** Each store's middleware sees
  one root transaction whose `frameId` is shared across the subtree
  (`atomic-…`), the id `restore`/`reset` return as the result's
  `transaction`. A store disposed after the frame took its lock is skipped
  and reported; the rest commit.

### 17.7 Encoding, names and the persisted-name self-check

`tree.encode(includeRemote = false)` writes a `TreeSnapshot` as a
`holdfast.tree` v1 text and `App.tree.decode(text)` reads one back,
addressed by the receiver's subtree as declared now. Names appear here and
in `render()` only: the structure is written by node name, and every store
carries its own `holdfast.store` v1 body (§16.2) verbatim — the same bytes
`store.snapshot().encode()` writes, so a blob persisted per store stays
readable, and a `Secret` state is `null`, a codec-less state absent, a
`Remote` one absent unless included.

```kotlin
fun persistTheTree(): String {
    val n2 = Notes.byId.create("n2")
    n2 action { body mutate "remember me" }
    Notes.prefs action { theme mutate "dark" }
    println(Notes.tree.verifyPersistedNames())                                    // "[]": Notes has a tree id, prefs and byId pins, n2 its key
    val text = Notes.tree.snapshot(scope = SnapshotScope.UserAuthored).encode()   // names for structure; each store its own body
    n2.dispose()
    return text
}

fun rehydrateTheTree(text: String) {
    val tree = Notes.tree.decode(text)                                            // resolves nodes; runs no store code
    println(tree.pendingKeys(Notes.byId))                                         // "[n2]": a body with no live store yet
    tree.pendingKeys(Notes.byId).forEach { Notes.byId.create(it) }                // the process-death idiom
    val report = Notes.tree.restore(tree, RestorePolicy.Strict).getOrThrow()
    println(Notes.byId["n2"]?.body?.value)                                        // "remember me"
    println(report.unresolvedPaths)                                               // "[]"
    Notes.byId["n2"]?.dispose()
    Notes.tree.reset()
}
```

- **The wire shape.** Every node is uniform: a store's node is a `leaf`
  that may carry `store` (its body; omitted when nothing was captured) and
  `children` (omitted when empty), a group a `branch` with `children`, a
  keyed branch a `keyed` node whose `entries` are leaf nodes under their
  encoded keys — so a keyed store's own children are written under it:
  `{"format":"holdfast.tree","v":1,"receiver":"notes","scope":"UserAuthored",
  "path":[],"tree":{"kind":"leaf","store":{…},"children":{"byId":{"kind":
  "keyed","entries":{"n2":{"kind":"leaf","store":{…}}}},"prefs":{"kind":
  "leaf","store":{…}}}},"skipped":[]}`. The receiver's node is anonymous:
  `path` (the captured node) and `skipped` are relative to it, and it is
  identified once, by `receiver` (below). Children are sorted by name and entries by encoded key. A keyed
  branch's entries go through its `keyCodec`; a branch without one is left
  out and listed under `skipped` — also when that branch is the node
  captured (written with no entries) — while a capture *under* such a
  branch refuses to encode, since its path would have to spell the key. An
  empty capture of a store — nothing tagged under `UserAuthored`, or a
  disposed store — is written `{"kind":"leaf"}` with no body and reads back
  as `Absent`. The text nests at most 64 container levels (about 28 levels
  of stores).
- **The receiver's identity.** `receiver` is the receiver store's
  `TreeIdentified.treeId` when it implements `TreeIdentified` (an
  interface the store class implements, like `SchemaVersioned`), else its
  node name — a child's property name or pin, a top-level store's class
  name minus `Store`. `decode` refuses a text whose `receiver` differs from
  the decoding receiver's identity, computed the same way, with a
  `SnapshotFormatException` naming both ("captured under 'notes', decoding
  under 'Settings'"), so a text captured through one store's `tree` never
  lands in another store's subtree. A child pinned with `named =` decodes
  under the same pin through any parent; a top-level store with no tree id
  is exposed to class renames — R8 obfuscation included — so give a
  persisting top-level store a `TreeIdentified` id.
- **Decode resolves paths, retains bodies.** `decode` reads the envelope
  once and matches each named node against the receiver's subtree as
  declared now (materializing declared children first): a path the subtree
  does not declare — a renamed property, a group leaf whose class was
  renamed, the children of a store that is disposed or has no live store —
  lands in `tree.unresolvedPaths` (the one place the tree hands out
  strings, a diagnostic) instead of failing, while an unknown *state* name
  inside a body is that store's `RestoreIssue` at restore, as for a single
  store (§16.2). A keyed entry with a body and no live store under its key
  becomes a typed pending key (`tree.pendingKeys(branch)`): create those
  stores, then restore — the process-death idiom above. A pending store's
  own children cannot resolve until it exists (an unresolvable keyed child
  lists one path per encoded entry, `["threads","42","replies","7"]`), so
  create nested keyed
  stores before `decode` to restore them under `Strict`; under
  `IgnoreUnknown` the parent's body restores and its children are skipped.
  Bodies stay name-keyed text until the restore, so `migrate` (§16.3) runs
  per store then, in the reading store's version. A text that is not a
  `holdfast.tree` v1 envelope, one captured under another receiver, a key
  its codec cannot decode, or two entries decoding to one key throw
  `SnapshotFormatException` naming the branch (or both receivers); no
  exception quotes a value or a key.
  `decode(encode(tree)).equalsEncodable(tree)` is the round-trip contract;
  full `equals` fails whenever a `Secret`, `Remote` or codec-less value
  exists, since the text never carried it.
- **Persisted names must be stable (T6).** A class-derived name
  (`NameOrigin.ClassName`) changes under obfuscation or a rename and
  orphans what was persisted under it, so `encode()` under
  `SnapshotScope.UserAuthored` — the persistence scope — fails fast on a
  captured group leaf named by its class; `All` and `Raw` write it (an
  in-memory undo or a debug dump does not outlive the class). The receiver
  is exempt from that refusal (its node name is never a path segment; see
  `receiver` above). A property name (`NameOrigin.Property`) is no safer
  under obfuscation or a property rename, but `encode()` writes it: pin it
  with `named =` before renaming the property.
  `App.tree.verifyPersistedNames(node)` is the self-check to run in a test.
  A store is persisted when it declares a `UserAuthored` state or keyed
  family, `is SchemaVersioned`, or has attachments reporting
  `persistenceKeys` (the overlay's, §16.8). The check lists every persisted
  store at a class-named group leaf
  (`NamingIssue.Kind.ClassDerivedNameOnPersistedStore`: pin it in the group
  lambda, `SignInStore() named "sign-in"`); every `store { }` child,
  `group { }` and `keyed { }` branch named by its property with a persisted
  store at or under it (`PropertyDerivedNameOnPersistedSubtree`: pin it
  with `store(named = …)`, `group(named = …)` or `keyed(named = …)` — a
  `store { }` child is flagged like a branch, since its property name is
  the same rename hazard); the receiver, when it is a top-level store
  identified by its class name — no `TreeIdentified` — and the checked
  subtree persists (`ReceiverNameIsClassDerived`); and every keyed branch
  declared without a key codec (`KeyedBranchNotEncodable`, empty or not).
  It reads declarations only and runs no initializer.

### 17.8 The tree value, settling and consistent cuts

`App.tree` is itself a `State<TreeSnapshot>`: the receiver's subtree as ONE
consistent capture, kept current — recomputed once per outermost entry
that changes the subtree, after every lock that entry took is released, and
published only when the capture differs. Observe it like any state;
`tree.snapshot()` is a fresh cut instead.

```kotlin
fun watchTheTree() {
    var settles = 0
    val watch = Notes.tree effect { settles++ }                         // fires once now, with the current tree
    val n3 = Notes.byId.create("n3")                                    // a store joined: one settle
    atomic(Notes.prefs, n3) {                                           // one frame over two stores: one settle
        Notes.prefs { theme mutate "dark" }
        n3 { body mutate "hi" }
    }.getOrThrow()
    println(settles)                                                    // "3": the subscription, the join, the frame
    val tree = Notes.tree.value
    println(tree[Notes.prefs.theme] to tree[n3.body])                   // "(dark, hi)": never one without the other
    println(tree == Notes.tree.snapshot())                              // "true"
    watch.dispose()
    n3.dispose()
    Notes.tree.reset()
}
```

- **Once per outermost entry (T4).** The value is a derived state over the
  receiver and every live store of its subtree as wholes (§16.5's
  machinery, following stores rather than listed states): a commit that
  changes one of them, an eviction, an inbound bridge write, a keyed entry
  coming to life, a `removeState`, a store joining or leaving at any depth
  each queue one recompute into the settle scope of the entry on this
  thread, and the outermost `action`, `atomic`, `suspendAction`,
  `suspendAtomic`, `restore` or `reset` runs it once when it has released
  everything — nested actions and nested same-flavour frames settle with
  it. The recompute is one lock-free capture (§17.5), so a two-store frame
  is seen whole; it commits on a private host store, whose lock is the host
  lock of §10.2, and the `distinct` backing publishes only a tree that
  differs (full value equality, so a `Secret`-only change is a change).
  Outside any entry — a keyed `create`, a bridge replay, a `removeState` at
  top level — the recompute runs inline, so the next read is current. A
  commit that a `distinct` state deduplicated changes nothing and
  recomputes nothing; a rollback recomputes nothing.
- **Only the stores that moved are recaptured.** A settle reuses the
  previous tree's capture of every store whose cut stamp has not moved —
  the states one cut listed and each one's write counter — so a commit on
  one store of sixteen reads one store, and the other fifteen captures are
  shared by reference; a frame recaptures every participant it wrote; a
  keyed entry, a dropped state or a bridge write recaptures its store. The
  reads still form one cut: a reused capture is validated under the same
  window the changed stores are read in.
- **Every store has its own value; each costs what it follows.** Any
  store's `tree` is a value over that store's subtree. A value costs
  nothing until it is read or observed: no host store, no listener, no
  capture — structural calls (`snapshot()`, `stores`, `middlewares`,
  `track(tree)`, `hydrateAll`) build none of it. Once it is read, it
  follows every store of its subtree: each materialized ancestor value
  recomputes once per outermost entry that commits below it, and each such
  recompute lists that ancestor's whole subtree and checks every store's
  cut stamp — O(subtree) per ancestor even when one store moved — then
  recaptures the stores whose stamp moved. So a commit under a chain of d
  ancestors whose values are all read costs O(N·d) for a subtree of N
  stores, and a store under D materialized values is recaptured D times
  per commit: captures are reused per value through its previous tree,
  never across values. Read or observe the value of the store whose
  subtree you need, not one at every level of a deep tree.
- **When a read is fresh.** The first read or observation builds the
  value (materializing declared children, and running never-read
  initializers; declaring children runs no child code). Attaching a child
  takes no capture by itself, but every value in use at or above it
  settles after the attach and captures the new store — running its
  never-read initializers then. A read inside a store's commit fanout is the tree
  before that commit — the settle comes after the fanout — while
  `snapshot()` there is after it. A read while a store has just joined or
  left, inside an action, frame or observer, is a fresh capture that is
  not committed, so the code that created a keyed store sees it in the
  value at once; outside any entry such a read settles first, handing off
  — never waiting — if the host is busy. A read never opens a transaction
  on the host inside an entry, and never takes a store's lock.
- **Observers are ordinary observers.** A value observer runs in the
  host's commit fanout, under the host lock and no other store's lock: an
  action on another thread proceeds while it runs, and an action it opens
  on a store of the subtree commits normally and settles the value again
  afterwards, never re-entrantly; a throwing one — and a failing recompute
  — reaches the receiver's `uncaughtObserverHandler` (else the platform
  log). The receiver's `scope` is what `App.tree.asStateFlow()` defaults
  to; `App.tree.asFlow()` emits one tree per settle;
  `App.tree.collectAsState()` (`:holdfast-compose`) recomposes once per
  settle. The settle's transaction runs on the host, which no tree
  middleware (§17.9) sees.
- **Dispose.** A child's `dispose()` or `removeState` reaches the value like
  a commit (once per entry). Disposing the receiver stops its value
  following the subtree, drops the value's observers and leaves the last
  tree readable through a handle taken before; the host is disposed once
  the receiver's dispose is past every lock of the receiver's — after the
  entry it ran in settles, or after the action holding the receiver's lock
  releases it, else inline. A first read of a value never read before the
  dispose throws. A thousand keyed create/dispose cycles leave no edge,
  observer or store behind.
- **For library code** (`@StoreInternalApi`, on the handle, so they keep
  answering after the receiver is disposed): `internalSettleCount`,
  `internalCaptureCount` (store captures, so reuse shows; the first settle
  captures the receiver too) and `internalCutRetryCount`; `internalHost()`
  (the host store, never a member of any tree, created lazily with the
  value — this call creates it too, so a test can install host middleware
  before the first read) and `internalSettleNow()` (run a queued recompute
  now, outside every entry).

### 17.9 Tree middleware

`App.tree.middlewares(vararg TreeMiddleware)` installs middleware over the
receiver and every store of its subtree — attached now or later — always
outermost of each store's own `middlewares(...)`, with the store's node in
every hook.

```kotlin
class Audit : TreeMiddleware() {
    val log = mutableListOf<String>()

    override fun onTransactionCompleted(node: StoreNode, context: Middleware.MiddlewareContext<*>) {
        log += "${node.name} ${if (context.transaction.frameId != null) "in a frame" else "alone"}"
    }
}

fun auditTheTree() {
    val audit = Audit()
    Notes.tree.middlewares(audit)                                  // Notes and every store under it, now and later
    val n4 = Notes.byId.create("n4")                               // attached after install: covered
    Notes action { folder mutate "archive" }                       // the parent's own transactions too
    Notes.prefs action { theme mutate "dark" }
    atomic(Notes.prefs, n4) { n4 { body mutate "x" } }.getOrThrow()
    println(audit.log)                                             // "[Notes alone, prefs alone, prefs in a frame, n4 in a frame]"
    println(Notes.tree.removeMiddleware(audit))                    // "true": no new observation from here on
    n4.dispose()
    Notes.tree.reset()
}
```

- **What it sees (T4).** Every transaction of every store of the subtree,
  the receiver's own included: top-level actions, savepoints (nested
  actions), the one-shot action a bare `mutate` synthesizes, a derived
  state's recompute on the store, each root of an `atomic`/`suspendAtomic`
  frame — `tree.restore`/`reset` included — with the frame's shared
  `frameId`, and the suspending path (`suspendAction`, a hydrator's seed
  and adopt) with the same trace as the blocking one. What it never sees:
  inbound bridge writes (they bypass middleware), a keyed store's
  transactions before its factory returned (it is not attached yet), and
  the value's settles (the host is no member). Each hook receives the
  store's `MiddlewareContext` — the store, the transaction, the
  per-transaction `metadata` map — so a tree middleware can do what a store
  middleware does, node by node.
- **Outermost, ancestors outside.** On every store the tree's ring wraps
  the store's consumer-registered chain, whatever the order of
  registration, and a store's `clearMiddleware()` never reaches it. A
  store's ring is what it and each of its ancestors installed through
  their `tree`s: its own innermost, the top-most ancestor's outermost (its
  `started` fires first), and among one store's installs the last argument
  is outermost, one installed again moving to the outermost place. A
  middleware installed on a child and on its parent fires once per
  transaction, at the parent's place. An install on a store mid-tree
  covers that store's subtree only. Throwing in `started` or `completed`
  aborts that store's transaction as a store middleware's throw does — a
  `completed` throw on the last root of a frame rolls every root back, and
  the tree error hook fires for each — while on the suspending path a
  hook's throw is isolated, as for store middleware there.
- **Install and remove from outside.** `middlewares`/`removeMiddleware`
  throw inside an `atomic` frame, a transaction of any store of the
  subtree (an action body, a hook, an observer) or a
  `suspendAction`/`suspendAtomic` body of one: the chain is snapshotted per
  transaction, so an install never applies to an in-flight action and
  never waits for one. The suspending check is conservative: a suspending
  body resumes on any thread, so the guard's probe is the settle scope its
  entry carries across dispatch, and that cannot tell the holder's body
  from another entry on the same thread — from inside any entry on the
  calling thread (an action or an observer of an unrelated store, say)
  while a store of the subtree is held by a parked suspending body the call
  is refused too, naming the held store; from outside every entry it is
  allowed beside a parked holder and does not reach it. On iOS a nested
  `withContext` on another dispatcher inside the body replaces the carrier
  of that scope, so the refusal is not guaranteed in that section.
  `removeMiddleware` returns whether it was installed through this
  receiver; no new observation starts once it returns, and an observation
  it started still gets its terminal hook (a parked action or
  `suspendAction` included). A store that leaves takes its adapters with
  it — a thousand keyed create/dispose cycles leave nothing behind — and a
  child its parent's dispose releases keeps only what it and its remaining
  ancestors installed. Disposing the receiver removes what it installed
  from every store of its subtree; `removeMiddleware` on a disposed
  receiver answers `false` rather than throwing, so teardown code can
  unwind a store disposed meanwhile.

### 17.10 Testing a tree

`:holdfast-testing`'s `track(tree)` (§11.6) tracks the receiver and every
store of its subtree — the ones attached now and every store that joins
later — as `StoreHandle`s, and records every transaction with its node
into one tree timeline.

```kotlin
fun testTheTree() =
    storeTest {
        val tree = track(Notes.tree)                                  // Notes and every store under it; reset at teardown
        val n5 = Notes.byId.create("n5")                              // tracked as it joins
        atomic(Notes, Notes.prefs, n5) {                              // the parent's own state in the same frame
            Notes { folder mutate "archive" }
            Notes.prefs { theme mutate "dark" }
            n5 { body mutate "hi" }
        }.getOrThrow()
        println(tree.timeline.map { "${it.phase} ${it.node.name}" }) // "[Started Notes, Started prefs, Started n5, Completed Notes, Completed prefs, Completed n5]"
        println(tree.shouldCommitTogether(Notes.tree.node) == tree.committedFrameIds(Notes.tree.node).single())   // "true": one frame over all three
        println(tree.handle(n5).transactions.size)                    // "2": the store's own timeline, started and committed
        n5.dispose()
        println(tree.events(Notes.byId).size)                         // "2": a disposed store's events stay under its branch
    }
```

- **Handles.** `tree.handle(store)` is the same handle `track(store)`
  answers, with the tree's `Capture` mode; `tree.group(node)` groups the
  live stores of a subtree for the cross-store matchers (§15.6);
  `tree.consumeAllPendingErrors()` consumes every member handle's pending
  `TransactionResult.Error`s. `tree.root` is the receiver store.
- **The tree timeline.** `TreeEvent(node, store, phase, transaction, cause,
  timestamp)`, in observation order across the tree; `events(node)` narrows
  to the subtree at a node (a store's own events and its descendants'),
  judged by where each event's store sat when the event was recorded — a
  store released since (its parent disposed) keeps its earlier events
  under its former ancestors;
  `committedFrameIds(node)` lists the frames committed in that subtree (a
  frame vetoed on its last participant committed nowhere);
  `shouldCommitTogether(node)`/`shouldNotCommitTogether(node)` judge
  whether one frame spanned every live store of the subtree, each judged by
  its own events — a parent that took no part in a frame does not count as
  committing with its children. A `Secret` never reaches a tree event.
- **Teardown.** The store recorders come off, then, unless
  `resetAtTeardown = false`, the receiver and its subtree are reset as one
  frame — the RECEIVER'S OWN STATES included, not only its descendants',
  and every hydrator in it, the receiver's too, back to `Detached` — so the
  next test finds the initial values. The tree middleware is still installed then, so a vetoed
  reset fails the test naming the store and the veto (unless the body
  already failed); a store disposed in the body is skipped; then that
  middleware comes off. Every middleware the test installed stays. A store
  still held when teardown runs (a `suspendAction` body parked in un-joined
  work, a thread inside an action) is re-probed for about a second of real
  time — a transient holder, such as an in-flight `suspendDerived`
  recompute on `Store.scope`, is waited out and the reset runs — and one
  still held after that skips the whole tree's reset and FAILS the test,
  naming the tree and the store: a silent skip would leak the body's values
  into the next test, and waiting longer would hang the test thread, the
  only one able to resume a parked body. Join such work before the body
  ends, or opt out with `track(tree, resetAtTeardown = false)` for a test
  that deliberately parks it. A child lambda that throws when teardown
  lists the subtree fails the test as a reset failure. No store is ever
  disposed. `track(tree)` is idempotent by receiver and throws on a
  disposed store, or from inside a member's action or an `atomic` frame (a
  refused call leaves nothing behind).

### 17.11 Hydrating a tree

`:holdfast-coroutines`' `App.tree.hydrateAll(node = App.tree.node)`
(§14.8, §16.7) drives the hydrator of every store of a subtree — the
parent's own included — and reports how each settled.

```kotlin
class FeedStore(private val remote: suspend () -> List<String>) : Store<FeedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration = hydrator {
        base { items mutate listOf("cached") }
        refresh { remote() } adopt { fetched -> items mutate fetched }
    }
}

class SettingsOnlyStore : Store<SettingsOnlyStore>() {
    val theme by state { "light" }
}

object Feeds : Store<Feeds>() {
    val news by store { FeedStore { listOf("headline") } }
    val sports by store { FeedStore { error("offline") } }
    val settings by store { SettingsOnlyStore() }
}

suspend fun hydrateTheTree() {
    val report = Feeds.tree.hydrateAll()                                 // the parent, then every child, in lock order
    println(report.entries.map { "${it.node.name}: ${it.outcome}" })     // "[Feeds: NoHydrator, news: Ran(Hydrated), sports: Ran(Failed(cause=java.lang.IllegalStateException: offline)), settings: NoHydrator]"
    println(report.isHealthy to report.failed.map { it.node.name })      // "(false, [sports])"
    println(Feeds.news.items.value)                                      // "[headline]"
    println(Feeds.sports.items.value)                                    // "[cached]": the seed stood, the refresh failed
    println(Feeds.tree.hydrateAll().failed.map { it.node.name })         // "[sports]": idempotent for news, a retry for sports
}
```

- **What it drives.** Every live store of the subtree at the node — the
  receiver's own when the node is its own, which sorts first whenever the
  receiver was constructed before its children (always, for children
  built on first use) — in `lockOrderKey` order, after materializing
  declared children: each hydrator's `hydrate()` (on the given scope, else
  its store's `Store.scope`) has committed its seed and launched its
  refresh before the next store's turn; then each is awaited in the same
  order (`awaitSettled = false` reports the phase after the seed instead),
  so the refreshes run concurrently. A store without a hydrator is
  `NoHydrator`, one disposed meanwhile `Disposed`; a store whose seed or
  refresh failed is `Ran(Failed(cause))` — reported, never thrown, and the
  other stores still hydrate. A hydrated store's hydrator does nothing, so
  the call is idempotent; a failed one retries its refresh, as
  `Hydrator.hydrate` does. The receiver's entry is named by its class
  (`Feeds`), since nobody declared it.
- **Where it may run.** Outside every entry: inside an action, an `atomic`
  frame, a `suspendAction` or `suspendAtomic` body of any store it fails
  before touching a store — before the listing materializes a single
  child — for the reasons `Hydrator.hydrate` gives (§16.7). The subtree is
  listed once, each store with its node. It throws on a disposed receiver and on a node outside the
  receiver's subtree. A cancellation propagates at once; the refreshes
  already launched keep running on their scopes. The report names nodes
  and outcomes, never a value.

### 17.12 API reference

```kotlin
// Declarations, beside state { } (com.vynatix.holdfast.tree)
fun <S : Any> Store<*>.store(named: String? = null, child: () -> S): StoreDeclaration<S>     // `by`: S; the lambda must produce a Store
fun Store<*>.group(named: String? = null, members: GroupScope.() -> List<Store<*>>): GroupDeclaration   // `by`: Branch
inline fun <reified K : Any, reified S : Store<S>> Store<*>.keyed(keyCodec: StateCodec<K>? = null, named: String? = null, onParentDispose: KeyedDisposal = KeyedDisposal.Dispose, noinline factory: (K) -> S): KeyedDeclaration<K, S>   // `by`: KeyedBranch<K, S>
class GroupScope { infix fun <S : Store<*>> S.named(name: String): S }   // pins one listed store's leaf name
enum class KeyedDisposal { Dispose, Release }               // what a keyed branch's stores become when its declaring store disposes
interface TreeIdentified { val treeId: String }             // a store's receiver identity in encode()/decode()
class StoreDeclaration<S : Any> : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, S>>
class GroupDeclaration : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, Branch>>
class KeyedDeclaration<K : Any, S : Store<S>> : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, KeyedBranch<K, S>>>

// The handle
val Store<*>.tree: StoreTree
sealed interface StoreTree : State<TreeSnapshot> {            // value: the subtree, settled once per outermost entry
    val node: LeafNode
    val parent: StoreNode?
    fun children(): List<StoreNode>                           // materializes declared children first
    fun stores(node: StoreNode = this.node): List<Store<*>>
    fun nodeOf(store: Store<*>): LeafNode?
    fun snapshot(node: StoreNode = this.node, scope: SnapshotScope = SnapshotScope.All): TreeSnapshot
    fun restore(tree: TreeSnapshot, policy: RestorePolicy = RestorePolicy.IgnoreUnknown, sterile: Boolean = false): TransactionResult<TreeRestoreReport>
    fun reset(node: StoreNode = this.node): TransactionResult<TreeResetReport>
    fun decode(text: String): TreeSnapshot                   // refuses a text captured under another receiver
    fun verifyPersistedNames(node: StoreNode = this.node): List<NamingIssue>
    fun middlewares(vararg middleware: TreeMiddleware)        // the receiver and its subtree, now and later; outermost
    fun removeMiddleware(middleware: TreeMiddleware): Boolean  // false on a disposed receiver
}

// Nodes
sealed interface StoreNode { val name: String; val parent: StoreNode?; val nameOrigin: NameOrigin; fun isUnder(node: StoreNode): Boolean }
enum class NameOrigin { Property, ClassName, Key, Pinned }
class LeafNode : StoreNode { val store: Store<*>?; val key: Any? }
class Branch : StoreNode { override val parent: LeafNode; val stores: List<Store<*>>; fun leafName(store: Store<*>): String }
class KeyedBranch<K : Any, S : Store<S>> : StoreNode {
    override val parent: LeafNode
    val keyClass: KClass<K>; val storeClass: KClass<S>; val keyCodec: StateCodec<K>?; val onParentDispose: KeyedDisposal
    fun create(key: K): S
    fun getOrCreate(key: K): S
    operator fun get(key: K): S?                              // may park while another thread finishes an attach
    fun entries(): Map<K, S>
    fun dispose(key: K): Boolean                              // false when no live store under key
    fun disposeAll()
}

// Captures
class TreeSnapshot {
    val node: StoreNode; val name: String; val children: List<TreeSnapshot>; val scope: SnapshotScope
    val hasStore: Boolean; val isLeaf: Boolean            // isLeaf: no children
    val unresolvedPaths: List<List<String>>               // decode diagnostic; empty for a capture
    operator fun get(node: StoreNode): TreeSnapshot?
    operator fun <T : Any> get(state: State<T>): T?       // null = Absent or Redacted
    fun <T : Any> entry(state: State<T>): SnapshotEntry<T>
    fun <K : Any, S : Store<S>> pendingKeys(branch: KeyedBranch<K, S>): Set<K>
    fun render(): String
    fun encode(includeRemote: Boolean = false): String
    fun equalsEncodable(other: TreeSnapshot, includeRemote: Boolean = false): Boolean
    override fun equals(other: Any?): Boolean; override fun hashCode(): Int; override fun toString(): String
}
class TreeRestoreReport { val perNode: Map<StoreNode, RestoreReport>; val skipped: List<StoreNode>; val rebound: List<StoreNode>; val unresolvedPaths: List<List<String>>; val issues: List<RestoreIssue> }
class TreeResetReport { val reset: List<StoreNode>; val skipped: List<StoreNode> }
class NamingIssue { val node: StoreNode; val kind: Kind; val message: String; enum class Kind { ClassDerivedNameOnPersistedStore, PropertyDerivedNameOnPersistedSubtree, ReceiverNameIsClassDerived, KeyedBranchNotEncodable } }

// Middleware
abstract class TreeMiddleware {
    protected open fun onTransactionStarted(node: StoreNode, context: Middleware.MiddlewareContext<*>) {}
    protected open fun onTransactionCompleted(node: StoreNode, context: Middleware.MiddlewareContext<*>) {}   // before commit
    protected open fun onTransactionError(node: StoreNode, context: Middleware.MiddlewareContext<*>, error: Throwable) {}
}

// @StoreInternalApi
val StoreTree.internalSettleCount: Long; val StoreTree.internalCaptureCount: Long; val StoreTree.internalCutRetryCount: Long
fun StoreTree.internalHost(): Store<*>; fun StoreTree.internalSettleNow(): TreeSnapshot
abstract class LeafMembershipListener { open fun onAttached(leaf: LeafNode) {}; open fun onDetached(leaf: LeafNode) {} }
fun Store<*>.internalAddMembershipListener(listener: LeafMembershipListener): Disposable

// :holdfast-coroutines (§17.11)
suspend fun StoreTree.hydrateAll(node: StoreNode = this.node, scope: CoroutineScope? = null, awaitSettled: Boolean = true): HydrateAllReport
class HydrateAllReport { val entries: List<Entry>; val failed: List<Entry>; val skipped: List<Entry>; val isHealthy: Boolean
    class Entry { val node: StoreNode; val store: Store<*>; val outcome: Outcome }
    sealed class Outcome { class Ran(val hydration: Hydration); object NoHydrator; object Disposed } }

// :holdfast-testing (§17.10)
fun StoreTestScope.track(tree: StoreTree, capture: Capture = Capture.All, resetAtTeardown: Boolean = true): TreeHandle
class TreeHandle { val tree: StoreTree; val root: Store<*>; val captureMode: Capture; val timeline: List<TreeEvent>
    fun events(node: StoreNode): List<TreeEvent>; fun <S : Store<S>> handle(store: S): StoreHandle<S>; fun group(node: StoreNode): StoreHandleGroup
    fun committedFrameIds(node: StoreNode): List<String>; fun consumeAllPendingErrors() }
data class TreeEvent(val node: StoreNode, val store: Store<*>, val phase: Phase, val transaction: Transaction, val cause: Throwable?, val timestamp: Long) { enum class Phase { Started, Completed, Errored } }
fun TreeHandle.shouldCommitTogether(node: StoreNode): String; fun TreeHandle.shouldNotCommitTogether(node: StoreNode)
```

---

## Appendix A — One-page cheatsheet

Lines marked `exp.` are `@ExperimentalStoreApi` (§16 and §17 have the detail); the
rest is the stable surface.

```kotlin
class V : Store<V>(), SchemaVersioned {                                      // SchemaVersioned: exp.
    val x by state { 0 }
    val s by state(MyTransformer()) { "" }
    val items by state(distinct = true) { emptyList<Item>() }                // dedup: equal values never fire
    val token by state(EncryptingTransformer(cipher), tags = setOf(StateTag.Secret)) { "" }   // exp.: withheld from every read-out
    val pinned by state(codec = PinsCodec, tags = setOf(StateTag.UserAuthored)) { emptySet<String>() } // exp.: what the user wrote
    val feed by state(codec = ItemsCodec, tags = setOf(StateTag.Remote)) { emptyList<Item>() }         // exp.: synced data
    val drafts by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }        // exp.: one state per key

    override val schemaVersion: Int get() = 2                                // exp.: an older snapshot is migrated first
    override fun migrate(from: Int, view: EncodedSnapshotView) { if (from < 2) view.rename("pins", "pinned") }
}
val v = V()

// Subscribe.
val sub = v { x effect { println("x=$this") } }       // initial: x=0

// Atomic single-store mutation; body return flows into Success.
val r = v action { x update { it + 1 }; "${x.value} done" }

// Failed atomic mutation: rolled back whole, observers never fire.
v action { x mutate 99; error("nope") }.onError { … }

// Bare mutation — same outcome as a one-mutate action.
v { x mutate 2 }                                       // → x=2

// Keyed entries (exp.): created on first get, evicted like a write.
v action { drafts["a"] mutate "hello"; drafts.evict("b") }
v.drafts["a"].value; v.drafts.entries.keys; "a" in v.drafts

// Cross-cutting concern. LAST argument is outermost middleware.
v.middlewares(ValidationMiddleware { … }, LoggingMiddleware("v"))

// External sync (two-way bridge); detach with bridge null.
v { s bridge KvBridge(kv, "s", StringCodec) }
v { s bridge null }

// Inbound-only push.
val sub2 = v { s observeFrom externalObservable }

// Cross-store atomic: every participant applies before any fans out.
atomic(accountA, accountB) {
    accountA { balance update { it - cents } }
    accountB { balance update { it + cents } }
}

// Derived states (exp.): read-only, recomputed once per outermost entry.
val total by v.derivedState(v.items) { items.value.sumOf { it.amount } }
val shown by v.merged(v.pinned, v.feed) { pins, feed -> feed.filter { it.id in pins } }
val (count, d) = v.derived(v.items) { items.value.size }   // stable: recomputes per source commit

// Snapshot / restore: raw values, undo on the same instance.
val snap = v.snapshot()
v.restore(snap)

// Persist (exp.): codecs, scopes, policies. Secret is written as null, Remote left out.
val text = v.snapshot(SnapshotScope.UserAuthored).encode()
val report = v.restore(StoreSnapshot.decode(text), RestorePolicy.IgnoreUnknown).getOrThrow()
v.restore(snap, RestorePolicy.Strict, sterile = true)  // Remote states back to their initializers

// Reset (exp.): every declared state and live entry back to its initializer, one action.
v.reset()

// Time as an input (exp.): store code reads v.clock.now(); a test binds a fixed clock.
v.bindClock(fixedClock); v.bindClock(null)

// Suspending bodies (holdfast-coroutines).
val r2 = v.suspendAction { status mutate Loading; val data = api.fetch(); status mutate Loaded; data }
suspendAtomic(accountA, accountB) { accountA { … }; accountB { … } }

// Hydration (exp., holdfast-coroutines): seed, fetch, adopt into Remote states; persist UserAuthored ones.
val hydration = v.hydrator {
    base { restore(bundled) }
    overlay(kv, "v.overlay")                            // UserAuthored states, written after each commit
    refresh { api.fetchFeed() } adopt { fetched -> feed mutate fetched }
}
hydration.hydrate(scope); hydration.awaitSettled()    // Detached → Seeded → Hydrated
hydration.invalidate()                                // back to Detached

// Store tree (exp., §17): a store declares its children next to its states; children are ordinary stores.
object App : Store<App>() {
    val settings by store { SettingsStore() }                         // one child, named by the property; built on first read
    val session by group { listOf(v named "v", other) }                // a group: leaf pinned with named, else class minus "Store"
    val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }        // keyed: the factory is declared once
}
val t = App.threads.create("t1")                                     // live once the factory returns; t.dispose() leaves
val tree = App.tree.snapshot(App.session)                            // one lock-free consistent cut; tree[v.x]: Int?
App.tree.restore(tree); App.tree.reset(App.session)                  // one outermost frame over the subtree, each
App.tree.decode(tree.encode()); App.tree.verifyPersistedNames()      // names exist only here; pin every persisted name
App.tree.value                                                       // the tree is a State<TreeSnapshot>, settled once per outermost entry
App.tree.middlewares(MyTreeMiddleware()); App.tree.hydrateAll()      // App and every store under it (hydrateAll: holdfast-coroutines)

// Cleanup. Disposing App releases its store/group children as subtree roots and disposes its keyed stores.
sub.dispose(); sub2.dispose(); total.dispose(); shown.dispose(); d.dispose(); v.dispose(); App.dispose()
```
