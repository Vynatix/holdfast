# Holdfast Usability Analysis — Second Pass

**Date:** 2026-07-04 · **Scope:** all six modules at `b404f62` (main) · **Method:** 5 parallel audit lenses (core API, coroutines+compose, testing+hallmark, docs/funnel, cross-store frame deep-dive; ~680k agent tokens), each verifying claims against source with file:line evidence, plus direct source verification of every blocker/major highlighted below. The one new blocker was found independently by two lenses and re-derived a third time from source. **First pass:** 2026-06-12 at `880af58` (7 lenses, 112 findings) — full text preserved at commit `5760bdc`; raw data in [`usability-findings.json`](usability-findings.json). This pass re-verifies every first-pass blocker/major and audits everything that landed since (PRs #12–#15: rename completion, `TransactionResult` ergonomics, context-overload removal, doc-snippets gate, docs-truth sweep, cross-store transaction frames).

---

## Verdict

**The remediation wave was real. The frame contract is the new frontier — and it leaks at the seams.**

Of the June P0 list, four of five items landed and verify clean against source: the rename is finished with deprecated aliases, `TransactionResult` grew `getOrThrow`/`valueOrNull`/`onSuccess`/`onError`, all five doc/code contradictions are fixed, the stale identifiers are gone, and a doc-snippets module now compile-gates (and for the root quick-start, *executes*) the samples. The `context(CoroutineScope)` overload hazard — June's worst API trap — is removed outright, with a breaking-change changelog entry and a regression test. That is an unusually complete response to a usability review.

What did **not** move: the install story. The README badge, install snippet, and changelog still claim Maven Central; nothing is published (`ROADMAP.md:3` says so itself); and `publish.yml` still invokes a vanniktech task no plugin provides, with secret names the build's actual publish convention never reads. A newcomer still bounces at minute two, exactly as in June.

The big new surface — the cross-store transaction frame contract (`atomic`/`suspendAtomic` + `FramePolicy`) — is the best-specified feature in the library: enforcement instead of advice, a numbered fanout contract that matches the code phase-for-phase, and teaching exceptions that name the cause, the consequence, and the exact fix. But its promises are enforced only where the thread-local frame marker can see. Where the marker can't see, behavior silently diverges from the documented all-or-nothing story: **blocking `atomic()` skips the serializer that makes `action` safe against in-flight suspending work (transaction-state corruption, the one new blocker), a nested `atomic(c)` is an unpoliced escape from `Strict` enrollment whose partial commit the docs actively deny, and read-your-own-writes quietly fails across dispatcher hops inside `suspendAtomic`.** A feature whose entire pitch is preventing silent partial commits currently has silent-partial-commit seams.

The June "silent-failure philosophy" cluster also persists — and reproduced itself in code written since: `derived()` recompute failures vanish into a `runCatching`, `restore()` commits type-mismatched snapshot values whose observer CCEs are swallowed, `KvBridge` silently discards unreadable persisted data and then overwrites it on the next commit, and `SuspendingBridge.publishAwaited` swallows every persistence error while its interface KDoc sells "part of the all-or-nothing guarantee."

---

## First-pass findings: status scorecard

Every June blocker/major was re-verified against `b404f62`. **14 fixed · 3 partial · 13 open.**

### Fixed (verified against source)

| June finding | Evidence |
|---|---|
| `TransactionResult` had no helpers | `getOrThrow()` rethrows the original exception, `valueOrNull`, chainable `onSuccess`/`onError` (`Transaction.kt:409-452`); KDoc warns against fire-and-forget |
| Rename unfinished (`vaultTest`, `bindVault`, `owningVault`) | `storeTest`/`bindStore`/`owningStore` primary; old names WARNING-level deprecated aliases (`StoreTest.kt:49-58`, `EventfulSupport.kt:95-101`, `MutableState.kt:31-38`) |
| `context(CoroutineScope)` overload scope capture | Removed from API dumps; changelogged as BREAKING; regression test `AsStateFlowScopeCaptureRegressionTest.kt` |
| `holdfastTest` ghost + stale class names + `validation*` coords + broken MIGRATING links | Repo-wide grep: survivals only in frozen archives and `MIGRATING.md`'s own removal notes; `MIGRATING.md` exists and all changelog links resolve |
| Five doc/code contradictions (core deps, distinct default, middleware order, nested-action errors, `transaction(on=)`) | All five re-verified against `Store.kt`/`Transaction.kt` — docs now match code |
| Toolchain floor undocumented | `README.md:131-134` states Kotlin 2.3.x / JVM 21, matching `libs.versions.toml` |
| Compose README shadowing bug; pitfalls table quoting nonexistent error strings | README fixed with `this.count` + comment; all four quoted strings now exist verbatim in code |
| `asEagerStateFlow` ghost | Documented as removed with migration recipe (`MIGRATING.md:26-32`) |
| `suspendAction` invisible to the testing timeline | Suspending path now fires the sync middleware chain (`SuspendAction.kt:111,126-146,157-166`), so recorders see it — **but two harness KDocs still assert the old contract** (`Recorder.kt:69-72`, `StoreHandle.kt:41-42`) |
| `shouldBeBoxedAs` dragging hallmark into every test classpath | Moved to `:holdfast-hallmark` (`BoxedMatchers.kt:20`); `holdfast-testing` has no hallmark dep |
| wasmJs support tier undocumented | Experimental-tier section verified accurate against the force-disabled test tasks and throwing actuals |
| No "Known issues" honesty | Root README section added; all three named hazards verified real in code |

### Partial

- **Observer exceptions swallowed** — still swallowed by default (`MutableState.kt:175-186`), but `uncaughtObserverHandler` is now a documented public member (`Store.kt:239-247`). Neither `effect` nor `observe` KDoc mentions it, and nothing logs even in debug.
- **`action{}`-under-`suspendAction` livelock** — fail-fast landed **for frames only** (`FrameInteropException`, `Store.kt:405-420`, excellent message). Plain `store.suspendAction { store.action {} }` still busy-spins forever (`AsyncSerializer.kt:23-27` unbounded `tryLock`/`threadYield` loop; `Store.kt:386` calls it with no `suspendingOwner` check). README Known issues + ROADMAP acknowledge it for 0.3.0.
- **Testing-module debt bundle** — rename done, hallmark dep gone; the rest open (below).

### Still open (June majors re-confirmed in current source)

1. **Install story (BLOCKER)** — badge, install snippet (`README.md:4,112-129`) and `holdfast/CHANGELOG.md:87-89` ("First public release on Maven Central") vs `ROADMAP.md:3` ("unpublished"). `publish.yml:65` invokes `publishAndReleaseToMavenCentral` (vanniktech; not applied anywhere) and exports vanniktech-style secrets (`mavenCentralUsername`, `signingInMemoryKey`) that the real convention (`holdfast.publish.sonatype.gradle.kts`, reads `ossrhUsername`/`signing.key`) never sees. A `v*` tag fails at task resolution; even the fixed task couldn't authenticate.
2. **`store { }` vs `store action { }`** — `invoke` still commits each `mutate` separately with fanout between (`Store.kt:505,653`); its KDoc carries no atomicity warning. (Now at least named in README Known issues.)
3. **CRTP unenforced** — `this as Self` (`Store.kt:324`); `class Foo : Store<Bar>()` still degrades to a runtime CCE folded into an ignorable `Error`.
4. **Standalone `update` non-atomic RMW** — worse than June: the read still happens before the synthesized action (`Store.kt:605-607`), and the KDoc now **actively asserts the wrong thing** ("an implicit single-shot transaction wraps the operation" — it wraps only the write).
5. **Commit-fanout exceptions → partial commit as ignorable `Error`** — a throwing `transformer.get`/`Bridge.publish` mid-fanout leaves earlier writes applied, reported as `TransactionException("Commit failed")` naming no store, state, or phase (`Transaction.kt:264-298`).
6. **`emit()` has no owner check** — stages onto whatever transaction is active from any thread (`EventfulStore.kt:89-96`), while `mutate` checks ownership and `stagePendingEvent`'s own KDoc says non-owner staging is undefined (`Transaction.kt:128-132`).
7. **`suspendAction` on a disposed store** — still no disposed check anywhere in `SuspendAction.kt`; a no-mutation body returns `Success`. Same gap now inherited by `suspendAtomic` — and blocking **`atomic()` and `derived()` also skip `checkNotDisposed`** (`Atomic.kt:139-177`, `Derived.kt:64-70`), violating the project's own every-entrypoint rule.
8. **Lazy state registration** — `snapshot()`/`properties` still miss never-read delegates; no eager option; warning lives only on `snapshot`, not `properties`.
9. **Harness `action` shadowing** — core's member still shadows the auto-tracking extension; `v.action {}` errors still bypass the pending-error guard. The trap is now thoroughly KDoc'd and even test-asserted (`AutoRegistrationTest.kt:235-236`) — documented, not fixed. The guard is off exactly when users write idiomatic code.
10. **Vacuous matchers** — `middleware<M>()` without a property access registers no predicate; all four runners early-return on empty predicates (`TimelineMatcherRunner.kt:18,31,59,84`), so `shouldNotFire { middleware<M>() }` can never fail.
11. **`api(kotest-assertions)`** — still exposed (`holdfast-testing/build.gradle.kts:28`), still zero usages in the module.
12. **No READMEs for `:holdfast-testing` / `:holdfast-hallmark`** — root README table links to bare directories; GUIDE §11 "Testing Patterns" teaches raw `kotlin.test` and never mentions `storeTest`.
13. **`Store.defaultScope` once-per-process CAS** — unchanged, still absent from Known issues and all user-facing prose, still no test-reset affordance.

---

## Fresh findings

Severity from this pass's verification. Items marked ✓✓ were confirmed by two independent lenses plus a direct source read.

### Theme 1 — Frame-contract seams (new blocker + the cluster that matters most)

The contract is enforced through a thread-local `FrameMarker` installed around the body. Every same-thread misuse earns a superb teaching exception. Every cross-thread or cross-primitive seam silently escapes:

- ✓✓ **BLOCKER. Blocking `atomic()` is not serialized against in-flight `suspendAction`/`suspendAtomic`.** `acquireAndRun` takes only `transactionLock` (`Atomic.kt:152`) and never touches `asyncSerializer` — the mutex that makes blocking `action` safe against suspending peers (`Store.kt:386-392`). A concurrent `atomic(a)` sees the suspendAction's transaction as a foreign-owner `priorActive`, installs a fresh root over it (`Atomic.kt:153-164`), and — because `suspendingOwner != null` relaxes `mutate`'s owner check (`Store.kt:627-629`) — the suspending body then **stages its writes into the frame's transaction**. Silent cross-transaction contamination; `atomic` is currently *less* safe than plain `action`. *Fix: bracket each store with `asyncSerializer.blockingAcquire()/Release()` in lock order, or fail fast when `suspendingOwner != null`.*
- **MAJOR. Nested frames are an unpoliced escape from `Strict` enrollment.** `atomic(a,b) { c.action{} }` throws, but `atomic(a,b) { atomic(c) { … } }` is legal (`verifyFrameNesting` checks flavor and lock order, never the enclosing policy — `Frame.kt:277-308`) and `c` commits at nested-frame exit; outer rollback does not undo it. *Fix: honor the enclosing policy (require `AllowUnenrolled`), or document this explicitly as the REQUIRES_NEW-style escape.*
- **MAJOR. The nesting docs are false for introduced stores.** `Atomic.kt:58-63` and `GUIDE.md:1819-1821` claim "enclosing rollback discards everything, including nested writes" — only true for *shared* stores (savepoints). `c.action { atomic(a,b,c) { … } }` followed by a throw in `c.action` reverts `c` but leaves `a`,`b` committed: a torn frame with no exception. *Fix: correct both docs; consider rejecting mixed nesting.*
- **MAJOR. `suspendAtomic` inside `suspendAction` on an overlapping store dies with a raw kotlinx mutex error** ("This mutex is already locked by the specified owner") or deadlocks — both resolve the mutex owner to the same `Job` (`SuspendAction.kt:74`, `SuspendAtomic.kt:116,198`). The reverse direction is handled beautifully as a savepoint. Relatedly, **nested `suspendAction` on the same store** hits the identical raw error instead of the savepoint semantics blocking `action` documents. *Fix: detect and either savepoint or throw `FrameInteropException` naming the hoist.*
- **MAJOR. Read-your-own-writes silently breaks across dispatcher hops in `suspendAtomic`.** `mutate` got the `suspendingOwner` relaxation; reads did not (`MutableState.kt:91` still requires the entry thread). After a resume on another thread, `balance update { … }` reads the *committed* value and overwrites earlier staged writes — a lost update with no error, on `Dispatchers.Default`, in the flagship transfer shape. GUIDE §15.3 states RYOW without the caveat. *Fix: mirror the relaxation in the `value` getter (sound for the same single-flight reason), or document loudly.*
- **MAJOR. Inside a Strict frame, `action {}` throws instead of returning `Error` — and only `atomic`'s KDoc says so.** `escalateInFrameError` (`Store.kt:428-438`) makes the result contract context-dependent: a helper written as `val r = store.action {…}; when(r) {…}` grows dead code the moment a caller wraps it in `atomic`. *Fix: document on `action` itself; consider a distinguishable wrapper exception.*
- **MAJOR. `atomic`'s result carries an arbitrary transaction** — `roots.last().txn` (highest `lockOrderKey`, `Atomic.kt:192`), documented nowhere public; on `Error` it doesn't point at the failing store. *Fix: KDoc now; a `FrameResult` with `frameId` + per-store transactions when the ABI opens.*
- **MINOR.** The behavior break (previously-committing unenrolled writes now throw; tolerated inner errors now abort) is changelogged under **Added**, and `MIGRATING.md` has zero frame content; GUIDE/KDoc refer to "pre-0.3 behavior" though no 0.3 exists. — **MINOR.** Frame messages name stores by `simpleName` only: two accounts of one class are indistinguishable in the exact canonical scenario (`accountA`/`accountB`); bare `mutate` misreports itself as "via action". — **POLISH.** `FrameObservers` is process-global yet `storeTest` doesn't auto-clear it; `FrameObserver` callbacks are asymmetric (only `onFrameStarted` gets participants); no frame test ever runs on wasmJs.

### Theme 2 — The silent-failure philosophy reproduced itself in new code

- **MAJOR. `derived()` recompute failures are swallowed; the derived state silently freezes.** Recompute runs as `postCommit { self action { … } }` (`Derived.kt:81-86`); the drain wraps tasks in `runCatching` (`Store.kt:319`) and discards the inner `TransactionResult`. One transient throw in `compute` and the derived value is permanently stale — no exception, no log. *(Direct-verified.)* *Fix: route to `uncaughtObserverHandler` or take an `onError` param.*
- **MAJOR. `restore()` commits type-mismatched values.** Cross-store restore by name is documented-legal; `stagePendingRaw` does a name lookup only, and erasure means `as? MutableState<Any>` always succeeds (`Snapshot.kt:67-77`). The wrong-typed value lands in state; observer CCEs are swallowed by default; first symptom is a CCE at an unrelated later read. *(Direct-verified.)* *Fix: capture `value::class` at snapshot time, verify on restore, fail the action with a named error.*
- **MAJOR. `KvBridge` destroys unreadable persisted data.** `runCatching { codec.decode(encoded) }.getOrNull()` (`KvBridge.kt:33`) silently hydrates nothing on corrupt/schema-changed data — and the next commit overwrites the (possibly recoverable) stored value. Meanwhile encode/put failures throw mid-commit-fanout into the partial-commit path. *Fix: `onDecodeError` hook; document the publish-throws path.*
- **MAJOR. `SuspendingBridge`'s durability claim is false.** The interface KDoc sells await-completion persistence as "part of the all-or-nothing guarantee" (`SuspendingBridge.kt:25-27`, "Throwing surfaces as a failed transaction commit" :45), but `Awaiting.publishAwaited` catches `Throwable` into a `replay=0` errors flow nobody collects by default (:186-192, 258-260) — the transaction returns `Success` regardless. Users get ordering, not durability. *Fix: rethrow (the interpose already handles it) or rewrite the claim.*
- **MAJOR. `SuspendingKvBridge` is not a `SuspendingBridge`.** Only its nested `.Awaiting` implements the interface the outer name promises; `suspendAction`'s commit interpose awaits only `is SuspendingBridge<*>` — so the naturally-named type silently gets fire-and-forget conflation. The `bridge()` vs `suspendingBridge()` factory split (one adjective apart, also breaking the module's own `suspend*`-functions / `Suspending*`-types convention) compounds it. *Fix: rename, or return `SuspendingBridge` from both factories with the choice as a parameter.*
- **MAJOR. `SuspendingKvBridge` leaks a drainer coroutine per instance, forever.** `init` launches an infinite drainer on `scope` = `Store.defaultScope` by default (`SuspendingBridge.kt:169-177,94,133`); no `close()`/`dispose()` exists and `state bridge null` disposes only the inbound side. Bridges outlive stores for the process lifetime. *Fix: implement `Disposable`.*
- **MAJOR. `suspendDerived` is a guaranteed crash on wasmJs** (its mandatory eager seed calls a `runBlocking` actual that throws unconditionally, with advice — "seed via suspendAction" — that no API variant can follow), and the `runBlocking` seed can deadlock a single-threaded dispatcher on JVM/iOS. *Fix: an overload taking explicit `initial: T`.* Also `suspendDerived`/`derived` never unregister their backing states on dispose.

