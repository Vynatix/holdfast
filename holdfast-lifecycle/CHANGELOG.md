# Changelog — `:holdfast-lifecycle`

All notable changes to `:holdfast-lifecycle` are documented here. The format
is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The module. ViewModel ownership for Holdfast stores on the Jetpack
  Lifecycle ViewModel KMP artifact
  (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0`), all of it
  `@ExperimentalStoreApi` (ROADMAP principle 6 — post-0.4.0 surface ships
  experimental until it has soaked two minors):
  - **`StoreViewModel<S : Store<*>>`** — a `ViewModel` that owns one store,
    created lazily by the first `store` read (`createStore` runs at most
    once; a ViewModel cleared before a first read never builds a store), with
    `dispose()` wired to a final `onCleared()` exactly once: a repeated
    `onCleared` is a no-op and `Store.dispose` is idempotent regardless.
    Reading `store` after clear throws a teaching `IllegalStateException`;
    platform closeables (`addCloseable`) run before the wiring, which is the
    persist-on-clear hook. Process death is documented honestly: `onCleared`
    never runs, nothing is persisted here — the snapshot pipeline (GUIDE
    §16.2, §16.8) is the survival story.
  - **`storeViewModel { }`** — the factory for a store per ViewModel; when
    the store is a tree root the tree's own dispose semantics apply (GUIDE
    §17.3): keyed stores disposed with the root, `store { }`/`group { }`
    children released as subtree roots.
  - **`treeViewModel { }`** (and `StoreViewModel.disposeSubtree = true` in
    your own subclass) — the screen-private-tree ownership claim: `onCleared`
    disposes every store of the subtree, then the root. The teardown listing
    materializes never-read `store { }`/`group { }` children (their lambdas
    run at teardown; a keyed factory runs only on `create`/`getOrCreate`); a
    child lambda that throws is reported through the root store's
    `uncaughtObserverHandler`, wrapped in a teardown exception, and the root
    is disposed regardless.
- Tests on every declared target — JVM, Android host (`testAndroidHostTest`)
  and iOS simulator (`iosSimulatorArm64Test`) — pinning: create-once
  laziness; dispose exactly once through the real `ViewModelStore.clear()`
  path and through `ViewModelProvider`; repeated-`onCleared` idempotence;
  the after-clear teaching error; the closeable-before-dispose ordering; the
  §17.3 split (keyed disposed, plain children released and still usable);
  whole-subtree disposal; teardown materialization of never-read children;
  and the reported-not-thrown teardown of a throwing child lambda.
