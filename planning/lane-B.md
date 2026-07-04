I have a complete picture now. Here is the implementation plan for workstream B.

---

# Lane B Implementation Plan — Core Silent Failures & Contract Gaps

All paths relative to `/home/user/holdfast`. Line references are current `main`. ROADMAP.md 0.3.0 already commits maintainer intent for several items (update-atomicity, CRTP-at-construction, invoke-fail-loud, eager registration via `provideDelegate`, disposed/emit checks, observer-exception "log loudly") — I treat those as decided defaults and flag only genuinely open questions in §4.

## 0. Enabling change (do first)

**E1 — give `MutableState` an internal `name` field.** Set at registration in `Store.state()` (Store.kt:531) and `registerInternalState` (Store.kt:561); `internal var debugName: String? = null` on `MutableState` (no ABI impact — internal). This is the prerequisite for identity-bearing messages in F32, P1-partial-commit, and F11. Size: **S**. Files: `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/MutableState.kt`, `Store.kt` (state()/registerInternalState only).

## 1. Per-finding plans

### F10 — derived recompute failures swallowed (major) — **S**
Two swallow layers: `Derived.kt:81-86` discards the recompute's `TransactionResult`, and `Store.drainPostCommitTasks` (Store.kt:319) wraps every task in bare `runCatching`. Fix both: in `Derived.kt`, capture the recompute result and route `TransactionResult.Error.exception` to `self.uncaughtObserverHandler` (post P1-observer-swallow this is a loud default, not null); in `drainPostCommitTasks`, replace `runCatching { it() }` with catch → `uncaughtObserverHandler?.invoke(e)` (never throw from the drain — it runs inside the top-level action exit and must not corrupt the outer result). Optionally add an `onError: ((Throwable) -> Unit)? = null` param to `derived(...)` (default-param addition → apiDump).
- **Files:** `Derived.kt`, `Store.kt` (drainPostCommitTasks, ~312-321).
- **Tests:** extend `holdfast/src/commonTest/.../DerivedTest.kt` — throwing `compute` on second recompute reaches handler; derived value recovers on next source commit (not frozen); a throwing postCommit task doesn't affect the parent action's `Success`.
- **API/docs:** apiDump only if `onError` param added; `holdfast/GUIDE.md` derived section, `holdfast/CHANGELOG.md`.
- **Depends on:** P1-observer-swallow (routing target semantics), E1 not required.

### F11 — restore() commits type-mismatched values (major) — **M**
Capture runtime class at snapshot: change `StoreSnapshot`'s internal payload from `Map<String, Any>` to `Map<String, SnapshotEntry>` where `SnapshotEntry(rawValue: Any, valueClass: KClass<*>)` (constructor is `internal` — free to reshape; keep `stateNames`/`size` accessors). In `restore()` (Snapshot.kt:67-77), before `stagePendingRaw`, verify `entry.valueClass.isInstance(ms.rawCurrentValue) || ms.rawCurrentValue::class.isInstance(entry.rawValue)`; on mismatch, `error(...)` naming state name, snapshot class, destination class — surfaces as `TransactionResult.Error` and rolls the whole restore back (atomic contract preserved). Add `validateTypes: Boolean = true` opt-out param on `restore` for polymorphic states (documented limitation).
- **Files:** `Snapshot.kt` (both snapshot() and restore()).
- **Tests:** `SnapshotTest.kt` — cross-store restore with same name/different type → `Error` naming the state, no state mutated, no observer fired; symmetric-transformer round-trip still works; polymorphic sealed-type state with `validateTypes = false` passes.
- **API/docs:** apiDump (param addition); `holdfast/GUIDE.md` snapshot/restore section; `holdfast/CHANGELOG.md`. Rollback-never-reruns-`Transformer.set` contract untouched (validation happens before staging; still uses `stagePendingRaw`).

