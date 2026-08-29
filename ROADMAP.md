# Holdfast Product Roadmap

**Date:** 2026-06-12 · **Current version:** 0.1.0 (unpublished) · **Owner:** solo maintainer
**Inputs:** the 2026-06-12 usability analysis (112 verified findings — see `USABILITY-ANALYSIS.md`), its 2026-07-04 second pass, an independent correctness review that reproduced three previously-unrecorded defects on `9e0fa57`, three competing strategy drafts (adoption-first, correctness-first, ecosystem-first) stress-tested by an adversarial release-engineering review, plus repo-verified constraints.

---

## Strategic thesis

Holdfast's differentiator is real and verified — atomic multi-property commit with rollback, savepoints, and deadlock-safe cross-store transactions have no Kotlin-ecosystem equivalent — but today the library is uninstallable, its quick-start doesn't compile, and its docs contradict its code. The sequencing below was **adoption-shaped with correctness gates** — fix the funnel first, then the footguns. It is now **correctness-first**: a review of `9e0fa57` reproduced a guaranteed single-threaded deadlock between `suspendAction` and `derived()`, permanently broken savepoints on any coroutine-touched store, and a partial commit from a throwing bridge. Publishing a library that hangs when two documented features are combined is worse than publishing late, so the concurrency and atomicity work moved ahead of the install story. The original shape otherwise stands: fix the funnel first (a developer gives a 0.x library fifteen minutes; a broken install reads as abandonment), kill the first-session footguns before any marketing (a launch is a one-shot consumable), go deep on the integrations that decide adoption (Compose, ViewModel, testing), and freeze only what is mechanically proven. 0.x is the only cheap window for breaking changes — every rename and removal is front-loaded while the user count is ~zero, making 1.0 a renumbering, not a migration.

## Release-train principles (the rules each milestone obeys)

1. **One big break.** All cheap API breaks ship across 0.2.0–0.3.0 (the commit-fanout and `@StoreInternalApi` changes, then rename completion, overload removal, result ergonomics) with `@Deprecated(ReplaceWith)` aliases for exactly one minor; aliases are removed **before** the RC is cut, so the RC and 1.0 are byte-identical.
2. **Docs cannot rot.** The snippet-test gate (every README/GUIDE code block compiled — and executed where output is claimed) lands before any doc rewriting, and must pass on a **fresh clone** (which forces the hallmark build-decoupling below).
3. **The serializer contract is one bug, not five.** The livelock was believed to be a deep K/N or kotlinx-`Mutex` race, so it was deferred behind a two-phase plan. It is not: `AsyncSerializer` is non-reentrant, permanently installed on first coroutine use, acquired unconditionally by a reentrant API, and held across the post-commit drain. That single error produced the `derived` deadlock, the broken savepoints, the swallowed sync recompute, the unserialized blocking `atomic()`, and the `action`-under-`suspendAction` livelock. Four of the five are fixed in 0.2.0 by local changes; only the last still needs the fail-fast guard.
4. **Hallmark is decoupled — build and publish.** `:holdfast-hallmark*` modules become property-gated in `settings.gradle.kts` (excluded by default; included with `-Pholdfast.includeHallmark`), so a fresh clone builds standalone. They ship only after `vynatix/hallmark` itself reaches Central, possibly relocated to that repo. Core 1.0 never blocks on the sibling.
5. **Launch is one-shot.** No announcement, awesome-kotlin submission, or comparison content until the 0.4.0 footgun pass is complete. Known issues are documented by name with workarounds — honesty is the brand repair for the badge that lied.
6. **New surface needs soak.** Anything introduced after 0.4.0 (lifecycle/savedstate modules) is labeled experimental at 1.0 unless it has had two minors of soak.

---

## Milestones

### 0.2.0 — "Correct under concurrency" *(in progress)*

**Theme:** the transactional guarantees hold before anyone is invited to depend on them.

