I've verified all six findings against source. All evidence lines check out: `KvBridge.kt:33` (`runCatching { codec.decode(...) }.getOrNull()`), `SuspendingBridge.kt:186-192` (`putOrEmit` catches `Throwable` into `tryEmit`), the JVM apiDump showing `SuspendingKvBridge : com/vynatix/holdfast/Bridge` only (line 55) while only `$Awaiting` implements `SuspendingBridge` (line 63), the un-cancellable `init { scope.launch { for (value in saves) ... } }` drainer (`SuspendingBridge.kt:169-177`), the unconditional `runBlockingForInitialSeed` at `SuspendDerived.kt:80`, and the raw-value `distinct` comparison at `MutableState.kt:137`. Key contextual facts: `suspendingCommit` (`AsyncSerializer.kt:107-114`) dispatches on `is SuspendingBridge<*>` and `suspendAction` already wraps `suspendingCommit` in try/catch → `TransactionResult.Error`, so a rethrowing `publishAwaited` surfaces cleanly with no interpose changes. `Store.removeState(name)` already exists for F16's unregistration. Nothing is published to Maven Central (ROADMAP:3), so breaking ABI changes in 0.x are cheap.

---

# Lane C Implementation Plan — bridges + coroutines

## 1. Per-finding plans

### F12 — KvBridge silent decode-drop + overwrite (core, `bridge/`)

**Approach.** Add an optional `onDecodeError: ((encoded: String, cause: Throwable) -> Unit)? = null` constructor parameter to `KvBridge`. In `observe`, replace `runCatching{...}.getOrNull()` with an explicit try/catch that invokes the hook with the raw encoded payload (so callers can quarantine it before the next commit overwrites it), keeping silent-skip as the default behavior but documenting it loudly. Rewrite the class KDoc: (a) decode-failure behavior + the overwrite hazard, (b) that `publish` exceptions (encode/`kv.put`) propagate into commit fanout and surface as `TransactionResult.Error` with earlier fanout already applied — do not claim rollback.

- **Files:** `/home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/bridge/KvBridge.kt`
- **Tests:** new `/home/user/holdfast/holdfast/src/commonTest/kotlin/com/vynatix/holdfast/bridge/KvBridgeTest.kt` (uses `InMemoryKvStore`): corrupt payload → hook receives exact encoded string + cause, state stays at initializer; default-null → silent skip (pins current behavior); throwing `publish` → action returns `Error`.
- **API/docs:** apiDump (`holdfast/api/jvm/holdfast.api` + `holdfast.klib.api` — constructor signature changes since `KvBridge` is final with a public ctor and no `@JvmOverloads`). GUIDE §14.7 signature block (~line 1332), `holdfast/CHANGELOG.md` `[Unreleased]`. Existing doc-snippet twins (`GuideEncryptedCredentialTwin.kt`, `GuideSurfaceExamplesTwin.kt`) still compile — default param — no change needed.
- **Size: S**

### F13 — `publishAwaited` swallows errors; durability KDoc is false

**Approach.** Make the await-completion `publishAwaited` rethrow: `try { store.put(key, codec.encode(value)) } catch (t) { _errors.tryEmit(t); throw t }` (emit-then-throw keeps existing `errors`-flow collectors working). `suspendAction`/`suspendAtomic` already convert a `suspendingCommit` throw into `TransactionResult.Error` — no interpose change. The fire-and-forget paths (conflated drainer, and sync `publish` under `action{}`) must keep swallowing into `errors` (rethrowing inside a `defaultScope` launch = uncaught crash), so keep `putOrEmit` for those. Rewrite the `SuspendingBridge` interface KDoc and `Awaiting.publishAwaited` KDoc: the guarantee is **ordering + a surfaced error result**, not memory/disk atomicity — in-memory commit and observer fanout have already applied when persistence fails; the transaction reports `Error`, it does not roll back.

- **Files:** `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendingBridge.kt` (+ one-line KDoc truthing in `AsyncSerializer.kt` if wording references the old contract).
- **Tests:** extend `SuspendingBridgePublishAwaitedTest.kt`: failing `put` under `suspendAction` → `TransactionResult.Error` AND `errors` emission AND state committed in memory; failing `put` under sync `action` → `Success` + `errors` emission only.
- **API/docs:** no apiDump delta by itself. `holdfast-coroutines/README.md` (`publishAwaited suspends until persisted` prose, ~line 49-50), GUIDE ~line 1831 (§15 fanout description) and §14.8 coroutines block prose, `holdfast-coroutines/CHANGELOG.md` (behavior change — file under a Changed/Fixed heading, not Added).
- **Size: M**