### Theme 3 — Compose bindings undermine Compose muscle memory

- **MAJOR. `rememberDisposable` is keyed on the factory lambda itself** (`remember(make)`, `ComposeBindings.kt:57-63`). `Store` is compiler-unstable, so a capturing lambda is a fresh instance each recomposition → dispose/resubscribe churn every frame; the KDoc claims key-based behavior but the signature has no `key` param. *Fix: `rememberDisposable(vararg keys: Any?, make: …)`.*
- **MAJOR/MINOR. `collectAsState`'s own KDoc example doesn't compile** — it contains the exact delegate-shadowing bug the README already fixed (`ComposeBindings.kt:27-29`), plus pre-rename `CounterVault` naming. And the `Store` receiver is dead code — users must write `store.collectAsState(store.count)` for zero benefit; `State<T>.collectAsState()` matches Compose idiom. *(Both compose examples are excluded from the snippet gate, which is how this survived.)*
- **MINOR.** Post-dispose, composed UI silently freezes (observers dropped, no signal); pre-composed-but-disposed subscribe crashes from inside `produceState` with bare `"store disposed"`. Neither contract is documented on the Compose entry points.

### Theme 4 — Testing harness & hallmark

- **MAJOR. `awaiting` timeouts are `CancellationException`s** (`Awaiting.kt:145-147`) — inside any `launch` the timeout cancels the child and the test goes green; assertions after it never run. `eventually` throws `AssertionError` for the same situation; `awaiting` should match (and the two interact badly: an awaiting-timeout inside `eventually` aborts the retry loop). Also `parallel` (real threads) + `awaiting` (virtual-time timeout) is a documented-nowhere flake generator.
- **MAJOR. Hallmark's flagship `assign` API needs `-Xcontext-parameters` in *consumer* builds** — documented nowhere, and the module has no README at all. And the context parameter is bare `Store<*>`, so `assign` compiles outside `action {}` (silently commits its own one-shot) and inside *another store's* action (resolves to the wrong store, runtime foreign-state error) — the KDoc's "you must be inside an action" claim is unenforced in both directions. `BoxedHandle`/`BoxedState` KDocs also still contradict themselves about whether `assign` exists, using `UserVault` samples.
- **MINOR.** Timeline failure messages never print the timeline (every failure → add `println`, rerun) — `awaiting`'s message, which appends the last 5 events, is the in-repo prior art. `emitted(prop, value)` takes `Any?` and never matches on type mismatch (silently green under `shouldNotFire`); `handle.bridge(prop)` returns `BridgeView<*>` that the typed matchers can't consume without casts; the new frame matcher's KDoc example doesn't compile (`(a and b) shouldCommitTogether ()` — it's not infix) and group failure labels collide for same-class stores; `LatchedBridge` demands an unused `initial` and ships a public no-op `releasePublish()`; time control needs the undocumented `testScope.` prefix; `storeTest` doesn't clear `FrameObservers`.

### Theme 5 — Docs & funnel

- **MAJOR. The snippet gate never runs in CI.** `ci.yml` enumerates tasks per module; `:doc-snippets:test` isn't among them — "docs cannot rot" is enforced only on a local bare `./gradlew check`. *Fix: one line in the ubuntu job.*
- **MAJOR. Gate-excluded blocks have already drifted**, proving the exclusion list is where rot now concentrates: GUIDE §14.8's `suspendAtomic` listing shows the renamed `vararg vaults` parameter and omits `policy` entirely; the Appendix A cheatsheet has a `"$x.value done"` string-template bug. And a whole class the gate can't see: **KDoc examples**. Five don't compile — `Effect.kt:20` (`println(it)` in a receiver lambda), `SuspendingFileSystemKvStore.kt:25` (nonexistent `suspendBridge` + interface invoked as constructor, the module's flagship persistence recipe), `ComposeBindings.kt:27-29`, `StoreHandleGroup.kt:19`, plus stale-contract KDocs in `Recorder.kt`/`StoreHandle.kt`. *Fix: extend the gate to KDoc snippets, or add a signature-grep check for excluded listings.*
- **MAJOR. Changelog integrity.** `holdfast/CHANGELOG.md:87-89` claims a Maven Central release that never happened (and is undated); `holdfast-compose/CHANGELOG.md` has no `[Unreleased]`/0.1.0 at all — its top entry is internal "2.0.0 — 2026-05-03" with none of the archive framing core got, so a reader concludes 2.0.0 is current; three module changelogs reference dead `vault/CHANGELOG.md` paths; core `[Unreleased]` has two `### Added` sections.
- **MINOR.** GUIDE TOC omits §15 (the headline differentiator) and Appendix A; §2's file layout lists a deleted `UUID.kt` and omits `Frame/Atomic/Derived/Snapshot/Eventful/bridge/crypto/middleware`; internal version archaeology ("ships in 1.1", "Validation 0.3.0") persists mid-tutorial; prose still uses three names (holdfast/store/vault) for one concept in teaching sections. `MIGRATING.md`'s "After" snippets are ungated. ROADMAP still shows ~9 delivered 0.2.0 items as open and doesn't mention the shipped frame work.
- **POLISH (positive).** GUIDE §15 is the best-documented feature in the repo — every checkable claim matches source, the main examples are compile-gated, and the root quick-start twin *executes* and asserts its printed output. The funnel now genuinely works from step 3 onward.