### F17 — derived() bare CCE for computed{} sources; zero sources accepted (minor) — **S**
At `Derived.kt:57-70`: add `require(sources.isNotEmpty()) { "derived requires at least one source state — with no sources it would never recompute" }`; replace the blind `src as MutableState<Any>` cast with a checked cast throwing a teaching `IllegalArgumentException`: computed{}/hand-rolled `State` has no observer fanout — "derive from the underlying `state {}` properties instead". Fold in the disposed check from P1-disposed-gaps (`check(!isDisposed)` at entry).
- **Files:** `Derived.kt`.
- **Tests:** `DerivedTest.kt` — zero sources throws; `computed{}` source throws with teaching message; message strings asserted.
- **API/docs:** no ABI change (behavior only); `holdfast/CHANGELOG.md`; GUIDE derived section note.

### F31 — TimingMiddleware reports Committed before commit; excludes fanout (minor) — **S**
`onTransactionCompleted` fires pre-commit by contract (`Middleware.kt:8-10`), so the fix is to defer reporting: in `onTransactionCompleted`, schedule `context.store.postCommit { ... }` (TimingMiddleware is in-module; opt in to `@StoreInternalApi`). The postCommit task reads `context.transaction.status` — now truthfully `Committed` or `Failed` — and elapsed time now includes commit fanout (drain runs at top-level action exit, after fanout, on both success and failure paths per Store.kt:486). Keep `onTransactionError` → `RolledBack` immediate. This changes observable callback behavior (status can now be `Failed`; timing includes fanout) — changelog under Changed.
- **Files:** `middleware/TimingMiddleware.kt`.
- **Tests:** `MiddlewareTest.kt` (or new `TimingMiddlewareTest.kt`) — failing commit (throwing `Bridge.publish`) reports `Failed`, not `Committed`; successful commit reports `Committed` with elapsed ≥ a slow observer's sleep.
- **API/docs:** no signature change; KDoc rewrite (currently documents the wrong contract); `holdfast/CHANGELOG.md`.
- **Note:** interacts with F10's drain change (both touch drain semantics) — F10 first.

### F32 — error-message identity (minor) — **M**
Raise legacy messages to the frame-exception bar:
- `Store.kt:199` `"store disposed"` → include store class simple name and the fix (e.g. `"CounterStore is disposed — dispose() is terminal; create a new instance."`). Same message constant used by `Effect.kt:29` and the new P1-disposed-gaps call sites — centralize in one internal helper (`internalCheckNotDisposed()`, see P1-disposed-gaps).
- `Store.kt:663-664` — split the two conflated failures: (a) "not a `MutableState`" (hand-rolled/computed State passed to `mutate`) and (b) "owned by a different store" — the latter naming both stores' class names and the state's `debugName` (E1).
- `Transaction.kt:298` `"Commit failed"` → include `txn.id`, the failing state's `debugName`, and phase (apply / bridge-publish / event-drain) — see P1-partial-commit, which owns this line.
**Lockstep docs (required):** `holdfast/GUIDE.md:1023,1025,1049` quotes the current strings verbatim ("all four quoted strings exist verbatim in code" is an audited claim) — the pitfalls table and dispose row must be updated in the same commit. Also `Store.kt` KDoc at lines 131, 149.
- **Files:** `Store.kt` (checkNotDisposed ~198-200, getMutableState ~661-666, KDoc 128-158), `Effect.kt`, `Transaction.kt` (via P1-partial-commit), `holdfast/GUIDE.md`, `holdfast/CHANGELOG.md`.
- **Tests:** update every test asserting old strings (`StoreDisposeTest.kt`, `FailingBridgeTest.kt` comment, grep for `"store disposed"` / `"State must be created"` in test sources); add assertions for the new identity content.
- **API:** no ABI change (message text only) — but any doc-snippets-gated block quoting messages must be checked.

