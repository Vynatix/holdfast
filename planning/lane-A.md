I've completed the investigation. All findings verified against source (`b404f62`-era tree). Below is the implementation plan for Lane A.

---

# Lane A implementation plan — frame/atomic concurrency

## 1. Per-finding fix plans

### F1 (blocker) — blocking `atomic()` not serialized against in-flight suspending work — **size M**

**Approach.** In `Atomic.kt`'s `acquireAndRun`, bracket each store's acquisition with `v.asyncSerializer?.blockingAcquire()` **before** `v.runUnderLock { ... }` and release in a `finally` after the lock scope exits (so the post-commit drain, which runs inside `runUnderLock`'s finally, still happens under the serializer — matching `action`'s bracket at `Store.kt:386-392`). Acquisition stays per-store in `lockOrderKey` order, consistent with `suspendAtomic`'s mutex order, so no lock inversion is introduced. Prerequisite: make `MutexSerializer.blockingAcquire`/`blockingRelease` (in `holdfast-coroutines/.../AsyncSerializer.kt`) **thread-reentrant** — track a `@Volatile` holder-thread-id (`platform.currentThreadId()`; safe on wasmJs where everyone is `0` because wasmJs is single-threaded) plus a holder-only reentry count, because inside an atomic body `a.action { }` re-calls `blockingAcquire()`, and today `tryLock(SPIN_OWNER)` on a mutex already held by `SPIN_OWNER` **throws** kotlinx's raw `IllegalStateException` — this is also a latent pre-existing bug for `store.action { store.action { } }` on any store that has ever run a `suspendAction` (both calls use the shared `SPIN_OWNER` at `AsyncSerializer.kt:35`). The alternative fail-fast (`throw when suspendingOwner != null`) is racy and rejects legal concurrent use — do not take it.

- **Files:** `holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Atomic.kt`, `holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/AsyncSerializer.kt`.
- **Tests:** new `holdfast-coroutines/src/commonTest/.../BlockingAtomicSuspendInteropTest.kt`: (a) suspendAction gated open mid-body via `CompletableDeferred`, concurrent blocking `atomic(store)` from another thread must block until suspendAction commits, with writes landing in the correct transactions (the contamination repro); (b) nested-blocking-action reentrancy regression after a suspendAction has installed the serializer; (c) `atomic` inside `action` savepoint path still works with serializer installed. Core-side: add a fake-`AsyncSerializer` unit test to `AtomicTest.kt` asserting acquire/release bracketing in lock order (no coroutines dep needed — `Store.AsyncSerializer` is core).
- **API/docs:** no public-API change (no apiDump). Document the remaining first-install race (serializer is installed lazily on the first-ever `suspendAction`; an `atomic` racing that exact first call has the same guarantee as `action` — none) in `Atomic.kt` KDoc. Changelog entries in `holdfast/CHANGELOG.md` and `holdfast-coroutines/CHANGELOG.md`.

### F2 (major) — nested `atomic(c)` escapes Strict enrollment — **size M**

**Approach.** Extend `verifyFrameNesting` (`Frame.kt:277-308`) — shared by `atomic` and `suspendAtomic` — with a third check: a store with `enclosing.enrollingFrame(store) == null` (introduced, not enrolled anywhere in the chain) throws `UnenrolledStoreException` unless `enclosing.policy.allowUnenrolled`, with a message teaching "enroll it in the outermost frame, or pass `FramePolicy.AllowUnenrolled` to run it as an independent (REQUIRES_NEW-style) frame". This makes `AllowUnenrolled` the single, greppable escape for both bare writes and nested-frame introductions. Behavior change: the currently-green `FrameTest.nestedFrameIntroducingHigherKeyStoreIsAllowed` (`FrameTest.kt:291-303`) must gain `policy = FramePolicy.AllowUnenrolled` on the outer frame.

- **Files:** `Frame.kt`; tests `FrameTest.kt`, `SuspendAtomicFrameTest.kt` (mirror).
- **Tests:** Strict outer + nested `atomic(newStore)` → `UnenrolledStoreException`; `AllowUnenrolled` outer + nested frame commits independently and outer rollback leaves it committed (pins the documented REQUIRES_NEW semantics); same pair for `suspendAtomic`.
- **API/docs:** no signature change, no apiDump. KDoc on `atomic` (Nesting paragraph, `Atomic.kt:58-63`) and `suspendAtomic`; `GUIDE.md` §15.4; behavior-change entry in both changelogs and a `MIGRATING.md` frame section.

