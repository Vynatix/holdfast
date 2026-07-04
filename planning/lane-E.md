# Lane E Implementation Plan — `:holdfast-testing` harness fixes

All work is confined to `/home/user/holdfast/holdfast-testing/` (plus its `api/` dumps and a new module README/CHANGELOG). No `:holdfast` core source files are touched. Investigation confirmed: no consumer outside the module's own `commonTest` uses any surface being changed (core `commonTest` uses `storeTest`/matcher basics only; `doc-snippets` and `holdfast-hallmark` tests use none of the affected APIs; zero `io.kotest` imports exist anywhere in the repo).

---

## 1. Per-finding plans

### F21 — `awaiting` timeout is a `CancellationException` (major) — **Size: M**

**Problem confirmed** at `/home/user/holdfast/holdfast-testing/src/commonMain/kotlin/com/vynatix/holdfast/testing/concurrency/Awaiting.kt:145-147`: `AwaitingTimeoutException : CancellationException`. Consequences: (a) thrown inside `launch {}`/`backgroundScope` it is treated as benign cancellation and the test can pass green; (b) inside `eventually {}` the `catch (c: CancellationException) throw c` at `Eventually.kt:44-45` rethrows it immediately, aborting the retry loop `eventually` exists to provide.

**Fix (concrete):**
1. Change supertype: `class AwaitingTimeoutException internal constructor(message: String) : AssertionError(message)` — matching `eventually`'s failure convention (`Eventually.kt:56`).
2. **Critical companion change** — split the two failure paths currently conflated in `awaiting` (lines 96-111): the `ClosedReceiveChannelException` path (scope-teardown closes the subscriber channel via `AwaitingRegistry.cancelAll`) must **not** become a loud `AssertionError`. On CRCE, throw a plain `CancellationException("storeTest scope tore down while awaiting")` so a forgotten `awaiting` in `backgroundScope` still unwinds quietly (the "never leaks past the test" contract in the KDoc at lines 46-51). Only genuine `withTimeoutOrNull` expiry throws `AwaitingTimeoutException`.
3. Fix the understated message in `buildTimeoutMessage` (lines 121-128): print both total and tail — `"awaiting: no event matched within Xms (saw $total events, last ${recent.size}: $recent)"`.
4. Rewrite the now-wrong KDoc blocks: lines 26-29, 53-61 ("Why a custom exception") and the class KDoc at 132-144 — new rationale: AssertionError so timeouts fail launched coroutines loudly and are retryable inside `eventually`; note TCE was never constructible anyway.
5. `Eventually.kt` needs **no code change** — the `CancellationException` guard now correctly retries awaiting timeouts (they fall into `catch (t: Throwable)`). Add a KDoc sentence saying `awaiting` timeouts are retried.

**Tests** (in `/home/user/holdfast/holdfast-testing/src/commonTest/kotlin/com/vynatix/holdfast/testing/AwaitingTest.kt` + `EventuallyTest.kt`):
- `assertFailsWith<AwaitingTimeoutException>` (existing, lines 58/98 — still compile) plus a new `assertFalse(err is CancellationException)` assertion.
- New: `eventually { awaiting(short) {...} }` where the event arrives on the 2nd poll — proves the retry loop survives an awaiting timeout.
- New: `launch { awaiting(...) }`-in-test-scope fails the test (assert via `assertFailsWith<AssertionError>` around a `joinAll`/child-failure propagation).
- New: teardown path stays quiet — body returns while a `backgroundScope` coroutine is suspended in `awaiting`; test must pass.

**API/doc lockstep:** `apiDump` — jvm dump line 324 superclass changes to `java/lang/AssertionError`; klib dump changes. Both `holdfast-testing/api/holdfast-testing.klib.api` and `api/jvm/holdfast-testing.api` committed. Changelog entry (see §Docs).

---

### P1-vacuous-matchers — empty predicates pass; never-recordable categories false-green (major) — **Size: M**

**Confirmed** at `TimelineMatcherRunner.kt:18,31,59,84` — all four runners `if (matcher.predicates.isEmpty()) return`. Also: `middleware<X>()` with no `.started/.completed/.errored` access registers **zero** predicates (register-on-access pattern, `TimelineMatcher.kt:110-127` + builder getters 218-237); and user-middleware events are never recorded in v1 (`Recorder.kt:64-68`), so `shouldNotFire { middleware<UserMw>().errored }` is green forever.