### F29 — ABI hygiene grab-bag (minor) — **M**, mostly independent sub-items
1. **const vals → private:** `HEX_DIGITS`/`HEX_RADIX`/`TMP_PREFIX` in `holdfast/src/jvmAndAndroidMain/.../bridge/FileSystemKvStore.kt` + `holdfast/src/iosMain/.../bridge/FileSystemKvStore.ios.kt`, and `holdfast-coroutines/src/jvmAndAndroidMain/.../SuspendingFileSystemKvStore.jvm.kt` + `iosMain/.../SuspendingFileSystemKvStore.ios.kt`; `KEY_START_MS` in `middleware/TimingMiddleware.kt` (already in a `private companion object` — the const val itself needs `private`, a known Kotlin hoisting quirk). Binary-breaking removal from ABI; 0.x acceptable; apiDump (jvm + klib, both modules) + changelog "Removed (ABI)".
2. **`StoreLock` public un-annotated:** annotate class `@StoreInternalApi` (used only inside core; no cross-module usage found). apiDump annotation change.
3. **`MutableState` public constructor:** make constructor `internal` (only construction sites are Store.kt:531/561, same module). apiDump.
4. **`MutableState.bridge` setter bypasses disposed check:** add `check(!owningStore.isDisposed)` (with F32-style message) in the setter (`MutableState.kt:229-242`); `shutdownSilently` writes fields directly so dispose still works.
5. **`TransactionResult.Success/Error` copy() forging + `Publisher.publish(): Boolean` never read (Contract.kt:18-20):** KDoc-honesty only for now (see §4 — signature changes are 1.0-batch material).
- **Tests:** `StoreDisposeTest.kt` — direct `ms.bridge = x` post-dispose throws; existing FileSystemKvStore tests unaffected (constants are implementation-internal).
- **API/docs:** apiDump both modules mandatory; `holdfast/CHANGELOG.md` + `holdfast-coroutines/CHANGELOG.md`; MIGRATING.md note for the ABI removals.

### P1-observer-swallow — observer exceptions silent by default — **S**
Per ROADMAP 0.3.0 ("log loudly instead of silent swallow"): change `uncaughtObserverHandler` (Store.kt:239-247) default from `null`-silent to a non-null default handler that prints to stderr (common `println` with store class + exception; keep `null` assignment as the explicit opt-back-into-silence). Implementation: keep the field nullable, but in `MutableState.notifyObservers` (MutableState.kt:175-186) fall back to a module-level default logger when the handler is null — or make the property non-null with a default value; the latter is cleaner but changes the ABI type; recommend the fallback approach + an explicit `Store.silentObserverHandler` sentinel if opt-out is wanted. Add "route here" KDoc pointers at `Effect.kt` and `MutableState.observe`.
- **Files:** `Store.kt` (uncaughtObserverHandler region only), `MutableState.kt` (notifyObservers), `Effect.kt` (KDoc).
- **Tests:** `EffectTest.kt` — throwing observer produces the log line via injected handler; default path exercised via handler-null + captured output where feasible.
- **API/docs:** apiDump only if property type changes (prefer not); GUIDE §effect, `holdfast/CHANGELOG.md` (behavior change).

