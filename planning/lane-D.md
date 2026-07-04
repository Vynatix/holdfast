All investigation done. Here is the implementation plan for workstream D.

# Lane D — `:holdfast-compose` Implementation Plan

Module surface is tiny: one source file (`ComposeBindings.kt`, 63 lines), one compile-smoke test, two API dumps (jvm + klib; targets android/jvm/ios×2/wasmJs). All three findings land in the same handful of files, so this lane is inherently sequential.

---

## 1. Per-finding plans

### F19 — `collectAsState`: dead `Store` receiver + delegate-shadowing KDoc bug (size: S)

**Confirmed:** at `ComposeBindings.kt:34` the receiver `V : Store<V>` is never used in the body (lines 35–38 only touch `state`), forcing the `store.collectAsState(store.count)` double-mention. The KDoc example (lines 27–29) has the exact shadowing bug the README already fixed at `holdfast-compose/README.md:29-31` (`this.count` qualification): inside `store.action { count update ... }` the local `val count by ...` delegate shadows the `State<Int>` property, so the example does not compile. It also uses old naming (`CounterVault`). Notably, **both changelogs already document the surface as `State<T>.collectAsState()`** (`holdfast-compose/CHANGELOG.md:16`, `holdfast/CHANGELOG.md:603`) — the code diverged from its published contract, which settles the API-shape question.

**Fix:**
1. Add the primary form:
   ```kotlin
   @Composable
   fun <T : Any> State<T>.collectAsState(): androidx.compose.runtime.State<T> =
       produceState(initialValue = value, this) {
           val disposable = this@collectAsState effect { value = this }
           awaitDispose { disposable.dispose() }
       }
   ```
   (Mind the two receivers: inner `this` in the `effect` handler is the new `T`; `value =` targets `ProduceStateScope`.)
2. Keep the old `V.collectAsState(state)` as a WARNING-level `@Deprecated(ReplaceWith("state.collectAsState()"))` shim delegating to the new form — matching the repo's "deprecated alias for one minor" convention (the `owningVault`/`bindVault` precedent in CLAUDE.md). No overload ambiguity: arities differ.
3. Rewrite KDoc example: `val count by store.count.collectAsState()`, `CounterStore` naming, and use the README's `this.count` qualification (keep the explanatory comment — the shadowing is a real user trap).

**Files:** `ComposeBindings.kt`; `ComposeBindingsCompileTest.kt` (call new form for both states; keep one `@Suppress("DEPRECATION")` call pinning source compat of the shim); apiDump (new entry added, old entry stays); `holdfast-compose/README.md` (Surface block lines 10–14 + both `collectAsState` example lines 24–25); `holdfast/GUIDE.md:1387` (§14.9 signature block); `holdfast-compose/CHANGELOG.md` under `## [Unreleased]` (Added + Deprecated).
**Not touched:** `holdfast/README.md:141`, root `README.md:83`, `holdfast/CHANGELOG.md:603` — name-only mentions, all stay accurate (the last one *becomes* accurate).

**Tests:** compile-smoke only, per the documented module policy in `ComposeBindingsCompileTest.kt:12-21` (no Compose test harness in this module — do not add one).

---

### F18 — `rememberDisposable` keyed on the factory lambda (size: M)

**Confirmed:** `ComposeBindings.kt:58` is `remember(make) { make() }` — keyed on lambda identity. An unstable capture (any lambda recreated per recomposition, which is the common case for `{ store { status observeFrom channel } }`) yields a new `Disposable` every recomposition; `DisposableEffect(disposable)` then dispose/resubscribes each frame. KDoc lines 42–43 claim "runs once per `key`/`store`/etc. change" but no key param exists.

