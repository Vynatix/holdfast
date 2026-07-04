Investigation complete. Here is the implementation plan for workstream F.

---

# Lane F Implementation Plan — docs, CI/publish, changelogs, API polish, hallmark assign

**Verified context that shapes everything below:**
- `git tag` is empty — 0.1.0 was never tagged or published. The core changelog's "First public release on Maven Central" (`/home/user/holdfast/holdfast/CHANGELOG.md:101-105`) and the root README Central badge are both false, matching ROADMAP.md:3 ("0.1.0 (unpublished)").
- publish.yml's env vars (`ORG_GRADLE_PROJECT_mavenCentralUsername`, `signingInMemoryKey*`) and its task (`publishAndReleaseToMavenCentral`) are exactly the vanniktech plugin's contract — the workflow was written *for* vanniktech; only the build side is missing. The homegrown `holdfast.publish.sonatype.gradle.kts` points `maven-publish` at `https://central.sonatype.com/api/v1/publisher/upload`, which `maven-publish` cannot speak (it does Maven PUTs; the Portal wants a bundle POST). ROADMAP 0.2.0 row 1 already mandates the vanniktech rip-and-replace.
- Doc-lockstep tax: any edit to a fenced ```kotlin block in the five tracked docs (root README, holdfast/README, holdfast/GUIDE, coroutines/compose READMEs) must update its twin in `doc-snippets/src/test/kotlin/.../twins/` or `doc-snippets/snippet-exclusions.txt`, and exclusion indices are positional — **adding/removing a fence renumbers every later `path#index` entry**. This is the biggest hidden cost and cross-lane conflict source.
- Changelog archive rule: everything below the `## 0.1.0` heading in core, and the `2.0.0`/`0.2.0` sections in the per-module changelogs, are frozen internal history. The 0.1.0 entry itself and `[Unreleased]` are editable; new framing/divider sections may be *added* above frozen content.

## 1. Per-finding plans

### Code-change findings (F23, F33, parts of P1-install) — split out first

---

### F23 — `assign` context is bare `Store<*>` (M, code)

**Problem** (`holdfast-hallmark/src/commonMain/kotlin/com/vynatix/holdfast/hallmark/BoxedHandle.kt:85-88`): `context(store: Store<*>)` is satisfied by *any* Store receiver — inside a Store subclass body (compiles, silently commits a one-shot outside any action) and inside another store's `action {}` (resolves to the wrong store, runtime foreign-state error). `Store` is `@StoreActionDsl`-annotated (Store.kt:70), so DslMarker shadowing works in our favor once the context is typed.

**Fix (two layers):**
1. **Compile-time gate — type the handle to its owning store.** Add the store type parameter: `BoxedHandle<V : Store<V>, P : Any, O : Boxed<P>>` (and `BoxedHandleDelegate<V, P, O>`); `boxedHandle` already has `V` in scope and returns the typed delegate. Change `assign` to `context(store: V) infix fun <V : Store<V>, P : Any, O : Boxed<P>> BoxedHandle<V, P, O>.assign(primitive: P)`. Inside `otherStore.action {}` the only implicit receiver is `OtherStore` (DslMarker hides outer ones), so wrong-store use becomes a compile error.
2. **Runtime gate — require an open transaction.** `Store.activeTransaction` is plain public (Store.kt:215). In `assign`, before mutating: throw `IllegalStateException` with a teaching message ("`assign` must be called inside `store action { }` — outside an action it would commit a silent one-shot transaction; use `state mutate civilize(...)` inside an action") when `store.activeTransaction == null` or its `ownerThreadId` isn't current. Also verify `state.getMutableState().owningStore === store` for a clear wrong-store message covering type-erased/generic escapes.
3. Fix the contradictory KDoc at BoxedHandle.kt:30-33 ("without enabling Kotlin context parameters" — written before `assign` existed) and the `UserVault` sample name (overlaps F33).