### P1-partial-commit — fanout exceptions → partial commit as ignorable Error — **M**
Contract forbids un-applying writes (rollback never touches state), so the fix is containment + honesty, in `Transaction.commitDispatching` (Transaction.kt:264-298):
1. Wrap the per-state `applyTopLevel` loop so a throw is caught with the failing state identified; **keep stop-at-first-failure** for the raw apply (a throwing `transformer.get` means the value itself is bad), but record which states were already applied.
2. Replace `TransactionException("Commit failed")` with a message naming `txn.id`, the failing state's `debugName`, the phase (state-apply / bridge-publish / event-drain), and the partial-commit consequence ("N earlier states in this transaction were already applied and remain committed").
3. Separately: bridge-publish failures should not poison state application — `MutableState.applyCommitted` (MutableState.kt:158) currently lets `bridge.publish` throw after the value+observers landed. Route bridge-publish exceptions to `owningStore.uncaughtObserverHandler` instead of throwing mid-fanout (bridge publish is fire-and-forget by contract already; KvBridge encode failures stop being commit-killers). This is the one semantic change needing sign-off (§4).
- **Files:** `Transaction.kt` (commitDispatching + TransactionException message), `MutableState.kt` (applyCommitted).
- **Tests:** `TransactionTest.kt`/`BridgeTest.kt` + `holdfast-testing`'s `FailingBridgeTest.kt` (comment at :75 references the old message) — throwing publish: commit succeeds, error reaches handler; throwing `transformer.get` mid-multi-state commit: `Error` message names state and lists applied-anyway states.
- **API/docs:** no ABI change; GUIDE commit-fanout section; `holdfast/CHANGELOG.md`. **Coordination hazard:** `commitDispatching` is driven by `suspendAction`'s interpose — verify `SuspendingBridgePublishAwaitedTest` still passes (awaited-publish errors must still surface per that path's own contract; only the sync `Bridge.publish` path changes).
- **Depends on:** E1 (state names).

### P1-emit-owner — emit() lacks the ownership check mutate has — **S**
In `EventfulStore.emit` (EventfulStore.kt:89-96) **and** `EventfulSupport.emit` (EventfulSupport.kt:112-123), mirror mutate's check (Store.kt:623-627): require `txn.ownerThreadId == currentThreadId() || suspendingOwner != null`; otherwise throw a teaching `IllegalStateException` ("emit() from a non-owner thread would stage onto another action's transaction; emit from the action body, or from your own action"). Both classes are in core and can read `activeTransaction` + `suspendingOwner` (`@StoreInternalApi`, already opted in file-wide).
- **Files:** `EventfulStore.kt`, `EventfulSupport.kt`.
- **Tests:** `EventfulVaultTest.kt`/`EventfulSupportTest.kt` — emit from a spawned thread during an action throws; suspendAction emit-after-dispatcher-hop still works (coroutines module test `EventfulVaultCommitOrderingTest.kt` guards the relaxation).
- **API/docs:** no ABI change; `Transaction.stagePendingEvent` KDoc already asserts this contract — now true. Changelog (behavior change).

### P1-disposed-gaps — atomic/suspendAction/suspendAtomic/derived skip checkNotDisposed — **M**
Add a `@StoreInternalApi fun internalCheckNotDisposed()` to `Store` (delegating to the private check, with the F32 message) so out-of-file/out-of-module entry points can enforce the rule. Then:
- `Atomic.kt:85-96` (`atomic`): check every store in `sorted` before any lock acquisition.
- `Derived.kt` (`derived`) + `computed`: check at entry (overlaps F17).
- `holdfast-coroutines/SuspendAction.kt:63`: check at entry AND re-check after mutex acquisition (a dispose can land while parked on the mutex).
- `holdfast-coroutines/SuspendAtomic.kt:97`: same, all participants, before and after acquisition.
- `holdfast-coroutines/SuspendDerived.kt`: check at entry.
- **Files:** `Store.kt` (new @StoreInternalApi member — apiDump), `Atomic.kt`, `Derived.kt`, `SuspendAction.kt`, `SuspendAtomic.kt`, `SuspendDerived.kt`.
- **Tests:** `StoreDisposeTest.kt` (atomic/derived), `SuspendActionTest.kt`, `SuspendAtomicTest.kt`, `SuspendDerivedTest.kt` — each entrypoint on a disposed store throws (not `Success`).
- **API/docs:** apiDump both modules; GUIDE dispose row; changelogs. **Order:** core hook lands before coroutines changes.

