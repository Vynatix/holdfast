# Holdfast Usability Analysis

**Date:** 2026-06-12 · **Scope:** all six modules at `da3856f..880af58` · **Method:** 7 independent analysis lenses (29 agents, ~1.9M tokens, 737 tool invocations), including a *real* first-use test — a fresh JVM consumer project built against the `0.1.0` artifacts with README samples pasted verbatim — plus empirical probes (concurrent-increment loss measurement, rollback verification, overload-resolution bytecode inspection). Every blocker/major finding from the first three lenses went through adversarial verification (a skeptic agent instructed to refute it against the code): **18 confirmed, 4 partially confirmed, 0 refuted.** Two further findings were independently proven earlier the same day (bytecode-level proof of the `asStateFlow` overload capture; sampled stack traces of the iOS livelock). Raw data: 112 findings (8 blocker, 53 major, 35 minor, 16 polish).

---

## Verdict

**The engine is real; the road to it is broken.** The core transactional promise was verified empirically — a throwing action rolled back atomically, observers never saw the staged write — and nothing in the Kotlin ecosystem (StateFlow, Orbit, MVIKotlin, Redux-Kotlin) offers multi-property atomic commit with rollback, savepoints, and deadlock-safe cross-store transactions. The positioning docs are unusually honest ("if your state is one value, use StateFlow and skip this library"), and parts of the engineering hygiene (ABI dumps, the testing harness's pending-error guard, the concurrency-contract KDoc) exceed what mature libraries ship.

But a new adopter today fails at every stage of the funnel:

1. **They cannot install it.** The Maven Central badge, install snippet, and changelog all claim publication; `com.vynatix` has zero artifacts on Central (live-checked, 404).
2. **The quick-start does not compile.** Pasting the README sample verbatim produced 18 compile errors (no imports shown; the load-bearing top-level `effect` import is unguessable; an undefined `handle()` placeholder).
3. **Their first error disappears.** The deliberately-failing transaction in the README sample produces *zero output* — errors are values (`TransactionResult.Error`) with no unwrap/`getOrThrow` affordance anywhere, trivially discarded in the idiomatic fire-and-forget style.
4. **The docs lie to them in load-bearing places.** Three brand generations (Vault → Holdfast → Store) coexist: the advertised testing entry point `holdfastTest { }` does not exist (real name: `vaultTest`), four docs use class names that no longer compile, and five documented behavioral claims are contradicted by the code.

All of this is fixable, most of it cheaply, and 0.x is the only cheap time for the API-surface items.

---

## What the library gets right

These are differentiators worth protecting (verified by multiple independent lenses):

- **Verified atomic rollback** — the headline claim is true at runtime; no ecosystem equivalent.
- **Cross-store `atomic(v1, v2) {}`** with deadlock-safe global lock ordering — competitors simply don't have this.
- **Errors that teach**: `emit()` outside an action explains *why* events must be transactional; `removeState` with pending writes says "commit or rollback first"; foreign-store state is rejected O(1) instead of corrupting silently.
- **The testing harness core is best-in-class**: the unconsumed-error teardown guard, ordered leak cleanup (barriers → awaitings → open transactions → recorders), race-free `awaiting{}` replay, virtual-time integration, and a complete bridge-fake kit exceed Turbine/Orbit norms.
- **Honest framing**: 0.x pin-exact-versions warnings, `XorCipher` marked not-production three times, a real "you don't need this if…" section, behavior-change KDoc that names the old failure mode it replaced.
- **GUIDE.md's pedagogy** (decision charts, visibility table, lifecycle diagrams, written concurrency model) is the right shape — the content has just rotted against the API.
- **`suspendAction`'s concurrency contract** worked exactly as documented on first try, including staged-value isolation from a concurrently collecting flow.

---

## Findings by theme

Severity is post-verification. ✅ = adversarially confirmed · ◐ = partially confirmed (severity adjusted) · ⚠ = not separately verified (from capped lenses) · 🔬 = proven independently this session.

### Theme 1 — Distribution: the library cannot be adopted (1 blocker)

- ✅ **BLOCKER. Not installable.** README badge + install block + changelog claim Maven Central; the whole `com.vynatix` group 404s. `holdfast-hallmark` additionally needs the sibling `hallmark` repo built to mavenLocal — documented nowhere user-facing. *Fix: publish (publish.yml needs repair first — it invokes a plugin that isn't applied), or rewrite the install section honestly.*
- ✅ **MAJOR. Undocumented bleeding-edge toolchain floor**: Kotlin 2.3.x metadata and JVM target 21 — consumers on older toolchains get cryptic metadata errors, and no doc states the requirement.

### Theme 2 — The silent-failure philosophy (1 blocker-class cluster, mostly confirmed)

The library's runtime guards are unusually strong — and almost all of them can be silently discarded because failures are ignorable values:

- ◐ **MAJOR. `action` never throws and `TransactionResult` has zero ergonomic helpers** — no `getOrThrow()`, `onError {}`, `valueOrNull`. Every guard in the library (foreign-state check, transformer validation, restore failures, disposed-store errors under `suspendAction`) funnels into a result that idiomatic fire-and-forget code drops on the floor. The README's own sample demonstrates the failure: its deliberately failing transaction prints nothing.
- ✅ **MAJOR. `store { }` vs `store action { }`** — one missing token compiles silently and commits each write separately with observers firing between writes: the exact non-atomicity the library exists to prevent. Empirically proven (mid-block throw left earlier writes committed).
- ✅ **MAJOR. The CRTP self-type is not enforced.** `class Foo : Store<Bar>()` compiles cleanly and degrades to a runtime `ClassCastException` — swallowed into the ignorable result. The README's central type-safety claim is false as stated.
- ✅ **MAJOR. Standalone `update` is a non-atomic read-modify-write** — measured: 5,049 of 10,000 concurrent increments survived. `StateFlow.update`, the audience's muscle memory, is an atomic CAS loop.
- ✅ **MAJOR. Observer exceptions during commit fanout are swallowed by default**; the `uncaughtObserverHandler` escape hatch is nearly undiscoverable.
- ✅ **MAJOR. Commit-fanout exceptions produce partial commits** (`transformer.get`, `Bridge.publish` throwing mid-fanout) reported as `Error` — against the all-or-nothing promise.
- ✅ **MAJOR. `suspendAction` has no disposed check** — on a disposed store it returns `Success` for a no-mutation body instead of throwing like blocking `action` does.
- ✅ **MAJOR. `emit()` checks for an active transaction but not ownership** — a racy off-action emit silently stages onto another thread's transaction.
- ✅ **MAJOR. `distinct = false` default inverts the StateFlow dedup contract** users carry in (same-value commits re-fire observers) — and the GUIDE documents dedup as the default in two places.

### Theme 3 — Brand archaeology: the unfinished triple rename (Vault → Holdfast → Store)

- ✅/◐ **BLOCKER→MAJOR. The advertised testing entry point `holdfastTest { }` does not exist** — the real function is `vaultTest`, named for an abandoned brand, invisible to anyone IDE-searching "store" or "holdfast". Found independently by three lenses.
- ✅ **MAJOR. Vault names persist in *permanent public API***: `bindVault`, `owningVault` (in the ABI dumps), KDoc samples (`CounterVault`, `MyVault`). Pre-1.0 is the only cheap time to fix.
- ✅ **MAJOR. Four docs use classes that don't exist** (`CounterHoldfast`, `AccountHoldfast`, `UserHoldfast`) — samples are not copy-pasteable.
- ⚠ **MAJOR.** GUIDE §14.11 and the hallmark-coroutines README still use pre-rename coordinates (`com.vynatix:validation*`), and GUIDE §14 organizes features under internal version numbers (1.1/2.0/0.3.0) no published artifact ever had.

### Theme 4 — Doc/code contradictions (docs-accuracy lens; spot-verified)

Five documented claims are the opposite of the code:

1. ✅ "No coroutines dependency in core" (three places) — core has `api(kotlinx-coroutines-core)` and exposes `SharedFlow`/`CoroutineScope` in public types.
2. ⚠ Same-value dedup documented as default; it's opt-in `distinct = true`.
3. ⚠ GUIDE §4.6 middleware ordering is inverted vs. source (and vs. GUIDE §13/§14.6 — the GUIDE contradicts itself).
4. ✅ **GUIDE 9.8's nested-action error-propagation semantics are the opposite of the implementation** — contradicted by the library's own test.
5. ⚠ `transaction(on = store)` presented as a core primitive; it's a test-only utility in holdfast-testing.

Plus: ⚠ `asEagerStateFlow` documented but removed; ⚠ eight broken `MIGRATING.md` links across changelogs; ⚠ the holdfast-compose README example doesn't compile (delegate shadowing — `count` local shadows the state property inside the action); ⚠ the pitfalls table quotes error strings that exist nowhere in the code; ⚠ `holdfast-testing` and `holdfast-hallmark` have no README at all.

### Theme 5 — Concurrency model: precise KDoc, absent guide, two traps (🔬 session-proven)

- 🔬 **BLOCKER. The `context(CoroutineScope)` overloads silently hijack the ambient scope.** Inside *any* coroutine body (`runBlocking`, `launch`, `LaunchedEffect`), K2 resolves zero-scope-arg `asStateFlow(...)` (and the bridge factories) to the context overload — capturing the caller's scope instead of `store.scope`, producing silently-dead or wrongly-scoped StateFlows. Proven at bytecode level this session (it hung the library's own test suite forever); the KDoc's resolution claim is false, and a comment in `AsStateFlowContextParamTest` shows the project already knew the hazard.
- 🔬 **MAJOR. Blocking `action {}` inside or concurrent with `suspendAction` degenerates to an infinite busy-spin** — observed live as the `SuspendDerivedTest` livelock on iOS (~200% CPU, sampled stacks; root cause in the `suspendingOwner` handshake still open; mitigated by the new 10-minute test-task cap). On wasmJs this is a guaranteed browser freeze.
- ⚠ **MAJOR. No user-facing threading/scoping documentation**: `bindToScope`, `defaultScope`, `Store.scope`, `dispose()` — zero occurrences in the 1,700-line GUIDE (grep-verified by the lens). The 4-level scope chain is documented only at its KDoc definition site.
- ⚠ **MAJOR. Observers run synchronously under the transaction lock** (contenders busy-spin) — undocumented at `effect`, and GUIDE.md:868 actively claims the opposite.
- ⚠ **MAJOR. `Store.defaultScope` is settable once per process** and breaks consumers' test suites; the library's own tests tiptoe around it, and holdfast-testing offers no reset.

### Theme 6 — Testing harness: brilliant core, broken funnel

- ⚠ **MAJOR. Core's member `Store.action` shadows the scope's auto-tracking `action {}`** — the natural `v.action {}` call records nothing *and its errors bypass the pending-error guard*; the harness's own tests work around it with commented warm-up calls.
- ⚠ **MAJOR. `suspendAction` produces zero timeline events** (v1 contract buried in an internal KDoc) — coroutine-first users get empty timelines with no hint why.
- ⚠ **MAJOR. Never-recordable matcher categories are always-green**: `shouldNotFire { middleware<M>() }` passes vacuously; `middleware<M>()` without a property access registers no predicate at all.
- ⚠ **MAJOR. Dependency weight**: `api(kotest-assertions)` exposed but unused by the harness's own matchers; hallmark pulled into every consumer's test classpath for one matcher file.

---

## Prioritized action plan

**P0 — before any adoption push (days):**
1. Fix the install story: publish to Central (repair publish.yml first — it calls a vanniktech task with no vanniktech plugin) **or** rewrite install docs/badge honestly.
2. Finish the rename in public API while 0.x allows it: `vaultTest`→`storeTest`, `bindVault`→`bindStore`, `owningVault`→`owningStore` (+ deprecated aliases for one release), then `apiDump`.
3. Add `TransactionResult.getOrThrow()` / `onError {}` / `valueOrNull`, and make the README sample demonstrate error *surfacing*, not error *swallowing*.
4. Mechanical doc sweep: stale class names, `holdfastTest`, `asEagerStateFlow`, `validation*` coordinates, broken MIGRATING.md links, imports in every quick-start sample (compile-check samples in CI — a snippet-test module would prevent regression).
5. Correct the five doc/code contradictions (one-line fixes each).

**P1 — the footgun pass (a week-ish, some API decisions):**
6. Decide the `context(CoroutineScope)` overload question: remove/rename them, or fix the KDoc and add loud warnings. (Removal is the safe call — the implicit-receiver capture cannot be documented away.)
7. `store { }` vs `action { }`: at minimum a GUIDE pitfall + lint rule; consider making `invoke` non-mutating (fail mutate outside action when invoked via `invoke`).
8. Make standalone `update` atomic (wrap the read in its synthesized action) — it currently loses ~50% of concurrent increments.
9. Eager state registration via `provideDelegate` (kills the snapshot/restore-on-untouched-store class of surprises).
10. Fail fast on `action {}` inside `suspendAction` (detect via `suspendingOwner`) — converts the livelock into a thrown error; then chase the `SuspendDerivedTest` race root cause.
11. Write the two missing GUIDE chapters: *Threading & Scopes* (the 4-level chain, observers-under-lock, dispose asymmetry, defaultScope-in-tests) and *Lifecycle & Events* (who calls `dispose()`, ViewModel/DI integration, EventfulStore).
12. Testing module: README + quickstart, fix the `action` shadowing (remove the dead extension, add a non-colliding verb), synthesize timeline events for `suspendAction`, make never-recordable matchers throw, drop the kotest `api` dep.

**P2 — papercuts (ongoing):** the 51 minors/polish items — error messages lacking store identity, generic `IllegalStateException` vocabulary, `derived()` returning a raw `Pair`, stringly-typed middleware metadata, Compose `collectAsState` double-mention ceremony, GUIDE staleness in file-layout/type maps, and the rest (full list in the appendix data).

---

## Appendix

- **Full machine-readable findings** (112 findings with evidence + recommendations, 22 verification verdicts): [`usability-findings.json`](usability-findings.json).
- **First-use scratch project** (the empirical newcomer journey, friction log reproducible): `/tmp/holdfast-uxtest`.
- **Verification discipline**: each blocker/major from the first-use, API-ergonomics, and error-experience lenses was re-derived from source by an independent skeptic agent; 0 of 22 were refuted, 4 had severity adjusted down. Findings from the remaining lenses (marked ⚠) follow the same evidence format but were not separately re-verified; the two 🔬 items were proven outside the workflow with bytecode inspection and thread-dump sampling.
