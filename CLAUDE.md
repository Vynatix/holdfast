# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Holdfast — transactional state containers for Kotlin Multiplatform (Maven group `com.vynatix`). Three-layer naming, all intentional: the **brand/repo/artifacts** are `holdfast*`, the **package** is `com.vynatix.holdfast`, but the **central class** is `Store<Self : Store<Self>>` (renamed Vault → Holdfast → Store; see `.git-blame-ignore-revs`). New code and docs use `Store*` class names; "holdfast" stays in artifact/package/brand names.

Targets: Android, JVM, iosArm64 + iosSimulatorArm64, wasmJs (wasmJs only on `:holdfast`, `:holdfast-coroutines`, `:holdfast-compose`). JDK 21, Kotlin 2.3.21, Gradle 9.5.

## Commands

```bash
./gradlew check                      # everything CI gates: tests, apiCheck, detekt, ktlintCheck
./gradlew :holdfast:jvmTest --tests "com.vynatix.holdfast.TransactionTest"   # one class
./gradlew :holdfast:jvmTest --tests "*.TransactionTest.someMethod"           # one method
./gradlew detekt ktlintCheck         # static analysis; format with: ./gradlew ktlintFormat
./gradlew :<module>:detektBaseline   # re-pin detekt baseline after deliberate cleanups
./gradlew apiDump                    # after ANY public-API change; commit <module>/api/* dumps
./gradlew :holdfast:dokkaGenerate    # docs (Dokka V2 — `dokkaHtml` no longer exists)
./gradlew publishToMavenLocal -Pholdfast.version=0.1.0
```

Per-target test tasks: `jvmTest`, `testAndroidHostTest` (AGP-KMP host-test naming — not `testDebugUnitTest`), `iosSimulatorArm64Test` (macOS host only). wasmJs test tasks are force-disabled on `:holdfast`/`:holdfast-coroutines` (tests use `runBlocking`/`newSingleThreadContext`, absent on wasm) and never run in CI.

apiDump gotcha (Linux hosts): binary-compatibility-validator cannot build Apple-target klib ABI dumps on Linux. Modules WITH a wasmJs target (`:holdfast`, `:holdfast-coroutines`) infer the ios entries from the wasm klib, so their `apiDump`/`apiCheck` work everywhere. `:holdfast-testing` has no wasm target, so on Linux its `iosArm64ApiInfer` task fails `apiDump` outright and its klib `apiCheck` silently no-ops (ubuntu CI therefore never verifies `holdfast-testing.klib.api` — only the `api/jvm` dump). Regenerate that module's klib dump on a macOS host. The committed `holdfast-testing.klib.api` was last updated BY HAND (2026-07, cross-store frame matchers: `StoreHandleGroup`/`and`/`shouldCommitTogether`/`shouldNotCommitTogether`/`committedFrameIds`) — run `:holdfast-testing:apiDump` next time a macOS host touches the module and commit any correction.