### P1-update-rmw — standalone update non-atomic; KDoc asserts wrong contract — **S/M**
In `Store.update` (Store.kt:605-608): if there is an owned active transaction (same check as mutate, including the `suspendingOwner` relaxation), keep current behavior (RYOW read is correct); otherwise wrap the whole RMW: `action { this@update mutate block(this@update.value) }` so the read happens under `transactionLock`. Fix the KDoc (currently claims "an implicit single-shot transaction wraps the operation"— it will now be true). Frame policing composes: the synthesized `action` runs the standard frame gate.
- **Files:** `Store.kt` (update only).
- **Tests:** `ConcurrencyTest.kt` — the ROADMAP regression: 10,000 concurrent standalone `update { it + 1 }` increments, expect 10,000 (currently ~50% lost). Plus: update inside action still RYOW-reads staged values.
- **API/docs:** no ABI change. **README.md Known issues bullet 2 must be removed/rewritten in lockstep** (it documents the bug as open); GUIDE; changelog.

### P1-invoke-nonatomic — store{} vs store action{} silent non-atomicity — **M**
ROADMAP: "bare invoke becomes non-mutating; mutate outside an action via invoke fails loudly." Implement with a thread-local marker (reuse the `platform/FrameLocal` mechanism `FrameMarkers` uses): `invoke` (Store.kt:505) installs an "inside bare invoke" flag for the block's duration; `mutate`'s one-shot-synthesis branch (Store.kt:653) and `update`'s new standalone branch check it and throw a teaching exception ("`store { }` provides context only and opens no transaction — writes here would commit one by one with observers firing between them. Use `store action { }`."). Non-mutating uses (`effect`, `bridge` wiring) keep working; `action` called inside invoke clears/ignores the flag (legal).
- **Files:** `Store.kt` (invoke, mutate, update), possibly `platform/FrameLocal.kt` (reuse as-is if generic enough — verify; else a small new thread-local in Store.kt companion via the same expect/actual).
- **Tests:** new `InvokeMutationGuardTest.kt` (or extend `StoreStateTest.kt`) — `store { count mutate 1 }` throws with the message; `store { count effect {} }` fine; `store { action { count mutate 1 } }` fine; nested `store { otherStore action {} }` fine.
- **API/docs:** no ABI change but **BREAKING behavior** — changelog under a behavior-change heading, MIGRATING.md entry, README Known issues bullet 3 rewritten, `invoke` KDoc, GUIDE.
- **Ordering:** must land with or after P1-update-rmw (both rewrite the same `update`/`mutate` region).

### P1-crtp — wrong Self degrades to swallowed CCE — **M**
ROADMAP: enforce at construction. Add `internal expect fun validateCrtpSelfType(store: Store<*>)` in `platform/` (new expect/actual files: `platform/CrtpValidation.kt` common + jvmAndAndroid actual using `javaClass.genericSuperclass` walk, ios/wasm no-op actuals); call from an `init {}` block in `Store`. JVM actual walks the superclass chain to the `Store` parameterization; if the resolved type argument is a concrete class ≠ the instance's class → throw a two-type teaching `IllegalStateException` ("class Foo declares Store<Bar>; Self must be the declaring class itself"). Type-variable arguments (generic intermediate bases) skip validation. wasmJs single-thread assumption irrelevant here; native gets no enforcement (documented).
- **Files:** `Store.kt` (init block near top), new `platform/CrtpValidation.kt` + `platform/CrtpValidation.jvmAndAndroid.kt` (or matching source-set naming used by `Threading` actuals — mirror those file locations exactly), ios/wasm actuals.
- **Tests:** new `CrtpValidationTest.kt` in `jvmTest` (JVM-only semantics): `class Foo : Store<Bar>()` throws at construction with both names; `EventfulStore` subclass passes; generic intermediate base passes.
- **API/docs:** no public API change (internal expect/actual); changelog; GUIDE "defining a store" note. Note `-Xexpect-actual-classes` is already on (buildSrc).

