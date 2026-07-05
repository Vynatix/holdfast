# Holdfast Product Roadmap

**Date:** 2026-06-12 · **Current version:** 0.1.0 (unpublished) · **Owner:** solo maintainer
**Inputs:** the 2026-06-12 usability analysis (112 verified findings — see `USABILITY-ANALYSIS.md`), three competing strategy drafts (adoption-first, correctness-first, ecosystem-first) stress-tested by an adversarial release-engineering review, plus repo-verified constraints.

---

## Strategic thesis

Holdfast's differentiator is real and verified — atomic multi-property commit with rollback, savepoints, and deadlock-safe cross-store transactions have no Kotlin-ecosystem equivalent — but today the library is uninstallable, its quick-start doesn't compile, and its docs contradict its code. The sequencing below is **adoption-shaped with correctness gates**: fix the funnel first (a developer gives a 0.x library fifteen minutes; a broken install reads as abandonment), kill the first-session footguns before any marketing (a launch is a one-shot consumable), go deep on the integrations that decide adoption (Compose, ViewModel, testing), and freeze only what is mechanically proven. 0.x is the only cheap window for breaking changes — every rename and removal is front-loaded while the user count is ~zero, making 1.0 a renumbering, not a migration.

## Release-train principles (the rules each milestone obeys)

1. **One big break.** All cheap API breaks ship in 0.2.0 (rename completion, overload removal, result ergonomics) with `@Deprecated(ReplaceWith)` aliases for exactly one minor; aliases are removed **before** the RC is cut, so the RC and 1.0 are byte-identical.
2. **Docs cannot rot.** The snippet-test gate (every README/GUIDE code block compiled — and executed where output is claimed) lands before any doc rewriting, and must pass on a **fresh clone** (which forces the hallmark build-decoupling below).
3. **Two-phase livelock handling.** Fail-fast guard immediately (0.3.0); root cause is deferred but **hard-gated before the RC** with a written ADR either way — fixed, or fail-fast becomes the permanent documented contract. No open-ended concurrency archaeology on the critical path, and no core redesign inside the freeze milestone.
4. **Hallmark is decoupled — build and publish.** `:holdfast-hallmark*` modules become property-gated in `settings.gradle.kts` (excluded by default; included with `-Pholdfast.includeHallmark`), so a fresh clone builds standalone. They ship only after `vynatix/hallmark` itself reaches Central, possibly relocated to that repo. Core 1.0 never blocks on the sibling.
5. **Launch is one-shot.** No announcement, awesome-kotlin submission, or comparison content until the 0.3.0 footgun pass is complete. Known issues are documented by name with workarounds — honesty is the brand repair for the badge that lied.
6. **New surface needs soak.** Anything introduced after 0.4.0 (lifecycle/savedstate modules) is labeled experimental at 1.0 unless it has had two minors of soak.

---

## Milestones

### 0.2.0 — "Installable and honest" *(realistic: 2–3 weeks + Central-verification latency)*

**Theme:** make every sentence in the funnel true; spend the breaking-change budget in one cut.

