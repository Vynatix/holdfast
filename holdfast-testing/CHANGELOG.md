# Changelog — `:holdfast-testing`

All notable changes to `:holdfast-testing` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`StoreHandle.bridge(prop)` now returns a typed `BridgeView<T>`** (was
  `BridgeView<*>`), keyed by the property's state type — no `as BridgeView<T>`
  cast at the call site.
- **`TimelineMatcher.emitted(prop, value)` is typed**: `emitted(Store::count,
  "nope")` fails at compile time instead of silently never matching.
- **`StoreTestScope` forwards the `runTest` time-control vocabulary** as members
  — `runCurrent()`, `advanceUntilIdle()`, `advanceTimeBy(Duration)`, and
  `currentTime` now work unprefixed inside a `storeTest { }` body.
- **`Store.act { }` verb** — a tracked, auto-registering action that routes
  through the handle (feeds `lastResult` and the pending-error guard), replacing
  the unreachable `Store.action` auto-tracking extension.
- Module `README.md` and this changelog.

### Changed

- **`awaiting` timeouts now throw `AwaitingTimeoutException : AssertionError`**
  (was a `CancellationException`), so a timeout inside a launched coroutine fails
  the test loudly and is retryable inside `eventually`. A forgotten `awaiting`
  suspended at scope teardown still unwinds quietly (as a `CancellationException`).
  The timeout message now reports the total and tail event counts.
- **Timeline matcher failure messages print the timeline.** When the timeline is
  empty they list the known causes (`Capture.None`, `suspendAction` under
  `Capture.None`, and an untracked `Store.action`).
- **Vacuous matchers now fail loudly.** An empty matcher builder (`shouldFire { }`
  with no predicates), a dangling `middleware<M>()` with no lifecycle access, and
  `shouldNotFire { middleware<…> }` on a real handle (user middleware events are
  not captured in v1) all throw instead of passing silently.
- **`storeTest` teardown clears the process-global `FrameObservers` registry**,
  so a `FrameObserver` registered inside one `storeTest` never fires in a later
  one in the same process.
- Corrected stale KDoc that claimed `suspendAction` does not run the middleware
  chain: it does, so the recorder captures suspending actions on the timeline
  exactly like blocking ones.

### Removed

- **`kotest-assertions-core` transitive dependency.** The harness is
  assertion-library-free; KDoc samples that showed kotest's `shouldBe` were
  rewritten against `assertEquals` / the harness matchers. External consumers who
  leaned on the transitive `io.kotest` classpath must add it themselves.
- **`StoreAutoRegistration.action` extension** — it was unreachable (a `Store.action`
  member always shadowed it). Use `Store.act { }`, `track(v)`, or `handle.action`.
- **`LatchedBridge`'s unused `initial` constructor parameter** — construct with an
  explicit type argument, `LatchedBridge<T>()`.

### Deprecated

- **`LatchedBridge.releasePublish()`** (WARNING) — always a no-op because publish
  never suspends in v1; remove the call.