### P1-lazy-registration — snapshot()/properties miss never-read delegates — **M**
ROADMAP: eager registration via `provideDelegate`. Least-invasive shape: add a top-level `operator fun <T : Any> StateDelegate<T>.provideDelegate(thisRef: Any?, property: KProperty<*>): StateDelegate<T>` in `Contract.kt` that invokes `getValue` once (registering the state) and returns `this`. All `by state {}` declarations recompiled against the new version register at construction; previously-compiled binaries stay lazy (fine). **Semantic break:** initializer lambdas now run at construction — declaration-order dependencies between state initializers become construction-time failures (see §4). Also add the missing lazy-registration warning to `Store.properties` KDoc (Store.kt:221-227) regardless.
- **Files:** `Contract.kt` (new operator — apiDump), `Store.kt` (properties KDoc), `Snapshot.kt` (KDoc: the "touch them explicitly first" caveat becomes historical).
- **Tests:** `SnapshotTest.kt`/`StoreStateTest.kt` — snapshot of a freshly constructed, never-read store contains all states; initializer-order edge documented in a test; `InitializerTest.kt` review (initializer laziness assertions will flip — expect deliberate updates).
- **API/docs:** apiDump; **CLAUDE.md's "states are lazily registered" contract line and GUIDE need rewording**; changelog BREAKING; MIGRATING.md.
- **Ordering:** land after F11 (both touch Snapshot.kt and its tests) to avoid churn.

### P1-defaultscope — once-per-process CAS undocumented, no test reset — **S**
Docs-first: add a Known-issues/behavior entry to root `README.md`, GUIDE (scope resolution section), and `Store.defaultScope` KDoc is already honest — cross-link from `storeTest` docs ("setting defaultScope in a test poisons the process"). Optionally (maintainer decision, §4) add `@StoreInternalApi fun resetDefaultScopeForTesting()` on the companion, called by `storeTest` teardown in `:holdfast-testing`.
- **Files:** `README.md`, `holdfast/GUIDE.md`, `Store.kt` (companion KDoc only in the default plan; + companion member and apiDump if reset hook approved; + `holdfast-testing/.../StoreTest.kt` teardown).
- **Tests:** only if reset hook lands (reset restores lazy fallback; second assignment after reset succeeds).

## 2. Intra-lane ordering constraints

1. **E1 (MutableState name field)** → before F32, P1-partial-commit, F11 (they consume names in messages).
2. **P1-observer-swallow** → before F10 and P1-partial-commit (both route errors into the handler; its default-loudness decision shapes their tests).
3. **F32 message helper (`internalCheckNotDisposed` + message format)** → before/with P1-disposed-gaps (core hook must exist before the coroutines-module call sites) and before F29-item-4 (bridge-setter check reuses the message).
4. **P1-update-rmw ↔ P1-invoke-nonatomic**: same Store.kt region (`update`/`mutate`/`invoke`); implement as one sequence (update first, then invoke-guard on top).
5. **F11 → P1-lazy-registration**: both rewrite Snapshot.kt semantics/tests; F11 first.
6. **F10 → F31**: F31's postCommit-based reporting rides on the drain whose error routing F10 changes.
7. **apiDump/changelog batching**: F29, P1-disposed-gaps, P1-lazy-registration, F11, (F10 if onError param) all touch dumps — run `./gradlew apiDump` after each PR, but plan the changelog "Behavior changes" section once.
8. Coroutines-module edits (P1-disposed-gaps part 2, F29 const vals in `-coroutines`) strictly after their core prerequisites.

## 3. SHARED-FILE MANIFEST (for cross-lane conflict detection)

**Core — `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/`:**

