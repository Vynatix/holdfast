# Changelog — `:holdfast-testing`

All notable changes to the testing harness. Earlier harness changes are
logged in [`holdfast/CHANGELOG.md`](../holdfast/CHANGELOG.md) under
`:holdfast-testing` prefixes.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- **A suspending entry that ends on another thread keeps its commit markers.**
  Under `suspendAction`/`suspendAtomic` the completed middleware hook runs on
  the thread the body ended on; `Transaction.modifiedStates` is
  owner-thread-confined, so a body that resumed on another dispatcher thread
  after its last suspension made the recorder's read throw — and the
  suspending path's per-hook `runCatching` swallowed it, leaving the
  timeline with `started` alone (no emission events, no `committed`): an
  intermittently, inexplicably empty timeline for coroutine-first tests. The
  recorder now degrades the way `ProfilingMiddleware` degrades its
  attribution: such a transaction records no emission events, but its
  `committed` (or `errored`/`rolled back`) markers always land
  (`SuspendingHopTimelineTest`). Full emission-event fidelity after a thread
  hop needs a core seam (a post-commit recorder hook, or a
  non-owner-thread-checked modified-states accessor) and stays a named
  follow-up; suspending entries that end on the thread they began — the
  common case on single-threaded dispatchers such as `runTest`,
  `Dispatchers.Main` and every coroutine on wasmJs — always recorded the
  full timeline.
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

### Removed

- **The `V.action` auto-registration extension** (0.5.0 "harness earns its
  claim"). `Store.action` is a member and always won overload resolution,
  so the extension was dead at every call site: a bare
  `store.action { … }` inside `storeTest` never auto-tracked and never fed
  the handle's `lastResult` slot or the unconsumed-error guard. Replaced
  by `V.trackedAction` — a name `Store` does not declare, so Kotlin cannot
  shadow it and it always routes through the `StoreHandle` (auto-track,
  result recorded, pending-error guard armed); its KDoc teaches the
  routing, and `AutoRegistrationTest` pins both sides.

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
- **Never-recordable matcher categories refuse instead of passing
  vacuously.** The handle-receiver timeline combinators (`shouldFire`,
  `shouldFireInOrder`, `shouldFireInExactOrder`, `shouldNotFire`) now throw
  a teaching `IllegalStateException` — mirroring how Secret value-matchers
  refuse — for two categories whose events can never appear on a recorded
  timeline: `middleware { }` predicates the recorder's self-events cannot
  satisfy (user middlewares are not wrapped in v1, so their
  started/completed/errored events never appear), and
  `bridgePublished`/`bridgeObserved` on a state whose bridge the recorder
  never wrapped (none attached, attached after `track(v)`, or the state not
  materialized then). Previously `shouldNotFire` with such a predicate
  passed vacuously and the positive forms failed with a generic
  "did not match". Synthetic `List<StoreEvent>` receivers (hand-built
  timelines) and `Capture.None` stay permissive.

### Changed

- **`track()` is thread-safe.** The handle registry's `getOrCreate` is
  atomic: `parallel { }` workers tracking one store, or the tree fixture
  tracking a keyed store from the thread that created it, get exactly one
  handle per store (a handle built by a losing racer detaches its recorder
  and is dropped).
- **`suspendAction` feeds the timeline.** Verified and pinned by
  `StoreHandleActionTest.suspendActionFeedsTheTimeline`:
  `:holdfast-coroutines`' suspending entries fire the middleware chain in
  concentric-ring order like the blocking path, so the harness recorder
  records suspending transactions the same as blocking ones. The stale
  Recorder/StoreHandle KDoc claims ("suspendAction does not run the
  middleware chain", "suspendAction not running middleware in 1.1") are
  corrected — there is no never-recorded suspending-timeline category
  today.
- **Dependency diet.** Dropped the unused `api(kotest-assertions-core)`:
  no source in this module — or in any module compiling against it
  (`:holdfast`'s commonTest, `:doc-snippets`) — imports `io.kotest`; the
  matcher surface is hand-rolled. The hallmark matcher
  (`shouldBeBoxedAs`) already lives in `:holdfast-hallmark`; nothing
  hallmark-shaped is hard-wired into this module's build.

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
- **Module README** (`holdfast-testing/README.md`, 0.5.0 funnel): the
  dependency line, a complete copy-paste quickstart under the current
  `storeTest { }` name (`track`, a handle action, a `shouldFireInOrder`
  timeline matcher), how to run it, the action-shadowing trap with the
  `trackedAction` verb, a compact surface listing, what teardown
  enforces, and the never-recordable/Secret refusals. `:doc-snippets`
  does not twin module READMEs outside its tracked list, so the README's
  fenced blocks are not CI-compiled; both were verified verbatim by a
  temporary mirror test.