**Fix (concrete)**, all in `TimelineMatcher.kt` / `TimelineMatcherRunner.kt` / `TimelineCombinators.kt`:
1. **Empty predicate list**: all four runners throw `IllegalArgumentException("shouldFire/...: no predicates declared — the builder body registered nothing (did you call middleware<M>() without accessing .started/.completed/.errored?)")` instead of returning.
2. **Dangling `middleware<M>()` builder**: `TimelineMatcher` gains an internal `openBuilderLabels: MutableList<String>`; `MiddlewareBuilder` construction records its label, `registerFromBuilder` marks it satisfied. A new internal `TimelineMatcher.validate()` — called by all four runners before matching — throws `IllegalArgumentException` naming any builder that registered no predicate.
3. **Never-recordable negation**: in `runShouldNotFire` **only**, when the matcher was built with `vaultRef != null` (real-handle form) and contains any `Middleware*Predicate`, throw `UnsupportedOperationException("shouldNotFire{ middleware<...> }: user middleware lifecycle events are not captured in v1 — this assertion would pass vacuously")`. Positive combinators stay allowed (they already fail loudly when nothing matches, and the module's own recorder self-event test at `TimelineMatcherTest.kt:456-459` uses `shouldFireInExactOrder` with `middleware<Recorder<...>>()` — unaffected). Synthetic `List<StoreEvent>`-receiver forms stay fully permissive (matcher self-tests need them).
4. Lead each register-on-access getter KDoc with "Accessing this property registers a predicate."

**Tests:** new cases in `TimelineMatcherTest.kt`: empty builder throws for each combinator; bare `middleware<M>()` throws; `shouldNotFire` + middleware predicate on real handle throws; synthetic-list form still works.

**API changes:** none structural (stdlib exceptions, internal fields). klib/jvm dumps unchanged except nothing — still run `apiDump` to verify.

---

### P1-action-shadow — `Store.action` member shadows auto-tracking extension (major) — **Size: M**

**Confirmed**: `StoreAutoRegistration.kt:97` declares `fun <V, R> V.action(...)` which is *unreachable* — Kotlin member-vs-extension resolution always picks `Store.action`. The harness's own tests need `v.read { }` warm-up warts (`AutoRegistrationTest.kt`), and errors from `v.action {}` bypass the pending-error guard.

**Fix (concrete):**
1. **Delete** the dead `V.action` extension from `StoreAutoRegistration` (it can never win resolution; keeping it is a trap).
2. **Add a non-colliding verb** that always routes through the handle: `fun <V : Store<V>, R> V.act(body: V.() -> R): TransactionResult<R> = track(this).action(body)` (auto-registers, feeds `lastResult`, feeds the pending-error guard). Mirror KDoc from `suspendAction` (which is unaffected — no member collision).
3. Rewrite the file-level KDoc (lines 47-63): the caveat text shrinks to "never call `v.action {}` expecting tracking — use `v.act {}`, `track(v)`, or handle.action"; update the lead example (line 18) which currently *shows the broken pattern*.
4. **Diagnostic tie-in with F24**: matcher failure messages get an empty-timeline hint (see F24 item 1) explicitly naming `Store.action`-before-tracking as a known cause.
5. Update `AutoRegistrationTest.kt` — replace warm-up warts with `act`, keep one regression test documenting that raw `v.action {}` on an untracked store records nothing and bypasses the guard.

**API/doc lockstep:** `apiDump` — removal of `StoreAutoRegistration.action` (default interface method) + addition of `act` in both dumps. ABI-breaking removal, acceptable at 0.x since the removed symbol was unresolvable from user call sites anyway. Update module README (new) and any KDoc cross-references.

---

### F24 — timeline/diagnostics bundle (minor, 7 sub-items) — **Size: M**

1. **Failure messages never print the timeline** (`TimelineMatcherRunner.kt:42-46,121-139`): add `internal fun formatTimeline(events: List<StoreEvent>): String` (indexed, one event per line: type, txn id, prop/values) in `TimelineMatcherRunner.kt`; append `"\nTimeline (N events):\n..."` to every `AssertionError` in all four runners. When the timeline is **empty**, append the three known causes: `Capture.None`, `suspendAction` (v1 contract, `Recorder.kt:69-72`), and untracked `Store.action` (shadow, see P1-action-shadow).
2. **`emitted(prop, Any?)` untyped** (`TimelineMatcher.kt:153-164`): generify in place — `fun <T : Any> emitted(prop: KProperty1<V, State<T>>, value: T?)`. `KProperty1` is covariant in its return type, so `MyStore::count` still resolves; JVM erased signature is unchanged (no jvm-dump churn), klib dump changes. Wrong-type values now fail at compile time.
3. **`bridge(prop)` returns `BridgeView<*>`** (`StoreHandle.kt:202`): generify in place — `fun <T : Any> bridge(prop: KProperty1<V, State<T>>): BridgeView<T>` (adding an overload is impossible: identical JVM erasure would clash). Internal unchecked cast from `bridgeWrappers[state]` is sound (map keyed by the `State<T>` itself). Removes the cast wart in the module's own KDoc (`BridgeView.kt:42`) and tests (`BridgeViewTest.kt:116`, `RecordingBridgeTest.kt:156` — update both).
4. **Invalid KDoc example** (`StoreHandleGroup.kt:19`): `(a and b) shouldCommitTogether ()` is not valid Kotlin (`shouldCommitTogether` is non-infix, zero-arg). Fix to `(a and b).shouldCommitTogether()` — matching the correct form already in `GUIDE.md` §15.6.
5. **`LatchedBridge` unused `initial` + no-op `releasePublish`** (`LatchedBridge.kt:40-42,133-135`): remove the `@Suppress("unused") private val initial: T` constructor parameter → `class LatchedBridge<T : Any>()` (callers now write the type argument explicitly; only one in-repo construction site, `LatchedBridgeTest.kt`/`BridgeViewTest.kt`). Mark `releasePublish()` `@Deprecated("No-op: publish never suspends in v1; remove the call.", level = WARNING)` rather than deleting (KDoc promises API parity; deletion can ride the next minor). Both changes hit both api dumps.
6. **`testScope.` prefix for time control** (`StoreTestScope.kt:23-25,40-48`): add forwarding members so `runTest` muscle memory works: `fun runCurrent() = testScheduler.runCurrent()`, `fun advanceUntilIdle() = testScheduler.advanceUntilIdle()`, `fun advanceTimeBy(delay: Duration) = testScheduler.advanceTimeBy(delay)`, `val currentTime: Long get() = testScheduler.currentTime`. API additions → apiDump.
7. **`parallel` + `awaiting` virtual-time flake** (`Parallel.kt:17-24` vs `Awaiting.kt:32-34`): `parallel` runs on real `Dispatchers.Default` threads while `awaiting`'s timeout burns virtual time that advances to expiry the instant the test thread idles — a short `awaiting` racing `parallel` workers is a flake. **Doc-only fix** (code fix would forfeit documented virtual-time semantics): add a "Mixing with `parallel`" hazard section to both KDocs recommending generous timeouts or `eventually` for real-thread work. No API change.

**Tests:** timeline-dump presence asserted in one failure-message test per combinator; empty-timeline hint test; typed `emitted`/`bridge` compile-and-pass tests in `TypedViewsTest.kt`/`BridgeViewTest.kt`; `LatchedBridge` construction updates; new `StoreTestScopeTest` cases calling `advanceUntilIdle()` unprefixed.

---

### P1-kotest-dep — `api(kotest-assertions)` exposed, zero usages (minor) — **Size: S**

**Fix:** delete line 28 `api(libs.kotest.assertions.core)` from `/home/user/holdfast/holdfast-testing/build.gradle.kts`. Rewrite the three KDoc samples that use kotest's `shouldBe` so docs stop implying kotest is on the classpath: `BridgeView.kt:27-31`, `RecordingBridge.kt` (~22-27), `OpenTransaction.kt` (~182-184) — use `assertEquals` or the harness's own matchers. **Do not** remove the `kotest`/`kotest-assertions-core` entries from `gradle/libs.versions.toml` (shared file across lanes; an unused catalog entry is harmless — flag for a later sweep).

**Tests:** `./gradlew :holdfast-testing:jvmTest :holdfast:jvmTest` — proves nothing depended on it (confirmed by grep: zero `io.kotest` imports repo-wide).

**API note:** BCV dumps do not track dependencies → no `.api` change; but the published POM loses a transitive dep — breaking for any external consumer leaning on it. 0.x + changelog entry ("Removed: kotest-assertions transitive dependency — the harness is assertion-library-free").

---

### P1-testing-bundle — remaining debt: README (major umbrella) — **Size: M**

Shadowing, vacuous matchers, kotest handled above. Remaining item: **module README**.

**Fix:** create `/home/user/holdfast/holdfast-testing/README.md` (root `README.md:84` already links to the directory — no root-README edit needed): `storeTest {}` quickstart (copy-pasteable, using `track`/`act` — post-shadow-fix idioms only), matcher tour (`shouldFire*` family, result matchers, frame matchers), concurrency helpers (`awaiting`/`eventually`/`parallel`/`barrier`/`openTransaction`) including the new AssertionError timeout semantics and the parallel+awaiting hazard, the pending-error-consumption teardown contract, bridge fakes (`RecordingBridge`/`LatchedBridge`/`FailingBridge`/`FakeKvStore`), v1 capture-gap table (suspendAction, user middleware, late bridges), and "assertion-library-free" note. Also create `/home/user/holdfast/holdfast-testing/CHANGELOG.md` (Keep-a-Changelog, `[Unreleased]`) to hold this lane's entries instead of contending on `holdfast/CHANGELOG.md`.

---

## 2. Intra-lane ordering

1. **E-1: F21** (Awaiting/Eventually) — self-contained, highest severity.
2. **E-2: P1-vacuous-matchers** (TimelineMatcher/Runner/Combinators).
3. **E-3: F24 item 1** (timeline printing + empty-timeline hints) — same files as E-2; do immediately after so failure-message tests are written once.
4. **E-4: P1-action-shadow** (StoreAutoRegistration + AutoRegistrationTest) — its diagnostic hint depends on E-3's message helper.
5. **E-5: F24 items 2-7** (typed views, StoreHandle.bridge, LatchedBridge, StoreTestScope forwarders, KDoc fixes).
6. **E-6: P1-kotest-dep** (build file + KDoc sample rewrites — after E-5 so BridgeView KDoc is edited once).
7. **E-7: README + CHANGELOG**, then a **single `./gradlew :holdfast-testing:apiDump`** capturing all API deltas, then `./gradlew check`.

---

## 3. SHARED-FILE MANIFEST

**Modified (all under `holdfast-testing/`):**
- `holdfast-testing/build.gradle.kts`
- `holdfast-testing/src/commonMain/.../testing/concurrency/Awaiting.kt`, `Eventually.kt` (KDoc only), `Parallel.kt` (KDoc only)
- `holdfast-testing/src/commonMain/.../testing/matcher/TimelineMatcher.kt`, `TimelineMatcherRunner.kt`, `TimelineCombinators.kt` (KDoc), `StoreHandleGroup.kt` (KDoc)
- `holdfast-testing/src/commonMain/.../testing/StoreHandle.kt`, `StoreTestScope.kt`, `StoreAutoRegistration.kt`
- `holdfast-testing/src/commonMain/.../testing/bridge/LatchedBridge.kt`, `BridgeView.kt` (KDoc), `RecordingBridge.kt` (KDoc)
- `holdfast-testing/src/commonMain/.../testing/concurrency/OpenTransaction.kt` (KDoc sample only)
- `holdfast-testing/src/commonTest/.../AwaitingTest.kt`, `EventuallyTest.kt`, `AutoRegistrationTest.kt`, `StoreTestScopeTest.kt`, `TypedViewsTest.kt`, `matcher/TimelineMatcherTest.kt`, `bridge/BridgeViewTest.kt`, `bridge/LatchedBridgeTest.kt`, `bridge/RecordingBridgeTest.kt`
- `holdfast-testing/api/holdfast-testing.klib.api`, `holdfast-testing/api/jvm/holdfast-testing.api` (regenerated)
- Possibly `holdfast-testing/detekt-baseline.xml` (only if new code trips a rule; prefer clean code)

**Created:** `holdfast-testing/README.md`, `holdfast-testing/CHANGELOG.md`

**Core `:holdfast` files: NONE touched.** Also deliberately NOT touched (cross-lane conflict avoidance): root `README.md` (line 84 stays accurate), `holdfast/GUIDE.md` (§15.6 example already valid), `holdfast/CHANGELOG.md` (using new module changelog instead — if the integrator prefers the core changelog, that is one append-only hunk under `[Unreleased]`), `gradle/libs.versions.toml` (kotest catalog entry left in place), `buildSrc/**`. Conflict risk with the other 5 workstreams is therefore near-zero unless another lane also edits `holdfast-testing/` (the pending-error guard or `storeTest` internals — `StoreTest.kt` itself is untouched here).

---

## 4. Risk notes + recommended defaults

- **F21 default: `AssertionError` subclass, keep the `AwaitingTimeoutException` name.** Rationale: matches `eventually`, reported as test *failure* not error, survives `launch`, retryable in `eventually`. The one real hazard is the teardown path: mapping channel-close to the same exception would turn the documented quiet-unwind of forgotten `awaiting`s into background failures — hence the mandatory CRCE→`CancellationException` split. Existing `assertFailsWith<AwaitingTimeoutException>` call sites keep compiling; anyone catching `CancellationException` to detect timeout (none in-repo) breaks — changelog it.
- **kotest removal is NOT BCV-visible** (dumps don't list deps) but **is a breaking POM change** for external consumers using kotest transitively. Acceptable pre-1.0; must be a `Removed` changelog entry. In-repo it is provably safe (zero imports).
- **Empty-predicate throw is a behavior change**: a downstream test with an accidentally empty builder flips from silent-pass to failure. That is the point, but it belongs in the changelog under `Changed`.
- **`shouldNotFire` + middleware throw**: scoped to real-handle receivers only, so synthetic-timeline self-tests and the recorder-self-event positive tests keep working. Do not extend the throw to bridge predicates — those *are* recordable when attached pre-`track` (documented hazard instead).
- **`LatchedBridge(initial)` removal is source-breaking** (callers must now write `LatchedBridge<String>()` — losing inference from the argument). Alternative if the integrator wants zero source breaks: keep the parameter and expose it as `val initial: T`. Recommended default: remove — it is a documented non-feature and false affordance (suggests load-on-attach, which the class explicitly refuses to do).
- **In-place generification of `bridge`/`emitted`**: JVM ABI unchanged (same erasure — which is also why an overload is impossible for `bridge`), klib dump changes; a caller holding a star-projected `KProperty1<V, State<*>>` can no longer call them (no in-repo occurrences; concrete property references are the universal idiom).
- **Removing `StoreAutoRegistration.action`** deletes a public (default interface) method → ABI break, but the method was unreachable from any call site due to member shadowing, so real-world breakage is ~zero. Verb choice for the replacement (`act` recommended; alternatives `transact(v) {}`) should be confirmed before implementation since it becomes permanent API.
- **wasmJs**: not a target of `:holdfast-testing` — no wasm-specific concerns.
- Run order for verification: `./gradlew :holdfast-testing:apiDump` then `./gradlew check` (covers jvmTest, testAndroidHostTest, apiCheck, detekt, ktlint); iOS tests are macOS-CI-only.

## 5. Size summary

| Finding | Size |
|---|---|
| F21 | M |
| P1-vacuous-matchers | M |
| P1-action-shadow | M |
| F24 (7 sub-items) | M |
| P1-kotest-dep | S |
| P1-testing-bundle (README/CHANGELOG remainder) | M |

### Critical Files for Implementation
- /home/user/holdfast/holdfast-testing/src/commonMain/kotlin/com/vynatix/holdfast/testing/concurrency/Awaiting.kt
- /home/user/holdfast/holdfast-testing/src/commonMain/kotlin/com/vynatix/holdfast/testing/matcher/TimelineMatcherRunner.kt
- /home/user/holdfast/holdfast-testing/src/commonMain/kotlin/com/vynatix/holdfast/testing/matcher/TimelineMatcher.kt
- /home/user/holdfast/holdfast-testing/src/commonMain/kotlin/com/vynatix/holdfast/testing/StoreAutoRegistration.kt
- /home/user/holdfast/holdfast-testing/src/commonMain/kotlin/com/vynatix/holdfast/testing/StoreHandle.kt