| File | Regions/functions touched | Findings |
|---|---|---|
| `Store.kt` **(hotspot)** | init block (new, top of class); `checkNotDisposed` (~198-200) + new `internalCheckNotDisposed`; `uncaughtObserverHandler` (~239-247); `drainPostCommitTasks` (~312-321); `invoke` (~505); `state()` (~517-537) + `registerInternalState` (~549-565); `update` (~605-608); `mutate` (~619-654); `getMutableState` (~661-666); `properties` KDoc (~221-231); companion `defaultScope` KDoc (~818-854); dispose-KDoc (~128-158) | P1-crtp, F32, P1-disposed-gaps, P1-observer-swallow, F10, P1-invoke-nonatomic, E1, P1-update-rmw, P1-lazy-registration, P1-defaultscope |
| `MutableState.kt` | constructor visibility + new `debugName`; `notifyObservers`; `applyCommitted`; `bridge` setter | E1, F29, P1-observer-swallow, P1-partial-commit |
| `Transaction.kt` | `commitDispatching` (~235-304) incl. `TransactionException("Commit failed")` | P1-partial-commit, F32 |
| `Snapshot.kt` | whole file (payload shape, restore validation, KDocs) | F11, P1-lazy-registration |
| `Derived.kt` | `derived()` entry + observer lambda; `computed()` entry | F10, F17, P1-disposed-gaps |
| `Atomic.kt` | `atomic()` entry (~85-96) only | P1-disposed-gaps |
| `EventfulStore.kt` / `EventfulSupport.kt` | `emit` | P1-emit-owner |
| `Effect.kt` | disposed-check message + KDoc | F32, P1-observer-swallow |
| `Contract.kt` | new `provideDelegate` operator; `Publisher` KDoc | P1-lazy-registration, F29 |
| `StoreLock.kt` | class annotation only | F29 |
| `middleware/TimingMiddleware.kt` | whole file | F31, F29 |
| `platform/CrtpValidation*.kt` | **new files** (common + jvmAndAndroid + ios + wasmJs actuals) | P1-crtp |
| `bridge/FileSystemKvStore.kt` (jvmAndAndroidMain) + `bridge/FileSystemKvStore.ios.kt` (iosMain) | companion const visibility | F29 |

**Coroutines — `holdfast-coroutines/src/`:** `commonMain/.../SuspendAction.kt` (entry), `SuspendAtomic.kt` (entry), `SuspendDerived.kt` (entry) — P1-disposed-gaps; `jvmAndAndroidMain/.../SuspendingFileSystemKvStore.jvm.kt` + `iosMain/.../SuspendingFileSystemKvStore.ios.kt` — F29.

**Generated/locked artifacts:** `holdfast/api/jvm/holdfast.api`, `holdfast/api/holdfast.klib.api`, `holdfast-coroutines/api/*` (apiDump); `holdfast/detekt-baseline.xml` possibly (new code in baselined files).

**Docs (lockstep):** root `README.md` (Known issues — three bullets rewritten: livelock stays, update+invoke bullets change), `holdfast/GUIDE.md` (pitfalls table §with quoted error strings, API-reference rows for `restore`/`derived`/`update`/`invoke`/`uncaughtObserverHandler`, dispose row), `holdfast/README.md`, `holdfast/CHANGELOG.md`, `holdfast-coroutines/CHANGELOG.md`, `MIGRATING.md` (invoke-guard, eager registration, F29 ABI removals), `ROADMAP.md` (tick delivered 0.3.0 items), **`CLAUDE.md`** (lazy-registration contract line if P1-lazy-registration lands). Note the doc-snippets gate: any GUIDE/README fenced block touching changed APIs recompiles in `./gradlew check`.

**Tests (existing files modified):** `DerivedTest.kt`, `SnapshotTest.kt`, `StoreDisposeTest.kt`, `TransactionTest.kt`, `BridgeTest.kt`, `EffectTest.kt`, `EventfulVaultTest.kt`, `EventfulSupportTest.kt`, `ConcurrencyTest.kt`, `MiddlewareTest.kt`, `InitializerTest.kt`, `StoreStateTest.kt`; coroutines: `SuspendActionTest.kt`, `SuspendAtomicTest.kt`, `SuspendDerivedTest.kt`, `SuspendingBridgePublishAwaitedTest.kt` (verify unaffected); testing-module: `FailingBridgeTest.kt` (message comment). New: `CrtpValidationTest.kt` (jvmTest), `InvokeMutationGuardTest.kt`.

