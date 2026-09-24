# holdfast-coroutines

`Flow` / `StateFlow` / suspend integration for the [Store](../holdfast/) library.
Pulls in `kotlinx-coroutines-core`; nothing else.

## Surface

```kotlin
fun <T : Any> State<T>.asFlow(): Flow<T>

fun <T : Any> State<T>.asStateFlow(
    scope: CoroutineScope = …,   // defaults to the owning store's Store.scope
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T>
// Eager publishing: asStateFlow(started = SharingStarted.Eagerly).

suspend fun <T : Any> State<T>.first(predicate: (T) -> Boolean): T
suspend fun <T : Any> State<T>.awaitValue(target: T): T

// Suspending transactions — bodies may delay/await/withContext. Mutually
// exclusive with blocking action/atomic on the same store(s).
suspend fun <V : Store<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R>
suspend fun <R> suspendAtomic(
    vararg stores: Store<*>,
    policy: FramePolicy = FramePolicy.Strict,
    body: suspend () -> R,
): TransactionResult<R>

// Push-recomputed derived state with a suspending compute.
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable>

// Async persistence: suspend KvStore + bridges over it.
interface SuspendingKvStore                          // suspend get / put / remove / snapshot
interface SuspendingBridge<T : Any> : Bridge<T>      // suspend fun publishAwaited(value: T)
fun <T : Any> SuspendingKvStore.bridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge<T>
fun <T : Any> SuspendingKvStore.suspendingBridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge.Awaiting<T>
```

`asStateFlow`'s `scope` parameter defaults to the owning store's
`Store.scope` (per-store override → `bindToScope` binding →
`Store.defaultScope`); pass a scope explicitly to override. The flows,
`first`/`awaitValue` and `suspendDerived`'s sources accept core's
experimental `derivedState`/`merged` states like declared ones (their owning
store is the one they were created on). `suspendAction`
allows the transaction body to suspend; cancellation of the body rolls the
transaction back, and the commit fanout runs under `NonCancellable` so it
completes even if the surrounding scope cancels mid-commit — the call then
returns the committed result, and the caller sees its cancellation at its
next suspension point. Called from an already-cancelled coroutine, an
outermost `suspendAction`/`suspendAtomic` throws that `CancellationException`
before taking the store. As with blocking
`action`, code running inside the commit — an observer, a bridge publish, an
event collector the emit resumes inline — must not write back into a store
that commit has applied: `mutate`/`update`/`emit` throw, and a blocking
`action`/`atomic` on that store returns an `Error` (which the caller must
check) rather than waiting on the commit it runs in. On iOS and wasmJs a
nested `withContext(dispatcher)` inside the commit is not recognised and such
a blocking call still waits forever. While a `suspendAction` holds the store,
another thread's bare `mutate`/`update` is not isolated from it — before the
commit applies it joins the transaction, after it throws — so write from other
threads through `action { }`, which waits. A failing
`SuspendingBridge.publishAwaited` never undoes the commit; it goes to
`Store.uncaughtObserverHandler` (logged while none is set). A
`suspendAction` or `suspendAtomic` is an entry like `action`: the
`derivedState`/`merged` states whose sources it — and everything nested in it
— changes settle once, when it has released every store, on whatever thread
it ends on, even when its body or its commit resumed on another thread —
except, on iOS and wasmJs, inside a nested `withContext(dispatcher)` in the
entry, where a commit does not see the entry's settle scope and its derived
states recompute after that commit instead.
`bridge(...)`
saves fire-and-forget (conflated — rapid publishes coalesce);
`suspendingBridge(...)` returns an await-completion `SuspendingBridge` whose
`publishAwaited` suspends until the value is persisted.

## Examples

### Cold Flow over a state

```kotlin
viewModelScope.launch {
    holdfast.count.asFlow().collect { value ->
        log("count = $value")
    }
}
```

### Hot StateFlow scoped to a ViewModel

```kotlin
class CounterViewModel : ViewModel() {
    val holdfast = CounterStore()
    val count: StateFlow<Int> = holdfast.count.asStateFlow(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
    )
}
```

### Await predicate

```kotlin
suspend fun waitForReady(store: AccountStore) {
    store.status.first { it == AccountStatus.Active }
}
```

### Suspending transaction

```kotlin
suspend fun refresh(store: CounterStore, api: Api) {
    val result = store.suspendAction {
        val delta = api.fetchDelta()      // suspending I/O inside the transaction
        count update { it + delta }
    }
    result.onError { log("refresh rolled back: ${it.exception}") }
}
```

### Cross-store frame with a suspending body

```kotlin
val r = suspendAtomic(accountA, accountB) {
    accountA { balance update { it - amount } }   // stages into A's frame root
    accountB.suspendAction {                      // joins the frame as a savepoint
        balance update { it + amount }
    }
}
```

Same contract as core `atomic` (see the
[GUIDE's cross-store chapter](../holdfast/GUIDE.md#15-cross-store-transactions)):
enrollment is enforced, inner errors abort the frame, and blocking
`action { }` on a participant fails fast with `FrameInteropException`
instead of deadlocking. Commit runs under `NonCancellable`: every
participant applies first, inside one write bracket, then each fans out in
lock order with its suspending bridge publishes awaited and its event
back-pressure honored — so no consistent read (a snapshot, a derived
state's compute) ever sees one participant applied without the others, no
reader sees one applied while another's publish is in flight (for an
outermost frame, or a nested one that shares no store with its enclosing
`suspendAtomic`: a store the enclosing frame already holds joins as a
savepoint and applies when that frame commits, while the nested frame's
other participants apply and fan out at its exit; enroll every store in the
outermost frame to keep the frame whole), and an observer may not write into
any participant. A participant whose fanout
fails, or whose `publishAwaited` throws a `CancellationException`, does not
keep the others from fanning out; the frame returns the failure.

## Build

```
./gradlew :holdfast-coroutines:allTests
./gradlew :holdfast-coroutines:apiCheck
./gradlew :holdfast-coroutines:dokkaGenerate
./gradlew :holdfast-coroutines:publishToMavenLocal
```