### F3 (major) — "enclosing rollback discards everything" is false for introduced stores — **size S (docs default; M if guard added)**

**Approach.** Correct `Atomic.kt:58-63` KDoc and `GUIDE.md:1843-1848` (§15.4): savepoint semantics apply only to **shared** stores (those with an enclosing transaction on this thread); stores getting a **fresh root** commit at frame exit and do NOT roll back with an enclosing action/frame. With F2 landed, the `atomic`-inside-`atomic` variant only arises under explicit `AllowUnenrolled`; the remaining silent shape is `c.action { atomic(a, b, c) { … } }` + later throw in `c.action` (no frame marker exists inside a plain action body, so F2's check can't see it). Add an explicit KDoc/GUIDE warning for that torn shape. Optional behavior guard (maintainer decision, see Risks): at `atomic` entry, precompute the shared set (`v.activeTransaction?.ownerThreadId == ownerThreadId`, stable pre-lock on own thread) and throw `FrameInteropException` when the frame is *mixed* (some savepoint, some fresh) — all-shared and all-fresh frames are sound.

- **Files:** `Atomic.kt` (KDoc; + entry check if guard approved), `SuspendAtomic.kt` KDoc, `GUIDE.md`, changelogs. Test (if guard): `FrameTest.kt` mixed-nesting rejection + existing savepoint tests stay green.

### F4 (major) — `suspendAtomic`/`suspendAction` nesting dies with raw mutex error or deadlock — **size L**

**Approach (Option 1, recommended — see Risks).** Make the non-frame `suspendAction` path install the same coherence machinery `suspendAtomic` uses, wrapped around the body only: a `FrameMarker(participants = setOf(store), policy = AllowUnenrolled + TolerateInnerErrors, suspending = true, parent = FrameMarkers.current())` via the existing `frameMarkerContext(...)` element, plus a `SuspendAtomicFrame(owner)`-style held-stores context element. Consequences, all via already-existing gates: (a) nested `S.suspendAction { S.suspendAction { } }` hits `frameGateAllowsOnlySavepoint` (`SuspendAction.kt:190`) and takes the existing `suspendActionInFrame` savepoint path instead of `mutex.withLock(sameJob)` → raw ISE; (b) `suspendAtomic(S, …)` inside `S.suspendAction` either savepoints S into the suspendAction root (if the held-stores element is shared) **or** — simpler recommended default — `verifyFrameNesting` sees the same-flavor enrolled store and we add an explicit `FrameInteropException` naming the hoist ("run `suspendAtomic` first and `suspendAction` inside it"); (c) blocking `action` on the same store inside the body hits `checkFrameAllowsBlockingAction` (`Store.kt:405-420`) → the P1 teaching exception, killing the same-coroutine livelock. Context-element detection (not `coroutineContext[Job]` comparison) is required because `withContext(dispatcher)` replaces the Job — owner-identity checks miss the hop case.

- **Files:** `SuspendAction.kt` (main), `SuspendAtomic.kt` (owner/held-stores resolution if element is shared), possibly `FrameMarkerContext.kt` (helper reuse only).
- **Tests:** `SuspendActionTest.kt`: nested same-store suspendAction → savepoint semantics (inner commit merges, inner rollback discards only inner writes, single fanout at outer commit); nested via `withContext(Dispatchers.Default)` hop (jvmTest); `SuspendAtomicFrameTest.kt`: `suspendAtomic(S)` inside `S.suspendAction` → `FrameInteropException` (not raw "already locked by the specified owner"), disjoint-store `suspendAtomic` inside a suspendAction still works.
- **API/docs:** no apiDump (all internals/`@StoreInternalApi`). `suspendAction` KDoc concurrency contract, `GUIDE.md` §14.8 + §15.4, `holdfast-coroutines/CHANGELOG.md` (behavior change: previously-deadlocking/ISE shapes now savepoint or teach).

### F5 (major) — RYOW silently breaks across dispatcher hops — **size M**