**Fix — API shape decision (see §4):** replace with
```kotlin
@Composable
fun rememberDisposable(vararg keys: Any?, make: () -> Disposable): Disposable {
    val disposable = remember(*keys) { make() }
    DisposableEffect(disposable) { onDispose { disposable.dispose() } }
    return disposable
}
```
- Zero keys ⇒ `remember` with empty key set ⇒ factory runs **once** on entering composition, disposed on leave. This makes every existing call site (`rememberDisposable { ... }`) source-compatible *and* silently fixes the churn.
- **Do not keep the old `(make: () -> Disposable)` overload**: Kotlin overload resolution prefers the non-vararg candidate, so `rememberDisposable { ... }` would keep binding to the old broken function, defeating the fix. Clean replace, binary break noted in changelog.
- Rewrite KDoc: factory runs on composition entry and again when any `key` changes; the returned Disposable is disposed on key change / composition exit; explicitly note the lambda is **not** a key and captures do not trigger resubscription.

**Files:** `ComposeBindings.kt`; `ComposeBindingsCompileTest.kt` (add a keyed call `rememberDisposable(v) { ... }` plus the existing keyless call); **apiDump — both files change** (`api/jvm/holdfast-compose.api` line 3 gains `[Ljava/lang/Object;`; `api/holdfast-compose.klib.api` line 10 gains `kotlin.Array<out kotlin.Any?>...`); `holdfast-compose/README.md` (Surface block + optionally a keyed example); `holdfast/GUIDE.md:1390`; `holdfast-compose/CHANGELOG.md` (Changed — signature + behavior: "no longer resubscribes when the factory lambda identity changes"; note binary incompatibility, source compatible).

**Tests:** compile-smoke (keyless + keyed forms). A real recomposition-behavior test requires a Recomposer/frame-clock harness the module deliberately excludes — out of scope per the module's stated testing contract.

---

### F20 — dispose contract undocumented on Compose entry points (size: S)

**Confirmed in core:** `Store.dispose()` (`holdfast/src/commonMain/.../Store.kt:174-180`) clears `_properties` and calls `shutdownSilently()` on every state — so a live `collectAsState` subscription just stops firing (UI freezes at last committed value, `produceState` stays parked in `awaitDispose`). Conversely `State.effect` (`Effect.kt:29`) throws bare `error("store disposed")` if subscribed after dispose — inside `produceState`'s producer coroutine, i.e. a runtime crash on composition entry.

**Fix — documentation only (recommended; see §4):**
1. `collectAsState` KDoc: add a "Lifecycle" paragraph — entering composition with a disposed store throws `IllegalStateException("store disposed")` from the producer; disposing while composed silently freezes the value; dispose only after dependent composables leave composition (e.g. from `onDispose`/ViewModel `onCleared`, ordered after the UI).
2. `rememberDisposable` KDoc: same note for factories that subscribe to a store (`observeFrom`, `effect`).
3. `holdfast-compose/README.md`: new short "Dispose contract" section after Examples.
4. `holdfast/GUIDE.md` §14.9: one sentence appended to the existing paragraph (lines 1392–1396).

**No code change, no apiDump.** Improving the bare `"store disposed"` message itself (store identity in errors) is a core-module P2 item per `USABILITY-ANALYSIS.md:113` and belongs to whichever lane owns `holdfast/` error vocabulary — do not fix `Effect.kt` from this lane.

---

## 2. Intra-lane ordering

1. **F19** — restructures `collectAsState` (adds primary form + deprecates old). Do first; it changes the most signatures.
2. **F18** — `rememberDisposable` re-signature.
3. **F20** — KDoc/README/GUIDE contract pass, written last against the *final* signatures so lifecycle docs land once.
4. Single closing pass: `./gradlew :holdfast-compose:apiDump`, then `./gradlew :holdfast-compose:allTests :holdfast-compose:apiCheck detekt ktlintCheck`; one consolidated `## [Unreleased]` block in `holdfast-compose/CHANGELOG.md`.

---

## 3. SHARED-FILE MANIFEST (exact files modified)