**Files:** `BoxedHandle.kt`; tests in `holdfast-hallmark/src/commonTest/` (new `BoxedHandleAssignTest.kt`: assign-inside-own-action succeeds; assign with no active transaction throws the teaching message; validation failure still rolls back); `holdfast-hallmark/api/holdfast-hallmark.klib.api` + `api/jvm/*.api` via `apiDump`.
**Public API change:** yes — `BoxedHandle`/`BoxedHandleDelegate` gain a type parameter, `assign` signature changes. Module is unreleased (mavenLocal-only), so no deprecation bridge needed; changelog entry under hallmark `[Unreleased]` "### Changed".
**Note:** BoxedHandle.kt is already ktlint-excluded for context syntax — no new exclusion needed. `apiDump` for this module needs the sibling hallmark repo in `~/.m2` + `-Pholdfast.includeHallmark=true` (clone `vynatix/hallmark`, `./gradlew publishToMavenLocal` there — same ritual CI uses).

---

### F33 — API polish bundle (L overall, code + docs)

Split into **do-now** (0.x break window per ROADMAP principle 1: "one big break in 0.2.0") and **defer/waive**:

**Do now — API changes (each needs `apiDump` + changelog + doc lockstep):**

| Item | Fix | Files | Breaking? |
|---|---|---|---|
| `derived` returns raw `Pair` (Derived.kt:57-60) | Introduce `class DerivedState<T : Any>(val state: State<T>, val disposable: Disposable)` with `operator component1/component2` — destructuring call sites stay source-compatible | `Derived.kt` (+ its KDoc example), `DerivedTest` additions, GUIDE §14 listing #34 (excluded — no twin), GUIDE cookbook derived block (twinned — update twin), `holdfast/api/*` | Binary yes, source mostly no; 0.x OK |
| No per-instance middleware remove (Store.kt:339/347) | Add `fun removeMiddleware(middleware: Middleware<Self>): Boolean` (identity removal under `middlewareLock`); **keep** the `middlewares` name (rename deferred — see waive list) | `Store.kt`, `MiddlewareTest`, GUIDE #40 listing, `holdfast/api/*` | Additive |
| No `action(name=)` — lambda txn ids are mangled `body::class.simpleName` or random UUID (Store.kt:459) | Add overload `fun <R> action(name: String, body: Self.() -> R): TransactionResult<R>` threading `name` into `Transaction(id = name, …)`; existing infix form unchanged. Optional symmetry: same param on `suspendAction` in `:holdfast-coroutines` (recommend including) | `Store.kt`, coroutines `SuspendAction.kt`, tests, `holdfast{,-coroutines}/api/*` | Additive |
| `crypto.Cipher` collides with `javax.crypto.Cipher` (Cipher.kt:15) | Rename interface to `StoreCipher`; keep `@Deprecated(WARNING) typealias Cipher = StoreCipher` for one minor (matches existing alias policy) | `crypto/Cipher.kt`, `XorCipher.kt`, `EncryptingTransformer.kt`, GUIDE #37 listing, MIGRATING.md table, `holdfast/api/*` | Aliased break |
| `FrameObserver` callback asymmetry (Frame.kt:213-238) | Add `participants: List<Store<*>>` to `onFrameCommitted`/`onFrameRolledBack` (defaulted bodies stay). Legal without aliases: interface is `@ExperimentalStoreApi` and unpublished | `Frame.kt`, frame dispatch sites (`Atomic.kt`, coroutines `SuspendAtomic.kt`), `FrameObserverTest`, GUIDE §15/#61 block (excluded), `holdfast/api/*` | Experimental-surface break |
| `storeTest` doesn't clear process-global `FrameObservers` | In `StoreTestScope.tearDown` (internal), call `FrameObservers.clear()` (with `@OptIn(ExperimentalStoreApi::class)`) after existing teardown; add a test that an observer registered in one `storeTest` is gone in the next | `holdfast-testing/.../StoreTestScope.kt`, test | None (internal) |