**Approach.** In `MutableState.value` (`MutableState.kt:87-96`), extend the owner check: pending writes are also visible when `owningStore.suspendingOwner != null` **and** `FrameMarkers.current()?.isEnrolled(owningStore) == true`. The marker gate is what makes this sound: the ThreadContextElement/interceptor installs the marker exactly on the thread currently resuming the frame/suspendAction body, so only single-flight body code sees pending values. **Do not** relax on `suspendingOwner != null` alone — that would leak uncommitted staged values to arbitrary reader threads (UI, observers), breaking the committed-visibility contract. With F4's Option 1, the same getter change covers `suspendAction` bodies too; without it, only `suspendAtomic` gets fixed and `suspendAction` needs a loud GUIDE caveat. Document the residual iOS/wasmJs gap (interceptor replaced by nested `withContext` — same documented gap as enforcement, GUIDE §15.1).

- **Files:** `MutableState.kt`; tests in `FrameMarkerDispatchTest.kt` (jvmTest — `update { }` after a forced dispatcher hop inside `suspendAtomic`/`suspendAction` reads the staged value; the lost-update transfer repro from the analysis as regression) and a negative test: a concurrent plain reader thread during an in-flight suspendAction still sees only committed values.
- **API/docs:** no apiDump. `GUIDE.md` §15.3 point 3 (RYOW wording + platform caveat), `value` KDoc, changelog.

### F6 (major) — `action {}` throws instead of returning `Error` inside Strict frames — **size S**

**Approach.** Documentation only: add a "Result contract inside frames" paragraph to `action`'s KDoc (`Store.kt:354-375`) and `suspendAction`'s, stating that inside a Strict frame body an inner-error result never returns — `escalateInFrameError` rethrows the original exception to abort the frame — and that `TolerateInnerErrors` restores check-it-yourself semantics. Recommend **against** a distinguishable wrapper exception: it would change `TransactionResult.Error.exception` identity for every frame abort, break existing assertions (`FrameTest.kt:272` asserts the original message) and the "rethrows the original exception" `getOrThrow` contract.

- **Files:** `Store.kt` (KDoc), `SuspendAction.kt` (KDoc), `GUIDE.md` §15.2. No tests (behavior unchanged; optionally pin with one assertion that the throw site is `action` itself). No apiDump.

### F7 (major) — `result.transaction` is arbitrarily `roots.last()`; Error doesn't name the failing store — **size S**

**Approach.** Document now, restructure later: KDoc on `atomic` and `suspendAtomic` stating `Success/Error.transaction` is the **last participant root in lock order** (highest `lockOrderKey`) and that per-store outcomes should be correlated via `Transaction.frameId` (middleware / `shouldCommitTogether`). On the commit-failure path, improve identification cheaply by wrapping the propagated exception message with the failing store's identity at the `atomic`/`suspendAtomic` catch site is *not* possible without wrapping (conflicts with F6 reasoning) — instead add the store name into the per-entry commit loop by catching around `entry.txn.commit()` and rethrowing a `TransactionException("Commit failed for <store>#<key> in frame <frameId>", e)`; this stays inside `Atomic.kt`/`SuspendAtomic.kt` and does not touch `Transaction.kt` (which the fanout-partial-commit finding in another lane likely owns). `FrameResult` with per-store transactions is deferred to the ABI break — add a ROADMAP line.

- **Files:** `Atomic.kt`, `SuspendAtomic.kt`, `GUIDE.md` §15.3, `ROADMAP.md`. Tests: one assertion each that a failing commit's error names the store.

### F9 (minor) — `simpleName`-only identity; bare `mutate` misattributed as "via action" — **size S**

**Approach.** Add instance identity everywhere frames name stores: change `FrameMarker.describeParticipants()` (`Frame.kt:175-178`), `Store.unenrolledMessage` (`Store.kt:440-451`), `checkFrameAllowsBlockingAction`, `verifyFrameNesting` messages, and `SuspendAction.frameGateAllowsOnlySavepoint` to render `SimpleName#<lockOrderKey>` (stable, short, and ties directly into the lock-order narrative — distinguishes `accountA`/`accountB` of one class). Fix the misattribution by checking the frame marker in `mutate`'s no-active-txn fallback branch (`Store.kt:652-653`) **before** synthesizing the one-shot action, calling a refactored `checkFrameAllowsBlockingAction(frame, via = "mutate")` (add a `via: String` param to the private helper and to `unenrolledMessage`).