Lint gotchas: detekt findings are baselined per module (`<module>/detekt-baseline.xml`) — rules stay active for new code. Files using K2 `context(name: Type)` syntax are excluded from ktlint (it can't parse them): `BoxedHandle.kt`; new context-parameter files need the same per-module `ktlint { filter { exclude(...) } }` treatment. Do NOT add `context(scope: CoroutineScope)` overloads next to default-param forms: inside any coroutine body the implicit `CoroutineScope` receiver satisfies the context parameter and silently captures the ambient scope (the `asStateFlow`/`bridge`/`suspendingBridge` overloads were removed for exactly this — issue #5).

CI (`.github/workflows/ci.yml`, push/PR to main) runs per module: `jvmTest` + `testAndroidHostTest` + `apiCheck` + `dokkaGenerate` + detekt/ktlint on ubuntu, `iosSimulatorArm64Test` + `jvmTest` on macos-15. **Known broken as written (verify before trusting/copying):** publish.yml calls `publishAndReleaseToMavenCentral` (vanniktech plugin — not applied anywhere; the build's own task is `publishAllPublicationsToSonatypeRepository`).

The hallmark modules (`:holdfast-hallmark`, `:holdfast-hallmark-coroutines`) are excluded from the default build because their `com.vynatix:hallmark*:0.1.0` deps only resolve via `mavenLocal()`. To include them, build/publish the sibling [hallmark repo](https://github.com/vynatix/hallmark) to `~/.m2` first, then run Gradle with `-Pholdfast.includeHallmark=true` (CI does exactly this). The `shouldBeBoxedAs` matcher lives in `:holdfast-hallmark`, not `:holdfast-testing`, for the same reason.

## Module graph (all project deps are `api`)

```
:holdfast                        core kernel — deps: kotlinx-coroutines-core (api), atomicfu (impl) only
├─ :holdfast-coroutines          suspendAction / suspendAtomic / asFlow / SuspendingBridge
├─ :holdfast-compose             collectAsState, rememberDisposable (only Compose module)
├─ :holdfast-hallmark            ValidatingTransformer, Store.boxed{}, BoxedCodec → external hallmark lib
├─ :holdfast-hallmark-coroutines suspendValidateAndMutate (uses coroutines + hallmark modules)
└─ :holdfast-testing             storeTest{} harness; core's own commonTest depends on it (deliberate test-only cycle)
```

Build logic lives in `buildSrc/src/main/kotlin/holdfast.*.gradle.kts` convention plugins: `kmp.library` (base targets + `-Wextra -Xcontext-parameters -Xexpect-actual-classes`), `kmp.jvm`/`kmp.wasmJs` (opt-in targets), `compose.multiplatform`, `quality` (detekt + ktlint), `abi` (binary-compatibility-validator incl. klib), `dokka`, `publish`(+`.sonatype`). Versions in `gradle/libs.versions.toml` (re-imported by buildSrc).

## Core architecture (`holdfast/src/commonMain/.../holdfast/`)

The unit of consistency is the transaction; everything below is contractual, not incidental:

- **States are lazily registered**: `val x by state { init }` creates the backing `MutableState` on first delegate read — `snapshot()`/`properties` miss never-read delegates.
- **`store action { }` buffers, never writes**: the whole action (including observer fanout) runs under the store's `transactionLock` (a reentrant spin-yield `StoreLock` — a slow observer blocks every other action on that store). `mutate` stages post-`Transformer.set` raw values into `Transaction.pendingWrites`. The owner thread gets read-your-own-writes through the savepoint chain; other threads only ever see committed values. Nested actions are savepoints (inner commit merges into parent; outer rollback discards all).
- **Rollback never touches state and never re-runs `Transformer.set`** — load-bearing for asymmetric transformers (encryption). Same reason snapshots hold raw values and `restore()` goes through `stagePendingRaw` (re-routing it through `mutate` would double-encrypt).
- **Commit fanout order is a contract**: observers → bridge publish → event drain. `:holdfast-coroutines`' `suspendAction` interposes between these phases via `@StoreInternalApi` hooks; reordering breaks it.
- **Middleware: last-registered is outermost** (its `started` fires first, `completed`/`error` last). `onTransactionCompleted` runs after the body but *before* commit — throwing there rolls back.
- **Bridges inbound (`applyFromBridge`) bypass transactions and middleware entirely** and deliberately don't re-publish (loop prevention). `mutate` outside an action synthesizes a one-shot action so middleware/observers always see committed semantics.
- **`derived()` recomputes via the `postCommit` queue**, drained only at top-level action exit (recomputing inline would mutate `pendingWrites` mid-iteration). `atomic(vararg stores)` is in-memory 2PC, lock-ordered by per-store `lockOrderKey`.
- **The cross-module boundary is `@StoreInternalApi`** (ERROR-level opt-in), not `internal` — companion modules opt in; everything public and *not* annotated is frozen by `apiCheck`. Every new public `Store` entrypoint must call `checkNotDisposed()`; cold companion APIs (`asFlow`, `first`, …) check `isDisposed` before subscribing.
- **wasmJs is single-threaded by assumption**: `currentThreadId()` returns `0` for everyone there — any new thread-identity logic must tolerate this. Expect/actual pairs live in `platform/Threading` and `bridge/FileSystemKvStore` (custom intermediate source sets `jvmAndAndroidMain`/`jvmAndAndroidHostTest` share JVM+Android actuals).
- `Store.Companion.defaultScope` is CAS-settable **once per process** — setting it in a test poisons every later test in that process.

Package layering: root kernel depends only on `platform`; `middleware/`, `bridge/`, `crypto/` are plug-ins over root contracts and never import each other. Grep trap: `middleware/HallmarkMiddleware.kt` contains `class ValidationMiddleware`. The public "vault" trio is renamed (`owningVault`→`owningStore`, `bindVault`→`bindStore`, `vaultTest`→`storeTest`); the old names survive as WARNING-level deprecated aliases for one minor — don't remove them early, and don't use them in new code. Remaining lowercase "vault" prose/internals are pre-rename residue.

## Testing harness (`:holdfast-testing`)

Entry point is `storeTest { }` (`vaultTest` is a deprecated alias; `holdfastTest` never existed — stale KDoc mentions it). Inside, `track(store)` (or just using `store.action {}` in scope — auto-tracks) yields a `StoreHandle` exposing a `timeline` of `StoreEvent`s with infix matchers (`shouldFireInOrder`, `shouldHavePublished`, `shouldBeSuccess`/`shouldBeError`, …). Every `TransactionResult.Error` a handle returns must be consumed by a matcher (or `consumeAllPendingErrors()`) or teardown fails the test.

## Conventions

- Open an issue before submitting a PR (CONTRIBUTING.md) — undiscussed PRs may be closed.
- 0.x pre-stable; release version comes from the git tag via `-Pholdfast.version` (publish.yml), not from any committed file.
- Changelog: Keep-a-Changelog format; add entries under `## [Unreleased]` in `holdfast/CHANGELOG.md` (and per-module changelogs where they exist). Entries below 0.1.0 are a frozen internal design archive using old Vault naming — never rewrite them; same for `*-DESIGN.md` files.
- Mechanical renames go in two commits (file renames with no content changes, then content changes marked "(mechanical)") and the content commit's SHA gets appended to `.git-blame-ignore-revs`.
- API-mirroring docs updated in lockstep with public-API changes: root `README.md` modules table, `holdfast/README.md`, `holdfast/GUIDE.md` API-reference sections, module READMEs.
- Demo/sample apps live in the separate `vynatix/banking-demo` repo — don't add demos here.