**Do now — doc/KDoc-only items (no API change):**
- `CoroutineName("VaultProcessScope")` → `"StoreProcessScope"` (Store.kt:825) — debug-string only.
- Vault-named KDoc samples → Store names (new-docs-use-Store rule): `LoggingMiddleware.kt:17`, `ValidationMiddleware.kt:18`, `EncryptingTransformer.kt:20`, `EventfulStore.kt:20`, `Derived.kt:18`, `ComposeBindings.kt:26,51`, `BridgeView.kt:36,41`, `FailingBridge.kt:15`, `BoxedHandle.kt:21`, `BoxedState.kt:14`.
- `Eventful.kt:22` "Issue 15 will add `EventfulSupport`" → it shipped (`EventfulSupport.kt` exists); rewrite as present-tense.

**Defer / explicitly waive (record in ROADMAP 0.6.0 "API-shaped P2 triage" and changelog rationale):**
- `TransactionResult.Error` vs `kotlin.Result.Failure` naming — renaming churns every consumer, matcher (`shouldBeError`), and doc for marginal gain; waive with a written rationale.
- Renaming `middlewares()` itself (e.g. → `addMiddleware`) — bundle with the waive decision; `removeMiddleware` addition resolves the functional gap now.

---

### P1-install — publish story (L; blocker) — build/infra code + docs

**Decision: adopt the vanniktech plugin. Do not "fix" publish.yml to the build's real task.** Rationale: (a) the real task `publishAllPublicationsToSonatypeRepository` uploads via `maven-publish` to the Portal upload API URL, which structurally cannot work; (b) publish.yml's task name and all five `ORG_GRADLE_PROJECT_*` names are already vanniktech's exact contract; (c) ROADMAP 0.2.0 row 1 explicitly mandates this replacement. The alternative would leave a publish pipeline that fails at upload time on release day.

**Steps:**
1. `gradle/libs.versions.toml`: add `com.vanniktech:gradle-maven-publish-plugin` (0.34.x — supports Dokka V2, Gradle 9, KMP+Android). `buildSrc/build.gradle.kts`: add it to dependencies (buildSrc convention plugins consume plugin classes as deps).
2. Rewrite `buildSrc/src/main/kotlin/holdfast.publish.sonatype.gradle.kts` to apply `com.vanniktech.maven.publish` and configure `mavenPublishing { publishToMavenCentral(); signAllPublications(); pom { … } }`, migrating the pom metadata (url/licenses/developers/scm) from `holdfast.publish.gradle.kts`. Keep plugin id `holdfast.publish.sonatype` so the six module build files don't change (conflict avoidance). Fold or slim `holdfast.publish.gradle.kts` (group/version derivation from `-Pholdfast.version` stays; vanniktech supplies its own publications for KMP — remove the hand-rolled repository block and stale "out of scope for 1.0" comments).
3. `.github/workflows/publish.yml`: task name stays `publishAndReleaseToMavenCentral` (now real). Verify-step: keep. Signing: vanniktech reads `signingInMemoryKey`/`signingInMemoryKeyPassword` — already mapped; `signingInMemoryKeyId` optional. Only check the secrets *exist* org-side (out of repo scope — note in README release docs or CONTRIBUTING).
4. Guard: signing only activates when key material present, so `./gradlew publishToMavenLocal -Pholdfast.version=0.1.0` must still pass unsigned locally — verify.
5. **README honesty** (`README.md:4,112-129`): until the first real release, either drop the Maven Central badge or annotate the Install section: "Not yet on Maven Central — first release is 0.2.0 (see ROADMAP); until then build from source / mavenLocal." Recommend the annotation (keeps the install block, which is snippet-excluded `README.md#2` — no twin churn). Also fix `holdfast/CHANGELOG.md:101-109` false release claim (coordinated with F27).
6. Changelog entry (core `[Unreleased]` → infrastructure note) + CLAUDE.md line 28 "known broken as written" becomes stale — update that sentence.