- **Files:** `Frame.kt`, `Store.kt`, `SuspendAction.kt`; tests `FrameTest.kt` (message contains `#<key>`; bare `mutate` on unenrolled store reports "via mutate"). No test currently asserts the exact strings (verified by grep), but **`GUIDE.md:1758-1763` quotes the message verbatim** — update §15.1 in lockstep. No apiDump (message-only).

### P1-livelock (partial) — blocking `action` under in-flight `suspendAction` — **size S (incremental)**

**Approach.** The same-coroutine case (blocking `action` called from *inside* a suspendAction body — the unrecoverable self-spin) falls out of F4 Option 1 for free: the installed suspending marker makes `checkFrameAllowsBlockingAction` throw the existing `FrameInteropException`. The cross-thread case is not a livelock after F1 — it's a bounded spin-wait for serialization (same as today's `action` vs `suspendAction`), which stays; the single-threaded-dispatcher starvation case is undecidable from inside the library and stays documented. Update the root `README.md` Known-issues entry and the `ROADMAP.md` 0.3.0 item to reflect the narrowed scope.

- **Files:** covered by F4 (`SuspendAction.kt`, `Store.kt` untouched beyond F9), plus `README.md`, `ROADMAP.md`. Test: blocking `action` inside a suspendAction body throws `FrameInteropException` instead of hanging (guarded with a timeout).

---

## 2. Intra-lane ordering

