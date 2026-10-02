# Changelog — `:holdfast-testing`

All notable changes to the testing harness. Earlier harness changes are
logged in [`holdfast/CHANGELOG.md`](../holdfast/CHANGELOG.md) under
`:holdfast-testing` prefixes.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- **Issue #21 review (PR #24).** `trackTree`'s teardown no longer spins
  forever on the test thread when a live leaf is still held by an entry it
  cannot wait out (a `suspendAction`/`suspendAtomic` body parked in
  un-joined work, a thread inside an action, or a holder of the leaf's
  serializer): it re-probes every live leaf for a bounded time (about one
  second of real time), so a transient holder — an in-flight
  `suspendDerived` recompute on `Store.scope`, which no test can join — is
  waited out and the reset runs; a leaf still held when the budget ends
  skips the tree's reset and fails the test, naming the tree and the held
  leaves in a section of the teardown `AssertionError` of its own (after
  the unconsumed errors and the vetoed resets) that points at the opt-out,
  `trackTree(root, resetAtTeardown = false)`, for a test that deliberately
  parks work. A silent skip would leak the body's values into the next
  test. `trackTree` from inside a
  leaf's action or an `atomic` frame is refused and leaves nothing behind
  (no tracked leaf, no membership listener, no tree middleware). Teardown
  reports unconsumed `TransactionResult.Error`s and a failed tree reset in
  one `AssertionError`, the unconsumed errors first. A handle that loses
  the track registration race hands its bridge wrappers to the winner (or
  unwraps them when the winner is `Capture.None`) instead of feeding a
  disposed recorder.

### BREAKING (behavior)

- **Teardown keeps the middleware a test installed.** `storeTest`'s
  teardown used to detach the recorder with `Store.clearMiddleware()`,
  dropping every middleware the test had registered on the store as well.
  It now removes the recorder alone (through core's
  `@StoreInternalApi internalRemoveMiddleware`, which removes one middleware
  by identity from a store's consumer list or its outer ring): a store that
  outlives a `storeTest` block keeps behaving as the test left it, and a
  tree's `Root.middlewares` ring is untouched. A test that relied on
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
  `trackTree(root, capture = Capture.All, resetAtTeardown = true)` tracks
  every leaf of a `Root` now and every keyed store created under it later
  — each a `StoreHandle`, one per store — and installs a tree middleware
  that records every leaf transaction with its node into the
  `TreeHandle.timeline` of `TreeEvent`s (`node`, `store`, `phase`,
  `transaction`, `cause`, `timestamp`), in observation order across the
  tree; `events(node)` narrows to a subtree, a keyed store disposed since
  still listed under its branch. `handle(store)`, `group(node)` (a
  `StoreHandleGroup` of the live leaves under a node), `committedFrameIds
  (node)` and `consumeAllPendingErrors()`; `TreeHandle.shouldCommitTogether
  (node)`/`shouldNotCommitTogether(node)` judge a subtree's frames from the
  tree's own timeline (a frame vetoed on its last participant committed
  nowhere). Teardown disposes the membership listener, resets the tree as
  one frame (unless opted out or the root is disposed) with the recorder
  still installed — so a vetoed reset fails the test naming the leaf and the
  veto, unless the body already failed; a leaf disposed in the body is
  skipped — then removes the recorder from the root; the root is never
  disposed. `trackTree` is idempotent by root identity and throws on a
  disposed root.