**Tests:** no unit tests; verification = `./gradlew publishToMavenLocal -Pholdfast.version=0.0.0-test` producing all module artifacts + poms, and `./gradlew :holdfast:tasks | grep publishAndReleaseToMavenCentral` resolving. **API dumps:** none.
**Risk:** vanniktech vs Gradle 9.5/Kotlin 2.3.21/Dokka-V2 interactions (javadoc-jar generation); mitigate by pinning latest plugin and using `JavadocJar.Empty()` or Dokka variant explicitly; `--no-configuration-cache` already passed in publish.yml.

---

### Pure doc/CI findings

### F25 — `:doc-snippets:test` not in ci.yml (S)

Add to the ubuntu job in `.github/workflows/ci.yml`: `:doc-snippets:test` in the "Build and test" step, and `:doc-snippets:detekt :doc-snippets:ktlintCheck` in the lint step (module applies `holdfast.quality`). doc-snippets is JVM-only — ubuntu job only; no change to macos job or publish.yml. No tests/API changes.

### F8 — Frame behavior break misfiled; MIGRATING silent; phantom 0.3 (S)

- `holdfast/CHANGELOG.md:11-42`: within `[Unreleased]`, move **enrollment enforcement** and **inner-error escalation** bullets from `### Added` into `### Changed` with an explicit "**Breaking (behavioral)**" lead and pointer to MIGRATING.md; frame observability/`frameId`/exception hierarchy stay under Added. Mirror the same reclassification in `holdfast-coroutines/CHANGELOG.md` (`suspendAtomic` graduated entry has the identical misfiling).
- `MIGRATING.md`: new section "atomic / suspendAtomic frame enforcement" — before/after behavior, `FramePolicy.AllowUnenrolled` and `FramePolicy.TolerateInnerErrors` escape hatches, note that contract violations rethrow.
- Phantom 0.3: `Frame.kt:57,61` KDoc "(pre-0.3 behavior)" and `GUIDE.md:1789` "pre-0.3 behavior" → "the pre-enforcement behavior (before the frame contract landed)" — no invented version numbers (nothing has shipped; next release is 0.2.0 per ROADMAP).

Files: `holdfast/CHANGELOG.md`, `holdfast-coroutines/CHANGELOG.md`, `MIGRATING.md`, `holdfast/src/commonMain/.../Frame.kt` (comment-only), `holdfast/GUIDE.md`. No API/tests.

### F27 — Changelog integrity (M)

