# holdfast-lifecycle

ViewModel ownership for the [Store](../holdfast/) library: a `ViewModel` that
owns a `Store` and wires `dispose()` to `onCleared()` — exactly once. Built on
the Jetpack Lifecycle ViewModel KMP artifact
(`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`), so it works wherever
that does: Android, JVM/desktop, iOS.

**Experimental tier** — the whole module is `@ExperimentalStoreApi` (ROADMAP
principle 6: post-0.4.0 surface ships experimental until it has soaked two
minors). Expect shape changes without a deprecation cycle.

## Surface

```kotlin
@ExperimentalStoreApi
abstract class StoreViewModel<S : Store<*>> : ViewModel {
    val store: S                        // created once, on first read
    protected abstract fun createStore(): S
    protected open val disposeSubtree: Boolean = false
    protected final override fun onCleared()   // disposes; idempotent
}

@ExperimentalStoreApi
fun <S : Store<*>> storeViewModel(create: () -> S): StoreViewModel<S>

@ExperimentalStoreApi
fun <S : Store<*>> treeViewModel(create: () -> S): StoreViewModel<S>   // disposes the whole subtree
```

## A store per ViewModel

The common shape — subclass when you want a ViewModel of your own:

```kotlin
class CounterViewModel : StoreViewModel<CounterStore>() {
    override fun createStore() = CounterStore()
}

val vm = CounterViewModel()
vm.store.action { count update { it + 1 } }
// when the host clears the ViewModel: onCleared → store.dispose(), exactly once
```

or build one inline, including through the platform's factory machinery:

```kotlin
val vm = storeViewModel { CounterStore() }
// Compose / ViewModelProvider:
viewModel { storeViewModel { CounterStore() } }
```

`createStore` runs at most once, on the first `store` read — never from a
constructor (the factory may reference subclass state that is not ready while
`this` leaks), and never at all when the ViewModel is cleared before a first
read, in which case there is nothing to dispose. Reading `store` after
`onCleared` throws a teaching `IllegalStateException`.

`onCleared` is final: it cannot be forgotten. For extra teardown work use the
platform's `addCloseable` — closeables run *before* `onCleared`, which is also
the hook for the persist-on-clear recipe:

```kotlin
class ScreenViewModel(prefs: KvStore) : StoreViewModel<ScreenStore>() {
    init {
        addCloseable { prefs.put("screen", store.snapshot().encode()) } // store still live here
    }
    override fun createStore() = ScreenStore()
}
```

## A store tree per ViewModel

When the owned store is a tree root (GUIDE §17), the two shapes differ in
what `onCleared` disposes:

- `storeViewModel { ScreenStore() }` — the tree's own dispose semantics
  (GUIDE §17.3): the root's **keyed stores are disposed with it**, its
  `store { }` / `group { }` children are *released* as subtree roots —
  deliberately not disposed, because their lambdas may have shared them. A
  released child that observes anything longer-lived than the ViewModel
  (another store, a bridge) keeps those subscriptions until something
  disposes it.
- `treeViewModel { ScreenStore() }` — the screen-private-tree ownership
  claim: `onCleared` disposes **every** store of the subtree, then the root.
  Listing the subtree materializes never-read `store { }` / `group { }`
  children, so their declaring lambdas run at teardown — keep tree-child
  lambdas side-effect-free (a keyed factory runs only on
  `create`/`getOrCreate`, so an unread keyed entry simply does not exist). A
  child lambda that throws there does not break the guarantee — the root is
  still disposed — and the failure is reported through the root store's
  `uncaughtObserverHandler` (or the default failure log), wrapped in a
  teardown exception naming the cause. A fatal error (`StackOverflowError`,
  `OutOfMemoryError`; GUIDE §18.5) is never wrapped or reported: every store
  is still disposed, then `onCleared` throws it.

## Process death, honestly

`onCleared` never runs on process death — the ViewModel, its store and any
unsaved state die with the process, and a restored ViewModel builds a fresh
store. This module persists nothing. The process-death story is the snapshot
pipeline's (GUIDE §16.2, §16.8): `snapshot().encode()` what must survive, at
clear time through a closeable or on every commit, and `restore` it in
`createStore`.

There is no additional leak vector: a ViewModel whose host never clears its
`ViewModelStore` (an app-level leak) leaks with its store, exactly as it
would without this module — the wiring guarantees only that a *cleared*
ViewModel disposes its store exactly once.

## Scopes

`dispose()` never cancels any scope, and a cancelled scope is not observed
(GUIDE §18.4). Post-commit work (derived-state recomputes, hydration writers)
lands on the store's resolved scope — bind the store to `viewModelScope` in
`createStore` (or a factory lambda) if that work should stop with the
ViewModel; the platform cancels `viewModelScope` independently of the dispose
wiring.

Like every `ViewModel`, instances are main-thread confined; `store` and the
wiring are not synchronized.

## Build

```
./gradlew :holdfast-lifecycle:allTests    # jvmTest + testAndroidHostTest + iosSimulatorArm64Test
./gradlew :holdfast-lifecycle:apiCheck
./gradlew :holdfast-lifecycle:dokkaGenerate
./gradlew :holdfast-lifecycle:publishToMavenLocal
```