| Deliverable | Size |
|---|---|
| **Release engineering rip-and-replace.** The homegrown `holdfast.publish.sonatype` convention points `maven-publish` at the Central Portal upload API, which it cannot speak — replace both publish conventions with the vanniktech plugin (which publish.yml already assumes) end-to-end: namespace verification preflight on the Portal, signing, a scripted **local** release path so releases never depend on CI availability. | M |
| **Unblock CI: settle the org billing lock.** The repo is already public — Actions (incl. macOS runners) is free for public repos; the lock is org-level. One support/billing action replaces any "migrate CI" project. Fallback if it drags: documented local gate script. | S |
| **Decouple hallmark from build and publish** (property-gated module inclusion; remove from README module table or mark "unreleased, requires Hallmark"). | S |
| **Finish the rename in public API**: `vaultTest`→`storeTest`, `bindVault`→`bindStore`, `owningVault`→`owningStore` (7 files in holdfast-testing + core), deprecated aliases for one minor, `apiDump`. | S–M |
| **Remove the `context(CoroutineScope)` overloads** on `asStateFlow` + bridge factories (bytecode-proven ambient-scope capture; not documentable away). | S |
| **`TransactionResult` ergonomics**: `getOrThrow()`, `onError {}`, `valueOrNull`; README quick-start rewritten to *surface* its deliberately-failing transaction. | S |
| **Snippet-test module**: every fenced Kotlin block in README/GUIDE compiles (and asserts claimed output) in `./gradlew check`, on a fresh clone. | M |
| **Docs-truth sweep**: the five doc/code contradictions, stale `*Holdfast`/`*Vault` sample classes, `holdfastTest` references, `asEagerStateFlow` ghost, `validation*` coordinates, MIGRATING.md links (stub or delete — file doesn't exist), compose-README shadowing bug, toolchain floor (Kotlin 2.3.x / JVM 21) documented in Install. | M |
| **Known-issues section** naming *all three* open hazards honestly: the action-in-suspendAction livelock (+ workaround), non-atomic standalone `update`, and the `store{}`-vs-`action{}` trap — both are fixed next release; saying so beats shipping silently. | S |
| **wasmJs → explicit experimental tier** (tests disabled, `FileSystemKvStore` throws, `suspendDerived` unusable — say so; keep the artifact: the build comment indicates an external consumer). | S |
| **Publish 0.2.0 to Maven Central**: `holdfast`, `-coroutines`, `-compose`, `-testing`. | M |

**Success criteria:** all four artifacts return HTTP 200 on repo1.maven.org; a scripted fresh-clone consumer project with the README install block + quick-start pasted verbatim compiles and prints the documented output including the surfaced error; `grep -ri vault */api/*.api` returns only deprecated aliases; link checker reports zero broken internal links; `./gradlew check` passes on a fresh clone with no sibling-repo ritual.

### 0.3.0 — "The first session cannot lie" *(2–4 weeks)*

**Theme:** kill every silent-failure footgun a newcomer hits in hour one; turn the analysis's empirical probes into permanent regression tests.

- Fail fast on blocking `action {}` inside/concurrent with `suspendAction` — **largely delivered** (F1/F4): the same-coroutine self-spin (a blocking `action`/`atomic` enrolling the store inside a `suspendAction` body) now throws `FrameInteropException` immediately, and a cross-thread blocking `action` overlapping an in-flight `suspendAction` is serialized by the shared `AsyncSerializer` rather than cross-contaminating. Residual (per the two-phase plan): single-threaded-dispatcher starvation stays a documented caveat pending the pre-RC ADR (M)
- Make standalone `update` atomic — **delivered** (P1-update-rmw): the standalone read-modify-write wraps in an implicit action, regression test asserts 10,000/10,000 concurrent increments survive (M)
- Enforce the CRTP self-type at construction — **delivered** (P1-crtp): `class Foo : Store<Bar>()` throws at init with a two-type teaching message (JVM/Android; iOS/wasmJs no-op) (S)
- Defuse `store { }` vs `store action { }` — **delivered** (P1-invoke-nonatomic): a bare `store { }` is non-mutating; `mutate`/`update` directly inside it throws a teaching `IllegalStateException` (M)
- Flip `distinct` default to `true` (matches the StateFlow dedup contract users carry in — and what the GUIDE already claims) (S)
- Eager state registration via `provideDelegate` — **delivered** (P1-lazy-registration): states register at construction, so snapshot/properties see every declared state; a throwing/forward-referencing initializer now fails at construction (MIGRATING recipe) (M)
- `suspendAction` disposed-store check matches blocking `action`; `emit()` gains the ownership check `mutate` has — **delivered** (P1-disposed-gaps, P1-emit-owner) (S)
- Observer-exception default: log loudly instead of silent swallow; document `uncaughtObserverHandler` where users will find it — **delivered** (P1-observer-swallow): null handler routes to a loud built-in logger; assign a no-op lambda to silence (S)
- Remove the 0.2.0 deprecated aliases (the one-minor promise) (S)

**Success criteria:** every footgun from USABILITY-ANALYSIS Theme 2 has a named regression test; the empirical probes (increment loss, rollback verification, overload resolution) run in CI; zero silent-failure paths for the misuse cases in the error-experience lens.

### 0.4.0 — "The harness earns its claim" *(2–3 weeks)*

**Theme:** the testing harness is a genuine differentiator with a broken funnel — fix the funnel; write the two missing GUIDE chapters.

- holdfast-testing README + 30-second quickstart under the new `storeTest` name (S)
- Fix the `action`-shadowing trap (remove the dead extension; add a non-colliding verb that always routes through the handle) (M)
- Synthesize timeline events for `suspendAction` so coroutine-first users stop getting inexplicably empty timelines (L)
- Never-recordable matcher categories throw instead of passing vacuously (S)
- Dependency diet: drop unused `api(kotest-assertions)`; make the hallmark matcher optional (S)
- GUIDE chapters: **Threading & Scopes** (4-level chain, observers-under-lock, dispose asymmetry, `defaultScope`-in-tests + a test-reset story) and **Lifecycle & Events** (who calls `dispose()`, EventfulStore patterns) (M)
- Commit-fanout hardening: `transformer.get`/`Bridge.publish` exceptions must not produce partial commits (L)

**Success criteria:** a newcomer can find, install, and write their first harness test from the module README alone; harness traps from Theme 6 have regression tests; threading model documented where users look.

### 0.5.0 — "Compose-native + the migration story" *(4–6 weeks; the launch happens after this ships)*

**Theme:** integration depth on the three surfaces that decide adoption; then — and only then — the public launch.

- holdfast-compose depth: single-mention observation (extension on `State<T>`), `rememberStore`, event-collection helper — StateFlow-ergonomics parity for the common cases (M)
- `holdfast-lifecycle` module (experimental tier): ViewModel ownership pattern, `dispose()` wired to `onCleared`, factory helpers (M)
- DI recipes as *documentation* (Koin + Hilt patterns, compiled in the snippet module — not new artifacts) (S)
- Process-death story: SavedStateHandle recipe built on `snapshot()`/`restore()` (recipe first; promote to a module only on demand) (M)
- **Migration guide**: mechanical StateFlow→Holdfast and MVI→Holdfast mapping tables, validated by porting one banking-demo feature side-by-side (M)
- banking-demo: Compose Multiplatform UI + ViewModel + DI wiring as the flagship evaluation track (L)
- **Launch**: announcement anchored on verified differentiators, awesome-kotlin, comparison content; governance pack ships *first* (SECURITY.md, issue templates, triage expectations) so launch-generated load lands on rails (S)

**Success criteria:** the Compose example in docs is the *recommended* pattern, not a hand-rolled bridge; a StateFlow ViewModel ports to Holdfast using only the migration guide; governance files exist before the first wave of issues.

### 0.6.0 — "Close the books" *(3–5 weeks)*

**Theme:** every open question gets a decision; everything breaking ends here.

- **HARD GATE — livelock ADR**: the `suspendingOwner` race is root-caused and fixed, *or* a written ADR makes the 0.3.0 fail-fast the permanent contract. Cross-target concurrency soak suite (iOS included) replaces the 10-minute timeout mitigation (L)
- **Dependency-stability policy**: holdfast-compose currently builds against Compose **beta** and material3 **alpha** — land on stable Compose or explicitly tier `-compose` below core stability at 1.0; same review for AGP/compileSdk (M)
- **Kotlin-train policy**: written cadence commitment (e.g., track latest stable Kotlin within one minor) so RC soaks can't be invalidated mid-window by a Kotlin release (S)
- API-shaped P2 triage: `derived()` raw `Pair`, store identity in error messages, middleware metadata typing, and a **`FrameResult` with per-store transactions** (F7 — today `atomic`/`suspendAtomic` return only the last participant root in lock order; per-store outcomes must be correlated via `Transaction.frameId`) — fix what touches frozen surface, explicitly waive the rest (M)
- Docs completeness: GUIDE §14 internal-version archaeology rewritten against published versions; pitfalls table quotes real error strings; file-layout maps regenerated (M)
- Issue burn-down from launch feedback; final `apiDump`; deprecation policy + SemVer policy docs (M)

### 1.0.0-rc → 1.0.0 — "The renumbering"

- RC ships with the final surface — **no deprecated symbols, no planned changes**. ≥4-week public soak with a call-for-breakage issue; banking-demo as the evaluation track.
- **Mechanical gate:** ABI dumps byte-identical between final RC and the 1.0 tag. If anything changed, cut another RC.
- 1.0 ships the semver contract, versioned doc site, and the stability-tier table (core/coroutines/testing: stable; compose/lifecycle: per the 0.6.0 decision; hallmark adapters: external train; wasmJs: experimental).

### 1.x horizon (demand-driven, post-freeze)

- `holdfast-hallmark*` GA once `vynatix/hallmark` reaches Central — likely relocated to the hallmark repo to end the coupled train permanently
- wasmJs parity (re-enable tests, `FileSystemKvStore`, `suspendDerived`) gated on demand evidence
- Static enforcement: lint/compiler plugin promoting the 0.3.0 runtime checks (CRTP, invoke-vs-action) to compile time
- DevTools: transaction-timeline inspector reusing the harness recorder — the one time-travel-adjacent feature that fits the model
- Community bridges (SQLDelight, DataStore, KStore) accepted as contributions against the stable Bridge/KvStore SPI
- JVM-target floor review (21 → 17) if real adopters ask — a deliberate post-1.0 decision

---

## Top risks

| Risk | Mitigation |
|---|---|
| Livelock root cause sits deeper than the handshake (K/N memory model, kotlinx Mutex) | Two-phase plan: users are protected by the 0.3.0 fail-fast either way; the 0.6.0 gate is an ADR, not an open-ended fix |
| Solo-maintainer bandwidth; 0.5.0 is the heaviest milestone | Each milestone is independently shippable; integration depth (0.5.0) can split into 0.5.x cuts without re-sequencing |
| Central namespace verification / first-publish latency outside maintainer control | Portal preflight is the *first* 0.2.0 task, run in parallel with everything else |
| Compose Multiplatform stable timing slips, blocking the 0.6.0 dependency policy | The policy allows tiering `-compose` below core stability instead of waiting |
| Org billing lock persists | Local gate scripts already exist (this repo was fully verified without CI); releases use the scripted local path |

## Anti-goals (explicit cuts)

- No new platform targets and no wasmJs parity work before 1.0
- No compiler/IDE plugin in 0.x — runtime checks first, static enforcement post-1.0
- No DevTools/time-travel, speculative bridges, or middleware library before 1.0
- No hallmark feature work inside this repo; core never blocks on the sibling
- No toolchain-floor lowering to chase older consumers before 1.0
- No marketing before the 0.3.0 footgun pass — the launch is fired exactly once