| Deliverable | Status |
|---|---|
| **Fix the `AsyncSerializer` contract.** Drain post-commit tasks outside the serializer bracket in both the blocking and suspending paths (mirroring what `suspendAtomic` already did); skip the acquire for nested actions. Fixes the `suspendAction`+`derived` deadlock, the silently-swallowed sync recompute, and permanently-broken savepoints. | **Done** |
| **Commit fanout cannot tear a transaction.** Phase the commit — apply all writes (assignment only), then all observers, then all bridge publishes, then events — and isolate the fanout phases per state, reporting through `uncaughtObserverHandler`. | **Done** |
| **Serialize blocking `atomic()`** against in-flight suspending work, in `lockOrderKey` order. Closes the second-pass BLOCKER. | **Done** |
| **`commit`/`rollback` catch `Throwable`**, propagate `CancellationException` unwrapped, and `TransactionException` names store/phase/state count. | **Done** |
| **Observer callbacks run outside `observersLock`**, so one slow observer no longer blocks subscribe/dispose from other threads. | **Done** |
| **`StoreLock` parks instead of spinning** (`SynchronousMutex`), so contention stops presenting as unexplained CPU load in `RUNNABLE`. | **Done** |
| **Savepoint-or-teach for nested `suspendAction`** and `suspendAtomic`-inside-`suspendAction` on an overlapping store — currently a raw kotlinx mutex error. `Mutex.holdsLock(owner)` detects it; needs care where the owner is the `SuspendActionFallbackOwner` singleton. | Open (M) |
| **Fail fast on blocking `action` inside a `suspendAction` body** — the last remaining spin. Thread identity cannot distinguish it from a legitimate blocking caller on a reused pool thread, so this needs a "body is running on this thread" marker propagated like `FrameMarkerContext` already does for frames. | Open (M) |
| **Verify the locking changes on iOS.** `StoreLock` and the drain placement touch `pthread`-backed actuals; `iosSimulatorArm64Test` needs a macOS host. | Open (S) |
| Standalone `update` reads inside its synthesized action; disposed checks on `atomic`/`derived`/`suspendAction`/`suspendAtomic`; owner check on `emit()`. | Open (M) |

**Success criteria:** every defect above has a regression test that fails without its fix and completes rather than hangs; `./gradlew check` green on JVM, Android host and iOS simulator; no documented feature pair deadlocks.

### 0.3.0 — "Installable and honest" *(2–3 weeks + Central-verification latency)*

**Theme:** make every sentence in the funnel true; spend the breaking-change budget in one cut.