**High-collision warning for other lanes:** `Store.kt` (nearly every region), `Transaction.kt:commitDispatching` (lane touching frames/suspendAction will also want this), `MutableState.kt:applyCommitted` (any KvBridge/SuspendingBridge lane), README Known issues block, both CHANGELOGs, api dumps (regenerate last, per merged branch).

## 4. Risk notes — maintainer decisions needed

1. **Bridge-publish failures stop aborting commits (P1-partial-commit item 3).** Recommended default: route sync `Bridge.publish` throws to `uncaughtObserverHandler`, commit succeeds (bridge publish is documented fire-and-forget; today it produces the worst outcome — partial commit + ignorable Error). Alternative: keep throwing but with the enriched message only. This changes `FailingBridgeTest` semantics in `:holdfast-testing` — coordinate with any testing-lane owner.
2. **Observer-swallow default (P1-observer-swallow).** Recommended: loud stderr default per ROADMAP; risk is log spam in apps that relied on silence. Keep `uncaughtObserverHandler = null`→silent as explicit opt-out or introduce a no-op sentinel — pick one; I recommend "null field ⇒ built-in loud fallback, assign a no-op lambda to silence" (no ABI change).
3. **Eager registration (P1-lazy-registration).** Biggest behavior break in the lane: initializers run at construction; forward-referencing initializers (`val y by state { x.value }` where `x` declared later) now NPE at construction. Recommended: ship per ROADMAP with a MIGRATING recipe; fallback option if deemed too hot: KDoc/`properties`-warning only and defer the operator. Also requires editing CLAUDE.md's architecture-contract line — flag explicitly in the PR.
4. **Invoke mutation guard (P1-invoke-nonatomic).** Breaking for code that used `store { x mutate 1 }` as a working one-shot. ROADMAP commits to it; recommended: implement, BREAKING changelog + MIGRATING. Fallback: KDoc + Known-issues only.
5. **F11 type validation strictness.** Class-identity/isInstance checks can spuriously reject polymorphic states (State<Shape> holding different subtypes across snapshot/restore). Recommended: `validateTypes: Boolean = true` opt-out + documented limitation.
6. **CRTP enforcement is JVM/Android-only** (reflection); iOS/wasm keep the CCE behavior. Recommended: accept (dev loop is JVM) + KDoc honesty. Alternative "catch CCE in action and re-throw teaching message" is unreliable (CCE site varies) — not recommended.
7. **F29 items 5** (`TransactionResult` data-class `copy()` forging, `Publisher.publish(): Boolean`): both need source-breaking signature changes to fix properly (`@ConsistentCopyVisibility` + internal ctor breaks the cross-module constructors in `:holdfast-coroutines`; publish→Unit breaks every Bridge implementor). Recommended: KDoc-only now, batch into the pre-1.0 breaking window (ROADMAP already has an "API-shaped P2 triage" gate).
8. **P1-defaultscope reset hook.** Adding `@StoreInternalApi` reset weakens the settable-once contract but fixes real test poisoning. Recommended: docs now; hook + `storeTest` auto-reset as a separate opt-in decision (touches `:holdfast-testing`, likely another lane's territory).
9. **F31 status change**: `onResult` can now report `TransactionStatus.Failed` — downstream metric consumers keying on the enum will see a new value. Changelog under Changed.

## 5. Size summary

| Finding | Size | | Finding | Size |
|---|---|---|---|---|
| E1 enabling | S | | P1-partial-commit | M |
| F10 | S | | P1-emit-owner | S |
| F11 | M | | P1-disposed-gaps | M |
| F17 | S | | P1-update-rmw | S/M |
| F31 | S | | P1-invoke-nonatomic | M |
| F32 | M | | P1-crtp | M |
| F29 | M | | P1-lazy-registration | M |
| P1-observer-swallow | S | | P1-defaultscope | S |

Lane total: roughly 2 engineer-weeks including tests and lockstep docs; Store.kt is the serialization point — sequence work there per §2.

### Critical Files for Implementation
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Store.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Transaction.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/MutableState.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Derived.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Snapshot.kt