1. **F1 first** (blocker; and the reentrant `blockingAcquire` is a hard prerequisite for every later test that mixes frames with suspending work — without it, F4's nested tests hit the latent `tryLock(SPIN_OWNER)` ISE).
2. **F9 second** (tiny; lands the final message format + `via` plumbing so F2/F3/F4 tests assert final strings once, not twice).
3. **F2 third** (its `verifyFrameNesting` policy check must exist before F4's Option-1 marker, whose `AllowUnenrolled` policy is what keeps legitimate nested frames inside suspendAction bodies legal — landing them in the other order churns semantics twice).
4. **F4 + F5 + P1 as one unit** (the marker/context-element mechanism is shared; F5's getter gate is only sound with the marker present).
5. **F3, F6, F7 docs last** (they describe the post-fix semantics; writing them earlier means rewriting them).

---

## 3. SHARED-FILE MANIFEST (all paths I would modify)

**Source**
- `/home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Atomic.kt`
- `/home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Frame.kt`
- `/home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Store.kt`
- `/home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/MutableState.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/AsyncSerializer.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendAction.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendAtomic.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/FrameMarkerContext.kt` (possible, helper reuse only)

**Tests**
- `/home/user/holdfast/holdfast/src/commonTest/kotlin/com/vynatix/holdfast/FrameTest.kt`
- `/home/user/holdfast/holdfast/src/commonTest/kotlin/com/vynatix/holdfast/AtomicTest.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendActionTest.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendAtomicFrameTest.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/SuspendAtomicTest.kt`
- `/home/user/holdfast/holdfast-coroutines/src/commonTest/kotlin/com/vynatix/holdfast/coroutines/BlockingAtomicSuspendInteropTest.kt` (NEW)
- `/home/user/holdfast/holdfast-coroutines/src/jvmTest/kotlin/com/vynatix/holdfast/coroutines/FrameMarkerDispatchTest.kt`

**Docs / meta**
- `/home/user/holdfast/holdfast/GUIDE.md` (§15.1–§15.4, §14.8) — **high conflict risk with the docs lane**
- `/home/user/holdfast/holdfast/CHANGELOG.md`
- `/home/user/holdfast/holdfast-coroutines/CHANGELOG.md`
- `/home/user/holdfast/README.md` (Known-issues entry only) — **conflict risk**
- `/home/user/holdfast/ROADMAP.md` (livelock 0.3.0 item; FrameResult note) — **conflict risk**
- `/home/user/holdfast/MIGRATING.md` (F2 behavior change) — **conflict risk**
- Possible: `/home/user/holdfast/holdfast-coroutines/detekt-baseline.xml` (SuspendAction.kt is already at `LongMethod` suppression territory; prefer `@Suppress` inline, but listing for completeness)

No `api/` dumps: no public signature changes anywhere in this lane. GUIDE §15 code blocks are compile-gated by `doc-snippets` (`GuideCrossStoreFrameTwin.kt` etc.) — my GUIDE edits are prose/message-quote blocks and the §15.4 semantics text, not the gated code snippets; if a gated snippet needs the `AllowUnenrolled` policy after F2, `doc-snippets/src/test/.../guidecrossstore/GuideCrossStoreFrameTwin.kt` joins the manifest.

---

## 4. Risk notes / maintainer decisions

1. **F1 mechanism (low risk, but confirm):** serializer-acquire (recommended) vs fail-fast on `suspendingOwner != null`. Fail-fast is racy (volatile read) and rejects legal concurrent use; acquire makes `atomic` exactly as safe as `action`. Residual: the lazy-install window on the very first `suspendAction` of a store's lifetime — same (absent) guarantee as `action`, document rather than fix.
2. **F2 is a behavior break** (previously-green nested-frame introduction now throws under Strict). Recommended default: enforce — the feature's pitch is preventing silent partial commits and the repo is pre-1.0; escape hatch already exists (`AllowUnenrolled`). Needs a behavior-change changelog heading + MIGRATING entry (the analysis already flags frames as absent from MIGRATING).
3. **F3 guard (design decision):** reject *mixed* frames (`c.action { atomic(a,b,c) }`) under Strict, or docs-only? Recommended default: **docs-only now**, propose the mixed-frame `FrameInteropException` as a named follow-up — the all-shared savepoint pattern is legitimate and tested, and the rejection rule (mixed ⇒ throw) needs maintainer sign-off on which policy flag escapes it.
4. **F4/F5 Option 1 vs Option 2 (the big decision):** Option 1 (suspendAction installs a relaxed `FrameMarker` + held-stores element) fixes F4, F5, and P1's same-coroutine case with one mechanism reusing existing gates. Side effects to sign off: (a) `atomic(lowerKeyStore)` inside a suspendAction body will newly throw `FrameLockOrderException` — this is *protective* (I traced a real ABBA deadlock against a concurrent `suspendAtomic` in that shape) but is a behavior change; (b) the body runs inside an extra `withContext`, so `coroutineContext[Job]` inside a suspendAction body changes identity (cancellation still propagates); (c) one `withContext` + marker element of overhead per suspendAction. Option 2 (narrow per-seam detection) avoids (a)/(b) but leaves suspendAction RYOW-across-hops broken (F5 half-fixed) and the P1 same-coroutine livelock alive. Recommended default: **Option 1**.
5. **F5 soundness constraint (non-negotiable):** the getter relaxation must be gated on the thread-local marker (`FrameMarkers.current()?.isEnrolled(...)`), never on `suspendingOwner` alone — the latter leaks uncommitted values to arbitrary reader threads. iOS/wasmJs keep the documented nested-`withContext` gap (enforcement *and* RYOW both lapse there — document them together).
6. **F6 wrapper exception: recommend no.** Breaks `Error.exception` identity, existing message assertions, and `getOrThrow`'s "original exception" contract for zero enforcement gain.
7. **Cross-lane collision:** `Store.kt`, `MutableState.kt`, `GUIDE.md`, changelogs, `README.md`, `ROADMAP.md`, `MIGRATING.md` are near-certain to be touched by the silent-failure, docs/funnel, and install-story lanes. `Transaction.kt` is deliberately **not** in my manifest (the "Commit failed" identity fix belongs to the fanout/partial-commit finding's owner); F7's store-naming happens at the `Atomic.kt`/`SuspendAtomic.kt` call sites instead.

## 5. Size summary

| Finding | Size |
|---|---|
| F1 | M |
| F2 | M |
| F3 | S (docs default; M with guard) |
| F4 | L |
| F5 | M |
| F6 | S |
| F7 | S |
| F9 | S |
| P1 | S (incremental on F4) |

### Critical Files for Implementation
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Atomic.kt
- /home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/SuspendAction.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Store.kt
- /home/user/holdfast/holdfast-coroutines/src/commonMain/kotlin/com/vynatix/holdfast/coroutines/AsyncSerializer.kt
- /home/user/holdfast/holdfast/src/commonMain/kotlin/com/vynatix/holdfast/Frame.kt