Constraint-aware plan (never rewrite frozen entries; adding sections/framing above them is allowed):
- **Core** (`holdfast/CHANGELOG.md`): merge the two `### Added` sections in `[Unreleased]` (lines 11-56 and 87-99) into one. Rewrite the `## 0.1.0` entry (editable — it's the boundary, not below it): "0.1.0 — initial public cut. **Never published to Maven Central** (no `v0.1.0` tag exists); the first Central release will be 0.2.0. Developed internally as `vault` 1.x-2.0; archive below." Line 260's `vault/CHANGELOG.md` path is below 0.1.0 → leave frozen.
- **Compose** (`holdfast-compose/CHANGELOG.md`): add `## [Unreleased]` (seed with the platform-tier doc note already in core, or "no changes yet") and an `## Internal-only history (preserved as design archive)` divider + framing paragraph above `## 2.0.0`, stating entries below use pre-rename module names/paths (`vault/CHANGELOG.md`, `vault-coroutines/CHANGELOG.md`) and correspond to no published release. Do **not** edit lines 29/44 themselves — the framing note resolves the dead paths without rewriting frozen text. (Alternative — treat the trailing "See `vault/CHANGELOG.md`" pointer lines as navigational and mechanically retarget them to `../holdfast/CHANGELOG.md`; only do this if the reviewer agrees it's not "rewriting an entry". Default: framing-only.)
- **Coroutines/hallmark** (`holdfast-coroutines/CHANGELOG.md:192`, `holdfast-hallmark/CHANGELOG.md:57`): both already have `[Unreleased]`; add the same archive divider + framing above their `2.0.0` sections.

Files: 4 changelogs. No API/tests. Coordinates with F8 (same core/coroutines files — one pass).

### F26 — gate-excluded blocks drifted (M)

All items, with twin/exclusion impact noted:
1. `GUIDE.md:1352` (§14.8 listing, excluded as an API-reference block): `suspendAtomic(vararg vaults: …)` → `vararg stores`, add `policy: FramePolicy = FramePolicy.Strict`. Excluded block — no twin work, but **do not add/remove fences** (index stability).
2. `GUIDE.md:~1910` cheatsheet (excluded `#63`): `"$x.value done"` → `"${x.value} done"`.
3. `Effect.kt:20` KDoc: handler is `T.() -> Unit`, so `println(it)` → `println(this)`.
4. `SuspendingFileSystemKvStore.kt:25` KDoc: fictional `balance suspendBridge SuspendingBridge(kv, …)` → real API: `store { balance bridge kv.bridge("balance", LongCodec) }` (fire-and-forget) or the `suspendingBridge` factory for await-completion.
5. `ComposeBindings.kt:26-30` KDoc: local `val count by store.collectAsState(...)` shadows the store property inside `store.action { count update … }` — rename the local (`val count by …` → `val countValue`) or qualify; plus Vault→Store names (F33 overlap — one edit).
6. `StoreHandleGroup.kt:15-21` KDoc: `track(AccountA)` passes class names as instances — use instances (`val a = track(accountA)`), and `atomic(accountA, accountB)`.
7. Stale harness contract KDocs: `Recorder.kt:69-72` and `StoreHandle.kt:41-42` claim "suspendAction does not run the middleware chain (1.1)" — contradicted by `Store.middlewares` KDoc ("same ordering applies to … suspendAction (issue 31)"). Verify against `SuspendAction.kt`'s current behavior, then rewrite both passages to the current contract.
8. **Gate extension** (the finding's fix suggestion): full KDoc compilation gating is L and out of proportion. Recommend the cheap middle: extend `DocSnippetDriftTest` with a signature-grep assertion for excluded API-reference listings (grep the listed declarations against the source tree, e.g. flag `vararg vaults` / names absent from `api/*.api`). Size M; mark optional — do it only if lane capacity allows, otherwise file an issue.

Files: `holdfast/GUIDE.md`, 6 Kotlin files (KDoc-only), optionally `doc-snippets/src/test/.../DocSnippetDriftTest.kt`. No API changes. Tests: `:doc-snippets:test` must stay green (edited GUIDE blocks are all in excluded listings — verify indices unchanged).

### F22 — `assign`'s `-Xcontext-parameters` requirement undocumented; no hallmark README (M)

- **Create `holdfast-hallmark/README.md`** (model on `holdfast-hallmark-coroutines/README.md`): what it is; unreleased status + build ritual (clone `vynatix/hallmark` → `publishToMavenLocal` → `-Pholdfast.includeHallmark=true`); `boxed` / `boxedHandle` / `ValidatingTransformer` / `BoxedCodec` / `shouldBeBoxedAs`; a prominent **"Using `assign`"** section: consumers must add `compilerOptions { freeCompilerArgs.add("-Xcontext-parameters") }`, and the two-step `state mutate civilize(...)` works without the flag. Document the F23 gate (must be inside the owning store's `action {}`).
- `README.md:85` hallmark row: append "`assign` requires `-Xcontext-parameters` in consumers".
- `holdfast/GUIDE.md` §14.11: same one-line note where `assign` appears.
- `holdfast-hallmark-coroutines/README.md`: cross-link (no flag needed there — plain suspend fun).
- New README is **not** in doc-snippets' tracked set and can't compile on the default build (hallmark dep) — deliberately leave untracked; note this in the file header comment.

Files: new `holdfast-hallmark/README.md`; `README.md`; `holdfast/GUIDE.md`; `holdfast-hallmark-coroutines/README.md`. No API/tests. Sequence **after F23** so the README documents the final `assign` contract.

### P1-module-readmes — testing/hallmark READMEs; GUIDE §11 never mentions storeTest (M)

- **Create `holdfast-testing/README.md`**: 30-second quickstart under `storeTest` (per ROADMAP 0.4.0 row 1) — `storeTest { }` / `track()` / timeline matchers (`shouldFireInOrder`, `shouldHavePublished`, `shouldBeSuccess/Error`), the **unconsumed-error teardown contract**, `Capture` modes, cross-store `shouldCommitTogether`, note that `shouldBeBoxedAs` lives in `:holdfast-hallmark`, `vaultTest` deprecation.
- **GUIDE §11** (`holdfast/GUIDE.md:935-1013`): currently four raw kotlin.test patterns; add a leading subsection "§11.0 The `storeTest` harness" showing the same assertions via the harness and linking the module README; keep the raw patterns as the no-dependency alternative. **Adding fenced blocks here renumbers later exclusion indices** — every `holdfast/GUIDE.md#N` entry ≥ the insertion point in `snippet-exclusions.txt` must be re-indexed, and new blocks need twins (testing module is on doc-snippets' classpath already — compile+run them).
- Optionally add `holdfast-testing/README.md` to the doc-snippets tracked set (`build.gradle.kts` inputs + `DocSnippetDriftTest` doc list) with twins — recommended, it's cheap and the module is on the default build.
- Hallmark README covered by F22 (one file, written once).

Files: new `holdfast-testing/README.md`; `holdfast/GUIDE.md`; `doc-snippets/snippet-exclusions.txt`; new twin file(s) under `doc-snippets/src/test/kotlin/.../twins/`; `doc-snippets/build.gradle.kts` (+ drift test) if tracking. No API changes. Test: `:doc-snippets:test`.

### F28 — GUIDE TOC/file-layout stale; ROADMAP stale (M)

- TOC (`GUIDE.md:16-32`): add `15. Cross-Store Transactions` and `Appendix A — One-page cheatsheet`.
- §2 file layout (`GUIDE.md:112-123`): regenerate against the real tree — remove deleted `UUID.kt`; add `Atomic.kt`, `Contract.kt`, `Derived.kt`, `Effect.kt`, `Eventful.kt`, `EventfulStore.kt`, `EventfulSupport.kt`, `ExperimentalStoreApi.kt`, `Frame.kt`, `Snapshot.kt`, `StoreActionDsl.kt`, `StoreInternalApi.kt`, `bridge/`, `crypto/`, `middleware/`, `platform/`. (This block is fenced but plain-text layout — confirm it's not a ```kotlin fence; it isn't, so no index churn.)
- Internal version archaeology (`GUIDE.md:1147-1149` "The 1.1 surface", `1444` "Validation 0.3.0", cookbook "1.1" annotations at ~1419 and cheatsheet): full rewrite is ROADMAP-0.6.0-scoped. Lane-F minimal: retitle §14 to "The extended surface" and §14.11 to "Validation — the Hallmark modules", plus one framing sentence: "version numbers quoted below (1.0/1.1/0.3.0) are internal pre-release milestones preserved from the design archive; no such public releases exist." Defer the per-annotation sweep.
- `ROADMAP.md`: refresh the header line (date, "0.1.0 (unpublished)") and mark delivered 0.2.0 rows: hallmark decoupling ✅, rename completion ✅, context-overload removal ✅, TransactionResult ergonomics ✅, snippet-test module ✅, docs-truth sweep ✅ (MIGRATING.md exists), known-issues section ✅, wasmJs tier ✅; mark "release engineering rip-and-replace" as in-progress/done once P1-install lands. Keep undelivered rows (Central publish, billing).

Files: `holdfast/GUIDE.md`, `ROADMAP.md`. No API/tests. Do **last** — TOC/layout must reflect all other lane edits.

## 2. Intra-lane ordering

1. **F25** — one-line CI edit, unblocks the gate running on everything after.
2. **F23** — hallmark code + tests + hallmark `apiDump` (needs sibling-repo mavenLocal ritual once; reuse for step 3's hallmark KDoc edits).
3. **F33** — core/coroutines/testing code changes + tests + `apiDump` for `:holdfast`, `:holdfast-coroutines`, `:holdfast-testing`. All API surface is now final for the lane.
4. **P1-install** — buildSrc/publish.yml/README-honesty (independent of 2-3, can run in parallel with them).
5. **F8 + F27** — single coordinated pass over all four changelogs + MIGRATING.md, folding in the new entries from steps 2-4 (frame reclassification, F23 gate, F33 API changes, publish rework) so each changelog is touched once.
6. **F22 + P1-module-readmes** — new READMEs + root README rows + GUIDE §14.11/§11 (documents the post-F23/F33 contracts); exclusion-index renumbering handled here.
7. **F26** — remaining drifted blocks/KDocs (most KDoc files were already touched in step 3 — fold where possible).
8. **F28** — GUIDE TOC/layout + ROADMAP, last.
9. Final gate: `./gradlew check` and `-Pholdfast.includeHallmark=true` `apiCheck` + `:doc-snippets:test`.

## 3. SHARED-FILE MANIFEST (cross-lane conflict detection)

**High-conflict (other lanes' doc-lockstep edits land here too):**
- `/home/user/holdfast/README.md` — F22, P1-install (badge/Install/Known-issues wording)
- `/home/user/holdfast/holdfast/GUIDE.md` — F8, F22, F26, F28, P1-module-readmes, F33 lockstep (§13/§14 listings for derived/middlewares/Cipher/action(name)/FrameObserver)
- `/home/user/holdfast/holdfast/CHANGELOG.md` — F8, F27, F33, P1-install
- `/home/user/holdfast/holdfast-coroutines/CHANGELOG.md` — F8, F27, F33(action name mirror)
- `/home/user/holdfast/holdfast-compose/CHANGELOG.md`, `/home/user/holdfast/holdfast-hallmark/CHANGELOG.md` — F27 (+F23 for hallmark)
- `/home/user/holdfast/MIGRATING.md` — F8, F33 (Cipher rename, derived Pair)
- `/home/user/holdfast/ROADMAP.md` — F28, P1-install
- `/home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Store.kt` — F33 (removeMiddleware, action(name), CoroutineName) — **likely also edited by concurrency/core-bug lanes; highest merge risk of any code file**
- `/home/user/holdfast/holdfast/api/holdfast.api` + `holdfast.klib.api` (and coroutines/testing/hallmark api dirs) — any lane doing API work collides here; regenerate via `apiDump` after merge rather than hand-merging
- `/home/user/holdfast/.github/workflows/ci.yml` — F25 (other lanes may add jobs/tasks)
- `/home/user/holdfast/CLAUDE.md` — P1-install (stale "publish.yml known broken" sentence)
- `/home/user/holdfast/doc-snippets/snippet-exclusions.txt` + `doc-snippets/src/test/kotlin/com/vynatix/holdfast/snippets/twins/**` — any lane editing tracked docs; positional indices make this file conflict-prone semantically even without textual conflicts

**Lane-F-exclusive (low risk):** `.github/workflows/publish.yml`; `buildSrc/src/main/kotlin/holdfast.publish{,.sonatype}.gradle.kts`; `buildSrc/build.gradle.kts`; `gradle/libs.versions.toml` (shared if other lanes bump deps); `BoxedHandle.kt`, `BoxedState.kt`; new files `holdfast-hallmark/README.md`, `holdfast-testing/README.md`; KDoc-only edits to `Derived.kt`, `Frame.kt`, `Effect.kt`, `Eventful.kt`, `EventfulStore.kt`, `crypto/Cipher.kt`, `crypto/EncryptingTransformer.kt`, `middleware/LoggingMiddleware.kt`, `middleware/ValidationMiddleware.kt`, `ComposeBindings.kt`, `SuspendingFileSystemKvStore.kt`, `SuspendAction.kt`, `Atomic.kt`, `SuspendAtomic.kt`, `Recorder.kt`, `StoreHandle.kt`, `StoreHandleGroup.kt`, `StoreTestScope.kt`, `holdfast-hallmark-coroutines/README.md` — exclusive *unless* a core-bug lane touches the same files (Frame/Atomic/SuspendAtomic are plausible overlaps for a frame-semantics lane; Recorder/StoreHandle for a testing lane).

## 4. Risk notes + recommended defaults

- **P1-install default: adopt vanniktech** (see rationale above). Fallback if plugin/Gradle-9.5 friction appears: keep vanniktech for signing+Central and drop the homegrown sonatype repo block entirely; never ship the "point publish.yml at `publishAllPublicationsToSonatypeRepository`" option — it fails at upload time by construction. Keep the README honest ("not yet on Central") until a release actually lands; don't let the badge lie again (ROADMAP: "honesty is the brand repair for the badge that lied").
- **F33 0.x-break defaults:** DO now — `DerivedState` return type, `Cipher`→`StoreCipher` (with deprecated typealias), `FrameObserver` params (experimental surface), `removeMiddleware`, `action(name=)`. DEFER/waive — `TransactionResult` naming, `middlewares()` rename. Everything shipped now rides the 0.2.0 "one big break" window with zero external consumers; after first Central publish these get 10x more expensive.
- **Changelog frozen-archive rule:** F27's dead-path fixes are done via added framing sections, not edits to frozen entries; if the maintainer prefers literal path fixes, confine them to the trailing "See `vault/CHANGELOG.md`" pointer lines and label the commit "(mechanical)". Never touch anything below core's 0.1.0 divider.
- **Snippet-index fragility:** GUIDE §11 insertion (P1-module-readmes) renumbers `holdfast/GUIDE.md#N` exclusions for all N after the insertion point; do all fence-adding GUIDE edits in one commit with a full exclusions re-index, and run `:doc-snippets:test` locally before pushing. Prefer editing inside existing fences elsewhere (F26/F8 do).
- **hallmark apiDump environment:** F23's dump requires cloning `vynatix/hallmark` at the CI-pinned ref (`79282128…`), `./gradlew publishToMavenLocal` there, then `-Pholdfast.includeHallmark=true apiDump` here. Budget this ritual once.
- **`FrameObservers.clear()` in `storeTest` teardown** clears observers a test registered *outside* the harness too — that's the documented registry hygiene contract (Frame.kt KDoc), but call it out in the changelog entry.
- **ktlint/context-parameters:** F23 keeps context syntax in `BoxedHandle.kt`, already ktlint-excluded; any *new* file using `context(...)` needs the per-module ktlint exclusion (CLAUDE.md) — the plan adds none.
- **apiCheck is the tripwire:** every F33/F23 change must land with regenerated dumps in the same commit or CI fails; hand-merging `.api` files across lanes is error-prone — designate lane F to re-run `apiDump` after cross-lane merges.

## 5. Sizes

| Finding | Size | Type |
|---|---|---|
| F25 | S | CI |
| F8 | S | docs/changelog (+2 comment-only code lines) |
| F27 | M | changelog |
| F26 | M | docs/KDoc (gate extension optional +M) |
| F28 | M | docs |
| F22 | M | docs (new README) |
| P1-module-readmes | M | docs (+ twin work) |
| F23 | M | **code** + tests + apiDump |
| F33 | L | **code** bundle + tests + apiDump + doc lockstep |
| P1-install | L | build infra + workflow + docs |

### Critical Files for Implementation
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Store.kt
- /home/user/holdfast/holdfast-hallmark/src/commonMain/kotlin/com/vynatix/holdfast/hallmark/BoxedHandle.kt
- /home/user/holdfast/buildSrc/src/main/kotlin/holdfast.publish.sonatype.gradle.kts
- /home/user/holdfast/holdfast/CHANGELOG.md
- /home/user/holdfast/holdfast/GUIDE.md