### F14 — `SuspendingKvBridge` is not a `SuspendingBridge`; factory split

**Approach.** Merge `Awaiting` into its base: `SuspendingKvBridge<T>` itself implements `SuspendingBridge<T>` — `publishAwaited` = direct encode+put (rethrowing, per F13); sync `publish` keeps the conflated-channel path (conflation now applies only under sync `action`, which is where it matters). Delete the nested `Awaiting` class. `suspendingBridge()` returns `SuspendingKvBridge<T>`; deprecate `bridge()` (WARNING, `ReplaceWith("suspendingBridge(key, codec, scope)")`, matching the repo's one-minor alias convention) since the two factories become behaviorally identical. Fix the broken KDoc example in `SuspendingFileSystemKvStore.kt:25` (nonexistent `suspendBridge`) to use the surviving factory.

- **Files:** `SuspendingBridge.kt`, `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendingFileSystemKvStore.kt` (KDoc only).
- **Tests:** update `SuspendingKvBridgeTest.kt` (factory return types; assert a `bridge()`/`suspendingBridge()` product **is** `SuspendingBridge`; conflation still holds under sync `action`; under `suspendAction`, publish is now awaited — new assertion). `SuspendingBridgePublishAwaitedTest.kt` references to `Awaiting` updated.
- **API/docs:** apiDump — `holdfast-coroutines/api/jvm/holdfast-coroutines.api` lines 38-41/55/63 + klib dump: `Awaiting` removed (BREAKING), `suspendingBridge` return type changed, `SuspendingKvBridge` gains the interface. `holdfast-coroutines/README.md` signature block (lines 30-50), GUIDE §14.8 block (~1353-1361), `MIGRATING.md` (breaking entry: `Awaiting` → `SuspendingKvBridge`), `holdfast-coroutines/CHANGELOG.md` under a BREAKING heading. Root `README.md` modules table only if it names these types (verify at edit time).
- **Size: M–L**

### F15 — leaked infinite drainer; no dispose

**Approach.** `SuspendingKvBridge` implements `Disposable`. Capture the drainer `Job` from the `init` launch; track outstanding load jobs from `observe` in a small lock-guarded set (removed on completion). `dispose()`: idempotent atomic flag → `saves.close()` (lets the drainer drain the last conflated value, then the `for` loop exits) → cancel load jobs → as a backstop cancel the drainer job after close. Post-dispose `publish` returns `false` (the KDoc already reserves `false` for shutdown); post-dispose `observe` returns a no-op `Disposable` without launching. Document that `state bridge null` detaches inbound only and that the bridge owner must call `dispose()`.

- **Files:** `SuspendingBridge.kt`.
- **Tests:** extend `SuspendingKvBridgeTest.kt` (or new `SuspendingKvBridgeDisposeTest.kt`): no `put` reaches the store for publishes after dispose; `publish` returns false; dispose is idempotent; in-flight load-on-attach cancelled; last pre-dispose value still drains.
- **API/docs:** apiDump (adds `Disposable` supertype + `dispose()` — binary-compatible addition). README/GUIDE lifecycle paragraph, `holdfast-coroutines/CHANGELOG.md`.
- **Size: M**

### F16 — `suspendDerived` runBlocking seed (wasmJs crash) + never-unregistered backing state

**Approach.** Add an overload `V.suspendDerived(vararg sources: State<*>, initial: T, compute: suspend V.() -> T)` that seeds the backing state with `initial` (no `runBlocking`) and immediately launches one async first compute on `store.scope` (tracked in `latestJob`); extract the shared subscription/dispose machinery into a private helper both overloads call. Keep the existing overload but rewrite its KDoc honestly: crashes on wasmJs, can deadlock single-threaded dispatchers, prefer the `initial` overload (no deprecation — matches the audit's P2 framing). In the composite `Disposable`, after disposing subscriptions and cancelling `latestJob`, call `runCatching { self.removeState(name) }` so the synthetic `__suspendDerived_N` state leaves the registry (swallow: store may be disposed or a racing recompute may hold pending writes — rare, benign leak).

- **Files:** `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendDerived.kt`; optionally sharpen the throw message in `src/wasmJsMain/.../platform/RunBlocking.kt` to point at the new overload (advice is currently unactionable).
- **Tests:** extend `SuspendDerivedTest.kt`: `initial` overload shows `initial` before first compute lands and computed value after `advanceUntilIdle`; recompute-on-source-commit parity with old overload; dispose removes the backing state (visible via `snapshot()`/`properties` no longer containing `__suspendDerived_*`); dispose after store-dispose doesn't throw.
- **API/docs:** apiDump (new overload in `SuspendDerivedKt`; additive). `holdfast-coroutines/README.md` line ~30-33 signature block, GUIDE §14.8 (~1353), `holdfast-coroutines/CHANGELOG.md`. Note: GUIDE's coroutines listing is fence `GUIDE.md#42` in `doc-snippets/snippet-exclusions.txt` — edit in place, don't add/remove ```kotlin fences or the index-based exclusion list goes stale and `DocSnippetDriftTest` fails.
- **Size: M**

### F30 — `distinct=true` + random-IV cipher never dedups

**Approach.** Doc-only (matches audit severity). One paragraph in three places: `Cipher` KDoc (a non-deterministic cipher — the very AES-GCM-with-per-value-IV it recommends — makes `distinct = true` inert because dedup compares post-`Transformer.set` raw values, i.e. ciphertexts), `EncryptingTransformer` KDoc, and the `distinct` param KDoc on `Store.state(...)` (~`Store.kt:512`). Explicitly reject the code alternative (comparing logical pre-set values) in the plan: it would run `transformer.get` per commit and break the asymmetric-transformer/no-double-decrypt invariants.

- **Files:** `Cipher.kt`, `EncryptingTransformer.kt`, `Store.kt` (KDoc lines only — see risk note), GUIDE crypto section (~line 1246).
- **Tests:** optional pinning test in `CryptoTest.kt` (nondeterministic test cipher + `distinct = true` → observer fires on equal logical values), documenting current behavior.
- **API/docs:** no apiDump change. `holdfast/CHANGELOG.md` optional (docs-only).
- **Size: S**

## 2. Intra-lane ordering constraints

1. **F14 → F13 → F15** — all three rewrite `SuspendingBridge.kt`; the class shape F14 produces is where F13's rethrow and F15's `dispose()` live. Land as one sequenced series (three commits, one PR) to avoid self-conflicts. F13's tests assume F14's merged class.
2. **F12, F16, F30** are mutually independent and independent of the F13-F15 series.
3. Run `./gradlew apiDump` once after the coroutines series and once after F12 (or one dump at the end); commit dumps with the change that caused them so `apiCheck` stays green per commit.
4. `MIGRATING.md` + BREAKING changelog entry land with F14, not before.

## 3. SHARED-FILE MANIFEST (complete — for cross-lane conflict detection)

**Source (will modify):**
- `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/bridge/KvBridge.kt` (F12)
- `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/crypto/Cipher.kt` (F30)
- `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/crypto/EncryptingTransformer.kt` (F30)
- `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Store.kt` (F30 — KDoc lines ~505-520 only; **high cross-lane contention, see risks**)
- `holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendingBridge.kt` (F13/F14/F15)
- `holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendDerived.kt` (F16)
- `holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendingFileSystemKvStore.kt` (F14 — KDoc example only; **docs lane also targets this exact KDoc**)
- `holdfast-coroutines/src/wasmJsMain/kotlin/com/vynatix/holdfast/coroutines/platform/RunBlocking.kt` (F16 — error-message text, optional)
- `holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/AsyncSerializer.kt` (F13 — KDoc sentence only, may be skipped)

**Tests:**
- NEW `holdfast/src/commonTest/kotlin/com/vynatix/holdfast/bridge/KvBridgeTest.kt`
- `holdfast/src/commonTest/kotlin/com/vynatix/holdfast/crypto/CryptoTest.kt` (F30, optional)
- `holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendingBridgePublishAwaitedTest.kt`
- `holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendingKvBridgeTest.kt`
- NEW `holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendingKvBridgeDisposeTest.kt`
- `holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendDerivedTest.kt`

**API dumps (regenerated):**
- `holdfast/api/jvm/holdfast.api`, `holdfast/api/holdfast.klib.api`
- `holdfast-coroutines/api/jvm/holdfast-coroutines.api`, `holdfast-coroutines/api/holdfast-coroutines.klib.api`

**Docs (lockstep):**
- `holdfast/GUIDE.md` (§14.7 ~1332, §14.8 coroutines block ~1340-1365, crypto prose ~1246, §15 prose ~1831)
- `holdfast-coroutines/README.md` (~lines 25-55)
- `holdfast/CHANGELOG.md`, `holdfast-coroutines/CHANGELOG.md`, `MIGRATING.md`
- root `README.md` (only if its modules table names `SuspendingBridge`/`suspendDerived` — check at edit time)
- `doc-snippets/snippet-exclusions.txt` + twins under `doc-snippets/src/test/.../twins/` (only if GUIDE fence count changes — plan is to avoid that)
- Possibly `holdfast/detekt-baseline.xml` / `holdfast-coroutines/detekt-baseline.xml` (only if new code trips active rules; prefer `@Suppress` at site)

## 4. Risk notes + recommended defaults

- **Should `SuspendingKvBridge` implement `SuspendingBridge`? Yes.** Binary compat is a non-issue: nothing is on Maven Central (ROADMAP:3; publish.yml is broken), and the repo is explicitly 0.x pre-stable. Adding the interface to the base class is even JVM-binary-compatible; the breaking parts are removing `Awaiting` and changing `suspendingBridge()`'s return type — both fine pre-publish, but must be filed as BREAKING in the changelog and `MIGRATING.md` (the audit already dinged the frame work for burying a behavior break under "Added").
- **Semantic shift from F14:** bridges from the deprecated `bridge()` factory become awaited under `suspendAction` (they now match the `is SuspendingBridge` interpose). That is strictly *more* durable but adds per-commit latency and drops cross-commit conflation under `suspendAction`. Recommended default: accept and document; do not add a `Boolean` mode flag unless a reviewer demands it — one class, one truth.
- **F13 must not claim rollback.** After a persistence throw, in-memory state is committed and observers have fired; the honest contract is "error surfaced as `TransactionResult.Error`". Coordinate wording with the workstream fixing commit-fanout partial-commit reporting in `Transaction.kt` (USABILITY still-open #5) — same phenomenon, keep one vocabulary.
- **Never rethrow on the fire-and-forget paths.** `Store.defaultScope` is `SupervisorJob() + Dispatchers.Default`; a throw inside `scope.launch` reaches the platform uncaught handler (process death on Android). The errors-flow swallow stays for drainer/sync-publish; only the awaited path throws.
- **`Store.kt` contention (F30):** every other lane likely touches `Store.kt`. The F30 edit there is 3-4 KDoc lines on the `state(...)` `distinct` param; if conflict detection flags it, drop it from this lane and keep `Cipher.kt`/`EncryptingTransformer.kt`/GUIDE — sufficient per the audit ("one KDoc sentence").
- **`SuspendingFileSystemKvStore.kt` KDoc** is a known docs-lane target (broken `suspendBridge` example). Whoever lands second rebases; the fix content should be agreed (use `suspendingBridge(...)`).
- **Doc-snippets index fragility:** `snippet-exclusions.txt` entries are index-based (`GUIDE.md#42` etc.). Any GUIDE edit that adds/removes a ```kotlin fence shifts every later index and fails `DocSnippetDriftTest`. Edit existing fences in place only.
- **F16 `removeState` race:** an in-flight recompute can hold pending writes when dispose runs; `removeState` then throws and the `runCatching` leaves the state registered (rare, bounded leak of one entry). Acceptable; note in KDoc.
- **F15 close-vs-cancel:** `saves.close()` lets the final conflated value persist (graceful); a hard `cancel` would drop it. Recommended default: close-then-cancel-backstop, and pin the "last value still drains" behavior in a test.
- **wasmJs discipline:** all new coroutines-module code must avoid `runBlocking`/`newSingleThreadContext` outside platform actuals — the module compiles for wasmJs even though its tests don't run there.

## 5. Size summary

| Finding | Size |
|---|---|
| F12 KvBridge decode hook | S |
| F13 publishAwaited rethrow + KDoc truth | M |
| F14 merge Awaiting / implement SuspendingBridge | M–L |
| F15 Disposable drainer lifecycle | M |
| F16 suspendDerived(initial=) + unregister | M |
| F30 distinct+cipher KDoc | S |

Whole lane ≈ one focused multi-commit PR for `:holdfast-coroutines` (F14→F13→F15, plus F16) and one small core PR (F12+F30), or a single PR if the repo prefers lane-sized changes.

### Critical Files for Implementation
- /home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendingBridge.kt
- /home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendDerived.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/bridge/KvBridge.kt
- /home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/AsyncSerializer.kt (interpose — read-side dependency; verifies F13/F14 need no core changes)
- /home/user/holdfast/holdfast-coroutines/api/jvm/holdfast-coroutines.api