### Theme 6 — API-surface hygiene (the ABI is frozen around avoidable warts)

- **MINOR.** `const val` leaks are now apiCheck-frozen: `FileSystemKvStore.HEX_DIGITS/HEX_RADIX/TMP_PREFIX` (both sync and suspending variants), `TimingMiddleware.KEY_START_MS` — implementation details in the public ABI. `StoreLock` (a busy-yield spinlock) is public and un-annotated, sitting in completion next to `Store`. `MutableState`'s constructor is public — hand-built instances pass the ownership check but are invisible to `snapshot`/`dispose`. `MutableState.bridge` setter bypasses `Store`'s disposed check. `TransactionResult.Success/Error` are data classes — public `copy()` forges results. `Publisher.publish(): Boolean` is never read by any caller.
- **MINOR.** `distinct = true` + a non-deterministic cipher (random-IV AES-GCM — exactly what `Cipher`'s KDoc recommends) silently never dedups: comparison happens on post-`set` raw values (`MutableState.kt:137`). One KDoc sentence would save the debugging session.
- **MINOR.** `TimingMiddleware` reports `Committed` from `onTransactionCompleted` — which fires *before* commit; a failed commit is recorded as committed, and timings exclude fanout (often the expensive part under the lock).
- **MINOR.** Core error messages still lack identity — `"store disposed"`, `"State must be created by this Store instance"` (one message, two distinct mistakes), `"Commit failed"` (no store/state/phase) — while the frame exceptions set the in-repo bar (cause + consequence + fix + escape hatch). The gap is now a style inconsistency inside one library.
- **POLISH.** `derived` returns a raw `Pair`; `middlewares()` noun-as-verb with no per-instance remove; `crypto.Cipher` collides with `javax.crypto.Cipher` on the platform its users implement it with; `@StoreInternalApi` members without the `internal*` prefix clutter completion; `CoroutineName("VaultProcessScope")` and `CounterVault/CartVault/UserVault` KDoc samples violate the repo's own new-docs-use-Store rule; `Eventful` KDoc still says "Issue 15 will add EventfulSupport" — it shipped; `TransactionResult` naming diverges from `kotlin.Result` muscle memory (`valueOrNull` vs `getOrNull()`, no `exceptionOrNull`/`fold`); no `action(name=)` overload, so `Transaction.id` for lambdas is a mangled class name or random UUID.

---

## What improved (worth protecting)

- **The frame contract's teaching exceptions are the best-in-class pattern in the library** — `UnenrolledStoreException` names the store, the consequence, the fix, why mid-frame enrollment is impossible, and the greppable opt-out. This is the quality bar the legacy messages should be raised to.
- **The doc-snippets gate with executing twins** (root quick-start compiles *and* runs with asserted output) is rarer than ABI validation. It just needs to run in CI and to cover KDoc.
- **The June docs-truth wave held up under adversarial re-checking** — all five contradictions, the identifiers, MIGRATING.md, toolchain floor, Known issues: verified, none regressed.
- The rename landed cleanly with time-boxed aliases; the context-overload removal came with a regression test and honest BREAKING changelog; `asFlow`/`asStateFlow` KDocs name the failure modes they replaced.

---

## Prioritized action plan

**P0 — correctness of the new contract + the unchanged adoption blocker (days):**
1. **Fix the install story** (unchanged since June): repair `publish.yml` (real task + matching secret names, or adopt vanniktech properly) and publish — or strip the Central badge/claims and say "pre-release, build from source" until it ships.
2. **Serialize blocking `atomic()`** against suspending work (acquire `asyncSerializer` per store in lock order), or fail fast on `suspendingOwner != null`.
3. **Close or honestly document the nested-frame `Strict` escape**, and fix the false "enclosing rollback discards everything" claim in `Atomic.kt` + GUIDE §15.4. Add the frame behavior-break to `MIGRATING.md` and re-file it under a behavior-change heading in the changelogs.
4. **`FrameInteropException` for `suspendAtomic`-inside-`suspendAction`** (and savepoint-or-teach for nested `suspendAction`); extend the RYOW relaxation to reads under `suspendingOwner` or document the hop hazard in §15.3.
5. **Wire `:doc-snippets:test` into CI** and fix the two drifted excluded blocks (§14.8 signature, cheatsheet template bug).

**P1 — the silent-failure pass (a week-ish):**
6. `derived` recompute errors → `uncaughtObserverHandler` (or `onError` param); type-check `restore`; `KvBridge` `onDecodeError`; make `publishAwaited` throw or rewrite the durability KDoc; give `SuspendingKvBridge` disposal and a truthful name.
7. Disposed checks on `atomic`, `suspendAction`, `suspendAtomic`, `derived` (the rule already exists; these four predate or missed it).
8. Fix standalone `update` (read under the synthesized action) — its KDoc currently asserts the wrong contract, making this the cheapest high-value fix in the list. Add the owner check to `emit()`.
9. Fail fast on plain `action`-under-`suspendAction` via `suspendingOwner` (converts the last known livelock into a teaching error — already roadmapped).
10. Compose: `State<T>.collectAsState()` (deprecate the receiver form), `rememberDisposable(vararg keys)`, document the dispose contract.
11. Testing: `awaiting` timeout → `AssertionError`; empty-predicate matchers throw; drop `api(kotest)`; a non-colliding tracked verb for handles; append timeline tails to failure messages; README + GUIDE §11 rewrite around `storeTest`.
12. Hallmark: README with the `-Xcontext-parameters` requirement; gate `assign` on a transaction-scope type rather than bare `Store<*>`; fix the self-contradicting KDocs.

**P2 — papercuts (ongoing):** identity in core error messages; `suspendDerived(initial=)` overload + wasm honesty; `exceptionOrNull`/`getOrNull` alignment and `action(name=)`; const-val ABI leaks, `StoreLock`/`MutableState` exposure; `TimingMiddleware` truthfulness; changelog framing for compose/coroutines/hallmark archives; GUIDE TOC/§2/version-archaeology; `FrameObserver` symmetry and `storeTest` auto-clearing `FrameObservers`; the KDoc-example compile sweep.

---

## Appendix

- **Machine-readable findings for this pass:** [`usability-findings-2026-07-04.json`](usability-findings-2026-07-04.json) (status scorecard + fresh findings with file:line evidence).
- **First pass:** full report at commit `5760bdc` (`git show 5760bdc:USABILITY-ANALYSIS.md`); raw 112-finding data in [`usability-findings.json`](usability-findings.json).
- **Verification discipline:** every fresh blocker/major above carries file:line evidence checked against `b404f62` source by its reporting lens; the blocker and both restore/derived silent-failure majors were additionally re-derived independently (the blocker three times: two lenses plus a direct read of `Atomic.kt`/`Store.kt`/`SuspendAction.kt`). First-pass statuses (FIXED/PARTIAL/OPEN) were each re-verified against current source, not inferred from changelogs.