| File | Findings | Cross-lane conflict risk |
|---|---|---|
| `/home/user/holdfast/holdfast-compose/src/commonMain/kotlin/com/vynatix/holdfast/compose/ComposeBindings.kt` | F18, F19, F20 | Low — compose-only |
| `/home/user/holdfast/holdfast-compose/src/commonTest/kotlin/com/vynatix/holdfast/compose/ComposeBindingsCompileTest.kt` | F18, F19 | Low |
| `/home/user/holdfast/holdfast-compose/api/jvm/holdfast-compose.api` | F18, F19 (apiDump) | Low |
| `/home/user/holdfast/holdfast-compose/api/holdfast-compose.klib.api` | F18, F19 (apiDump) | Low |
| `/home/user/holdfast/holdfast-compose/README.md` | F18, F19, F20 | Low |
| `/home/user/holdfast/holdfast-compose/CHANGELOG.md` | F18, F19, F20 | Low |
| `/home/user/holdfast/holdfast/GUIDE.md` (§14.9 only, ~lines 1383–1396) | F18, F19, F20 | **HIGH** — GUIDE staleness is a known P2 other lanes may touch; edits confined to §14.9 to keep merges clean |

**Deliberately NOT modified** (verify at merge time no other lane assumes we do): `holdfast/README.md`, root `README.md`, `holdfast/CHANGELOG.md` (all name-only mentions, stay correct), `holdfast/src/**` (`Effect.kt` / `Store.kt` untouched — F20 is doc-only), `holdfast-compose/build.gradle.kts`, detekt baselines.

---

## 4. Risk notes + recommended defaults

- **F18 API shape (the decision point): add `vararg keys` and change default keying — recommended.** Rationale: (a) matches `remember`/`DisposableEffect`/`LaunchedEffect` idiom; (b) zero-keys = run-once is the only default that fixes the churn for existing call sites without any source change; (c) keeping the old `Function0`-only overload alongside is actively harmful — non-vararg wins overload resolution, so all existing `rememberDisposable { ... }` calls would keep the broken behavior. Trade-offs to record in CHANGELOG: **binary-incompatible** (source-compatible) signature change, and a semantic change for anyone who (accidentally) relied on lambda-identity resubscription — they must now pass explicit keys. Rejected alternative: mandatory first key (`key1: Any?, vararg keys`) à la `LaunchedEffect` — safer against "forgot the key" but breaks source compat for every current call site.
- **F19:** deprecate (WARNING + `ReplaceWith`), don't remove, per the repo's one-minor deprecation convention. apiDump will show both entries; that's expected.
- **F20:** keep doc-only. Adding an eager `isDisposed` pre-check with a friendlier message in `collectAsState` would require `@OptIn(StoreInternalApi)` on `owningStore` — legitimate for a companion module, but it duplicates a core error-vocabulary fix another lane may own; flag to coordinator instead of implementing.
- **CI/verification:** `apiCheck` will fail until `apiDump` is committed; wasmJs tests never run in CI (compile-smoke still compiles for wasm via `allTests`/klib dump). Stale-prose note (out of scope, worth a coordinator flag): `holdfast-compose/CHANGELOG.md:34-38` claims "Android + iOS only, no wasm" while the build applies `holdfast.kmp.wasmJs` and the klib dump lists wasmJs — frozen-changelog convention says don't rewrite old entries, so leave it.

## 5. Sizes

- F18: **M** (API shape change, dumps, behavior-change changelog)
- F19: **S** (mechanical restructure + deprecation shim + docs)
- F20: **S** (docs only)

### Critical Files for Implementation
- /home/user/holdfast/holdfast-compose/src/commonMain/kotlin/com/vynatix/holdfast/compose/ComposeBindings.kt
- /home/user/holdfast/holdfast-compose/src/commonTest/kotlin/com/vynatix/holdfast/compose/ComposeBindingsCompileTest.kt
- /home/user/holdfast/holdfast-compose/README.md
- /home/user/holdfast/holdfast-compose/api/jvm/holdfast-compose.api (plus sibling `.klib.api`)
- /home/user/holdfast/holdfast/GUIDE.md (§14.9)