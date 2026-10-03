# Changelog — `:holdfast-testing`

All notable changes to the testing harness. Earlier harness changes are
logged in [`holdfast/CHANGELOG.md`](../holdfast/CHANGELOG.md) under
`:holdfast-testing` prefixes.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- **Issue #21 review (PR #24).** `track(tree)`'s teardown does not spin
  on the test thread when a live store of the subtree is still held by an
  entry it cannot wait out (a `suspendAction`/`suspendAtomic` body parked
  in un-joined work, a thread inside an action, or a holder of the store's
  serializer): it re-probes every live store for a bounded time (about one
  second of real time), so a transient holder — an in-flight
  `suspendDerived` recompute on `Store.scope`, which no test can join — is
  waited out and the reset runs; a store still held when the budget ends
  skips the tree's reset and fails the test, naming the tree and the held
  stores in a section of the teardown `AssertionError` of its own (after
  the unconsumed errors and the vetoed resets) that points at the opt-out,
  `track(tree, resetAtTeardown = false)`, for a test that deliberately
  parks work. A silent skip would leak the body's values into the next
  test; so would a skip when listing the subtree throws at teardown (a
  child lambda failing then), which is reported as a reset failure.
  `track(tree)` from inside a member's action or an `atomic` frame is
  refused and leaves nothing behind (no tracked store, no membership
  listener, no tree middleware). Teardown reports unconsumed
  `TransactionResult.Error`s and a failed tree reset in one
  `AssertionError`, the unconsumed errors first. A handle that loses
  the track registration race hands its bridge wrappers to the winner (or
  unwraps them when the winner is `Capture.None`) instead of feeding a
  disposed recorder.
- **Issue #21 review (PR #25).** `TreeHandle.events(node)` keeps history:
  it filters by the ancestry each event was recorded under, not by
  today's parent links, so a store released since (its parent disposed)
  keeps its earlier events under its former ancestors. The `track(tree)`
  and `TreeHandle` KDoc now say that `resetAtTeardown` resets the
  receiver's OWN states too and sends every hydrator in the subtree, the
  receiver's included, back to `Detached`. A `track(tree)` no longer
  builds the tree value's host store (the core builds it on the first
  value read only).

### BREAKING (behavior)

- **Teardown keeps the middleware a test installed.** `storeTest`'s
  teardown used to detach the recorder with `Store.clearMiddleware()`,
  dropping every middleware the test had registered on the store as well.
  It now removes the recorder alone (through core's
  `@StoreInternalApi internalRemoveMiddleware`, which removes one middleware
  by identity from a store's consumer list or its outer ring): a store that
  outlives a `storeTest` block keeps behaving as the test left it, and a
  store tree's `tree.middlewares` ring is untouched. A test that relied on
  teardown clearing its own middleware must call `clearMiddleware()`
  itself.

### Changed

- **`track()` is thread-safe.** The handle registry's `getOrCreate` is
  atomic: `parallel { }` workers tracking one store, or the tree fixture
  tracking a keyed store from the thread that created it, get exactly one
  handle per store (a handle built by a losing racer detaches its recorder
  and is dropped).

### Added

- **The tree fixture** (issue #21 plan PR 21-7, experimental):
  `StoreTestScope.track(tree: StoreTree, capture = Capture.All,
  resetAtTeardown = true)` — resolving beside the member `track(store)` —
  tracks the store a `tree` handle was obtained from and every store of its
  subtree now, and every store that joins it later (a child read for the
  first time, a keyed store created) — each a `StoreHandle`, one per store —
  and installs a tree middleware that records every member transaction with
  its node into the `TreeHandle.timeline` of `TreeEvent`s (`node`, `store`,
  `phase`, `transaction`, `cause`, `timestamp`), in observation order across
  the tree; `events(node)` narrows to the subtree at a node (a store's own
  events and its descendants', judged by where each event's store sat when
  it was recorded), a keyed store disposed since still listed under its
  branch and a released one under its former ancestors. `TreeHandle` exposes `tree` and `root` (the receiver
  store), `handle(store)`, `group(node)` (a `StoreHandleGroup` of the live
  stores of a subtree), `committedFrameIds(node)` and
  `consumeAllPendingErrors()`; `TreeHandle.shouldCommitTogether(node)`/
  `shouldNotCommitTogether(node)` judge from the tree's own timeline
  whether one frame spanned every live store of a subtree, each store by
  its own events, so a parent that took no part in a frame does not pass
  on its children's (a frame vetoed on its last participant committed
  nowhere). Teardown disposes the membership listener, resets the receiver
  and its subtree as one frame — the receiver's own states included, and
  every hydrator in it back to `Detached` —
  (unless opted out or the receiver is disposed) with the recorder still
  installed — so a vetoed reset fails the test naming the store and the
  veto, unless the body already failed; a store disposed in the body is
  skipped — then removes the recorder; no store is ever disposed.
  `track(tree)` is idempotent by receiver identity and throws on a
  disposed store.