| Deliverable | Size |
|---|---|
| **Strip the Maven Central badge and install block now.** Verified live: `repo1.maven.org/maven2/com/vynatix/holdfast/` returns 404 and the search index reports `numFound: 0` for the whole group. Until the pipeline works, the README promises a 404 at minute two. | S |
| **Release engineering rip-and-replace.** The homegrown `holdfast.publish.sonatype` convention points `maven-publish` at the Central Portal upload API, which it cannot speak — replace both publish conventions with the vanniktech plugin (which publish.yml already assumes) end-to-end: namespace verification preflight on the Portal, signing, a scripted **local** release path so releases never depend on CI availability. | M |
| **Unblock CI: settle the org billing lock.** The repo is already public — Actions (incl. macOS runners) is free for public repos; the lock is org-level. One support/billing action replaces any "migrate CI" project. Fallback if it drags: documented local gate script. | S |
| **Decouple hallmark from build and publish** (property-gated module inclusion; remove from README module table or mark "unreleased, requires Hallmark"). | S |
| **Finish the rename in public API**: `vaultTest`→`storeTest`, `bindVault`→`bindStore`, `owningVault`→`owningStore` (7 files in holdfast-testing + core), deprecated aliases for one minor, `apiDump`. | S–M |
| **Remove the `context(CoroutineScope)` overloads** on `asStateFlow` + bridge factories (bytecode-proven ambient-scope capture; not documentable away). | S |
| **`TransactionResult` ergonomics**: `getOrThrow()`, `onError {}`, `valueOrNull`; README quick-start rewritten to *surface* its deliberately-failing transaction. | S |
| **Snippet-test module**: every fenced Kotlin block in README/GUIDE compiles (and asserts claimed output) in `./gradlew check`, on a fresh clone. | M |
| **Docs-truth sweep**: the five doc/code contradictions, stale `*Holdfast`/`*Vault` sample classes, `holdfastTest` references, `asEagerStateFlow` ghost, `validation*` coordinates, MIGRATING.md links (stub or delete — file doesn't exist), compose-README shadowing bug, toolchain floor (Kotlin 2.3.x / JVM 21) documented in Install. | M |
| **Known-issues section** rewritten against what 0.2.0 actually fixed: the remaining `action`-in-`suspendAction` spin, the `store{}`-vs-`action{}` trap, and cross-store observer writes deadlocking on `transactionLock`. | S |
| **wasmJs → explicit experimental tier** (tests disabled, `FileSystemKvStore` throws, `suspendDerived` unusable — say so; keep the artifact: the build comment indicates an external consumer). | S |
| **Publish 0.3.0 to Maven Central**: `holdfast`, `-coroutines`, `-compose`, `-testing`. | M |

**Success criteria:** all four artifacts return HTTP 200 on repo1.maven.org; a scripted fresh-clone consumer project with the README install block + quick-start pasted verbatim compiles and prints the documented output including the surfaced error; `grep -ri vault */api/*.api` returns only deprecated aliases; link checker reports zero broken internal links; `./gradlew check` passes on a fresh clone with no sibling-repo ritual.

### 0.4.0 — "The first session cannot lie" *(2–4 weeks)*

**Theme:** kill every silent-failure footgun a newcomer hits in hour one; turn the analysis's empirical probes into permanent regression tests.

- Enforce the CRTP self-type at construction — `class Foo : Store<Bar>()` fails at init with a two-type teaching message (S)
- Defuse `store { }` vs `store action { }` — bare invoke becomes non-mutating; mutate outside an action via invoke fails loudly (M)
- Flip `distinct` default to `true` (matches the StateFlow dedup contract users carry in — and what the GUIDE already claims) (S)
- Eager state registration via `provideDelegate` — kills the snapshot/restore-on-untouched-store surprise class (M)
- `suspendAction` disposed-store check matches blocking `action`; `emit()` gains the ownership check `mutate` has (S)
- Observer-exception default: log loudly instead of silent swallow; document `uncaughtObserverHandler` where users will find it (S)
- Remove the 0.3.0 deprecated aliases (the one-minor promise) (S)

**Success criteria:** every footgun from USABILITY-ANALYSIS Theme 2 has a named regression test; the empirical probes (increment loss, rollback verification, overload resolution) run in CI; zero silent-failure paths for the misuse cases in the error-experience lens.

### 0.5.0 — "The harness earns its claim" *(2–3 weeks)*

**Theme:** the testing harness is a genuine differentiator with a broken funnel — fix the funnel; write the two missing GUIDE chapters.

- holdfast-testing README + 30-second quickstart under the new `storeTest` name (S)
- Fix the `action`-shadowing trap (remove the dead extension; add a non-colliding verb that always routes through the handle) (M)
- Synthesize timeline events for `suspendAction` so coroutine-first users stop getting inexplicably empty timelines (L)
- Never-recordable matcher categories throw instead of passing vacuously (S)
- Dependency diet: drop unused `api(kotest-assertions)`; make the hallmark matcher optional (S)
- GUIDE chapters: **Threading & Scopes** (4-level chain, observers-under-lock, dispose asymmetry, `defaultScope`-in-tests + a test-reset story) and **Lifecycle & Events** (who calls `dispose()`, EventfulStore patterns) (M)

**Success criteria:** a newcomer can find, install, and write their first harness test from the module README alone; harness traps from Theme 6 have regression tests; threading model documented where users look.

### 0.6.0 — "Compose-native + the migration story" *(4–6 weeks; the launch happens after this ships)*

**Theme:** integration depth on the three surfaces that decide adoption; then — and only then — the public launch.

- holdfast-compose depth: single-mention observation (extension on `State<T>`), `rememberStore`, event-collection helper — StateFlow-ergonomics parity for the common cases (M)
- `holdfast-lifecycle` module (experimental tier): ViewModel ownership pattern, `dispose()` wired to `onCleared`, factory helpers (M)
- DI recipes as *documentation* (Koin + Hilt patterns, compiled in the snippet module — not new artifacts) (S)
- Process-death story: SavedStateHandle recipe built on `snapshot()`/`restore()` (recipe first; promote to a module only on demand) (M)
- **Migration guide**: mechanical StateFlow→Holdfast and MVI→Holdfast mapping tables, validated by porting one banking-demo feature side-by-side (M)
- banking-demo: Compose Multiplatform UI + ViewModel + DI wiring as the flagship evaluation track (L)
- **Launch**: announcement anchored on verified differentiators, awesome-kotlin, comparison content; governance pack ships *first* (SECURITY.md, issue templates, triage expectations) so launch-generated load lands on rails (S)

**Success criteria:** the Compose example in docs is the *recommended* pattern, not a hand-rolled bridge; a StateFlow ViewModel ports to Holdfast using only the migration guide; governance files exist before the first wave of issues.

### 0.7.0 — "Close the books" *(3–5 weeks)*

**Theme:** every open question gets a decision; everything breaking ends here.

- Cross-target concurrency soak suite (iOS included) replaces the 10-minute test-task timeout as the livelock backstop. The livelock hard gate is retired: it was root-caused and fixed in 0.2.0, not deferred (M)
- **Dependency-stability policy**: holdfast-compose currently builds against Compose **beta** and material3 **alpha** — land on stable Compose or explicitly tier `-compose` below core stability at 1.0; same review for AGP/compileSdk (M)
- **Kotlin-train policy**: written cadence commitment (e.g., track latest stable Kotlin within one minor) so RC soaks can't be invalidated mid-window by a Kotlin release (S)
- API-shaped P2 triage: `derived()` raw `Pair`, store identity in error messages, middleware metadata typing — fix what touches frozen surface, explicitly waive the rest (M)
- Docs completeness: GUIDE §14 internal-version archaeology rewritten against published versions; pitfalls table quotes real error strings; file-layout maps regenerated (M)
- Issue burn-down from launch feedback; final `apiDump`; deprecation policy + SemVer policy docs (M)

### 1.0.0-rc → 1.0.0 — "The renumbering"

- RC ships with the final surface — **no deprecated symbols, no planned changes**. ≥4-week public soak with a call-for-breakage issue; banking-demo as the evaluation track.
- **Mechanical gate:** ABI dumps byte-identical between final RC and the 1.0 tag. If anything changed, cut another RC.
- 1.0 ships the semver contract, versioned doc site, and the stability-tier table (core/coroutines/testing: stable; compose/lifecycle: per the 0.7.0 decision; hallmark adapters: external train; wasmJs: experimental).

### 1.x horizon (demand-driven, post-freeze)

- `holdfast-hallmark*` GA once `vynatix/hallmark` reaches Central — likely relocated to the hallmark repo to end the coupled train permanently
- wasmJs parity (re-enable tests, `FileSystemKvStore`, `suspendDerived`) gated on demand evidence
- Static enforcement: lint/compiler plugin promoting the 0.4.0 runtime checks (CRTP, invoke-vs-action) to compile time
- DevTools: transaction-timeline inspector reusing the harness recorder — the one time-travel-adjacent feature that fits the model
- Community bridges (SQLDelight, DataStore, KStore) accepted as contributions against the stable Bridge/KvStore SPI
- JVM-target floor review (21 → 17) if real adopters ask — a deliberate post-1.0 decision

---

## Top risks

| Risk | Mitigation |
|---|---|
| Locking changes (`StoreLock` parking, drain placement) regress on Apple targets, which cannot be verified without a macOS host | 0.2.0 does not ship until `iosSimulatorArm64Test` is green in CI; the changes are confined to `StoreLock` and four drain call sites |
| Solo-maintainer bandwidth; 0.6.0 is the heaviest milestone | Each milestone is independently shippable; integration depth (0.6.0) can split into 0.6.x cuts without re-sequencing |
| Central namespace verification / first-publish latency outside maintainer control | Portal preflight runs in parallel with the 0.2.0 correctness work, so 0.3.0 is not gated on it |
| Compose Multiplatform stable timing slips, blocking the 0.7.0 dependency policy | The policy allows tiering `-compose` below core stability instead of waiting |
| Org billing lock persists | Local gate scripts already exist (this repo was fully verified without CI); releases use the scripted local path |

## Anti-goals (explicit cuts)

- No new platform targets and no wasmJs parity work before 1.0
- No compiler/IDE plugin in 0.x — runtime checks first, static enforcement post-1.0
- No DevTools/time-travel, speculative bridges, or middleware library before 1.0
- No hallmark feature work inside this repo; core never blocks on the sibling
- No toolchain-floor lowering to chase older consumers before 1.0
- No marketing before the 0.4.0 footgun pass — the launch is fired exactly once
