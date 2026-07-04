# Parallel fix plan for PR #17 findings

PR #17 (`claude/library-apis-usability-7j0l8c`) is the second-pass usability analysis:
33 fresh findings (F1–F33: 1 blocker, 19 majors) plus 13 open and 3 partial first-pass
items, machine-readable in `usability-findings-2026-07-04.json`. This document is the
orchestration plan for fixing all 49 items with parallel sub-agents. Six planning agents
each investigated one workstream against source and produced the detailed lane plans in
`planning/lane-{A..F}.md`; this file is the synthesis: lane scopes, the cross-lane
conflict matrix, deduplication rulings, and the phased execution order.

## Lane inventory

| Lane | Scope | Findings | Size | Detailed plan |
|---|---|---|---|---|
| **A** | Frame/atomic concurrency (core + coroutines seam) | **F1 (blocker)**, F2–F7, F9, P1-livelock | ~2M+1L | `planning/lane-A.md` |
| **B** | Core silent failures & contract gaps | F10, F11, F17, F29, F31, F32, P1-observer-swallow, P1-partial-commit, P1-emit-owner, P1-disposed-gaps, P1-update-rmw, P1-invoke-nonatomic, P1-crtp, P1-lazy-registration, P1-defaultscope (+ enabling change E1: `MutableState.debugName`) | ~2 wk | `planning/lane-B.md` |
| **C** | Bridges + coroutines module | F12, F13, F14, F15, F16, F30 | 4M+2S | `planning/lane-C.md` |
| **D** | Compose module | F18, F19, F20 | 1M+2S | `planning/lane-D.md` |
| **E** | Testing harness | F21, F24, P1-testing-bundle, P1-action-shadow, P1-vacuous-matchers, P1-kotest-dep | 5M+1S | `planning/lane-E.md` |
| **F** | Docs, CI/publish, changelogs, API polish, hallmark | F8, F22, F23, F25, F26, F27, F28, F33, P1-install, P1-module-readmes | 3L+5M+2S | `planning/lane-F.md` |

## Cross-lane conflict matrix (code files claimed by >1 lane)

| File | Lanes | Ruling |
|---|---|---|
| `holdfast/.../Store.kt` | A, B, C, F | **B owns the file.** A's edits (frame gates in `mutate`, `checkFrameAllowsBlockingAction`, `action` KDoc) land in Phase 1 before B starts; B rebases. C drops its `distinct` KDoc lines from `Store.kt` (per its own fallback — `Cipher.kt`/`EncryptingTransformer.kt`/GUIDE suffice). F's additions (`removeMiddleware`, `action(name=)`, `CoroutineName`) move to Phase 3, after B. |
| `holdfast/.../MutableState.kt` | A, B | A touches only the `value` getter (F5 RYOW gate); B touches everything else (`debugName`, `notifyObservers`, `applyCommitted`, `bridge` setter, ctor visibility). A first, B rebases. |
| `holdfast/.../Transaction.kt` | B only | A deliberately excluded it (F7 store-naming happens at `Atomic.kt`/`SuspendAtomic.kt` call sites). Keep it that way. |
| `holdfast/.../Frame.kt` | A, F | A owns code (`verifyFrameNesting`, message format); F's edits are KDoc ("pre-0.3" phrasing) + `FrameObserver` params (F33) — Phase 3, after A. |
| `holdfast/.../Atomic.kt`, `SuspendAtomic.kt` | A, B, F | A owns (F1 serializer bracket, F7 messages). B adds one-line disposed checks at entry after A merges. F's `FrameObserver` participants param — Phase 3. |
| `holdfast-coroutines/.../SuspendAction.kt` | A, B, F | A rewrites it (F4 Option 1). B's disposed check and F's `suspendAction(name=)` mirror land after A. |
| `holdfast-coroutines/.../AsyncSerializer.kt` | A, C | A owns code (reentrant `blockingAcquire`); C's edit is one KDoc sentence — C should skip it if A hasn't merged, or apply on top. |
| `holdfast-coroutines/.../SuspendDerived.kt` | C, B | C owns (F16 `initial` overload); B adds the disposed check *inside C's new entry points* — B lands after C or coordinates the one-line check into C's PR. |
| `holdfast/.../Derived.kt` | B, F | B owns code (F10/F17). F33's `DerivedState` return type is **folded into B's Derived work** (one reshape, one apiDump churn) — F hands the item to B. |
| `holdfast/.../crypto/Cipher.kt`, `EncryptingTransformer.kt` | C, F | C's F30 KDoc + F33's `Cipher`→`StoreCipher` rename touch the same files. Ruling: C writes the F30 KDoc; the rename happens in Phase 3 (F) and mechanically carries C's KDoc. |
| `holdfast-coroutines/.../SuspendingFileSystemKvStore.kt` | C, B, F | C owns the KDoc example fix (it knows the post-F14 factory API); **F drops F26 item 4**. B's F29 const-val privatization in the platform actuals is orthogonal (different files: `.jvm.kt`/`.ios.kt`). |
| `holdfast-compose/.../ComposeBindings.kt` | D, F | D owns entirely (F19 rewrites the same KDoc F26 item 5 / F33 Vault-naming target). **F drops both sub-items.** |
| `holdfast-testing/**` | E, B, F | E owns the module. **F drops its `holdfast-testing/README.md` (P1-module-readmes) — E writes it.** F keeps GUIDE §11 storeTest section (content cross-checked with E's README). F's `FrameObservers.clear()` in `StoreTestScope.kt` teardown and its `Recorder.kt`/`StoreHandle.kt` stale-KDoc fixes (F26 item 7) are handed to E. B's `FailingBridgeTest` message-comment update rides B's partial-commit PR. |
| `holdfast-hallmark/.../BoxedHandle.kt` | F only | No conflict; needs the hallmark mavenLocal ritual for apiDump. |
| API dumps (`*/api/*.api`) | all | Never hand-merge. Each lane runs `apiDump` for its own modules per PR; lane F re-runs a full `apiDump` after final integration. |

**Docs/meta files touched by everyone** (`GUIDE.md`, root `README.md`, `holdfast/README.md`,
all CHANGELOGs, `MIGRATING.md`, `ROADMAP.md`, `CLAUDE.md`, `doc-snippets/snippet-exclusions.txt`):
code lanes make *minimal, section-scoped* edits in lockstep with their API changes (D confines
itself to GUIDE §14.9, C to §14.7/§14.8, A to §15.x, B to its API-reference rows); lane F does
the consolidated changelog/MIGRATING/TOC/ROADMAP pass **last** (F8, F27, F28), folding in what
the code lanes added. Snippet-exclusion indices are positional — only lane F adds/removes
```kotlin fences in GUIDE (single commit with full re-index); all other lanes edit inside
existing fences only.

## Execution phases

**Phase 0 — serial, minutes.** F25: wire `:doc-snippets:test` (+ its detekt/ktlint) into
`ci.yml`'s ubuntu job so the doc gate runs on every subsequent PR. One tiny standalone PR.

**Phase 1 — five agents in parallel** (file-disjoint after the rulings above):
1. **Lane A** — F1 first (blocker + reentrant `blockingAcquire`, prerequisite for its own later tests), then F9 → F2 → F4+F5+P1-livelock as one unit → F3/F6/F7 docs. Highest priority; two other lanes rebase on it.
2. **Lane C** — F14 → F13 → F15 as one sequenced series in `SuspendingBridge.kt`; F12, F16, F30 independent. Skips `Store.kt` and `AsyncSerializer.kt` KDoc lines (rulings above).
3. **Lane D** — F19 → F18 → F20, one small PR, isolated to `holdfast-compose/`.
4. **Lane E** — F21 → vacuous-matchers → F24 → action-shadow → kotest → README/CHANGELOG; plus the three items inherited from F (StoreTestScope `FrameObservers.clear()`, Recorder/StoreHandle KDoc). Isolated to `holdfast-testing/`.
5. **Lane F-infra** — P1-install (vanniktech publish rip-and-replace per ROADMAP 0.2.0), F23 (`BoxedHandle.assign` typed context + runtime gate), F22 (hallmark README, written after F23 settles the contract). Touches only buildSrc/workflows/hallmark — disjoint from everything else.

**Phase 2 — one agent.** **Lane B** rebases on A (and C for the `SuspendDerived` disposed
check) and executes in its internal order: E1 → observer-swallow → F32 helper → disposed-gaps
→ F10 → F31 → F11 → update-rmw → invoke-guard → crtp → lazy-registration → F29 → F17 →
emit-owner → defaultscope. Includes F33's `DerivedState` reshape (inherited from F).
`Store.kt`/`Transaction.kt`/`MutableState.kt`/`Snapshot.kt` are the serialization point of the
whole effort — B is deliberately not parallelized internally.

**Phase 3 — one agent, after everything merges.** **Lane F-docs+API**: remaining F33 code
(`removeMiddleware`, `action(name=)`/`suspendAction(name=)`, `StoreCipher` rename + typealias,
`FrameObserver` participants, `CoroutineName`, Vault→Store KDoc sample sweep minus files other
lanes already fixed), then the consolidated F8 + F27 changelog/MIGRATING pass, F26 remaining
drift items, GUIDE §11 storeTest insertion with full exclusion re-index, F28 TOC/layout/ROADMAP
refresh, `CLAUDE.md` updates (publish.yml sentence; lazy-registration contract line if B ships
it), final full `apiDump` (incl. hallmark with `-Pholdfast.includeHallmark=true`), and the
closing gate: `./gradlew check` + `:doc-snippets:test`.

Estimated wall-clock: Phase 1 is the long pole among parallel lanes (Lane A, ~L); total
effort ≈ 4–5 engineer-weeks compressed to 3 sequential phases.

## Maintainer decisions needed before implementation (recommended defaults in lane plans)

1. **A/F4:** Option 1 (suspendAction installs a relaxed FrameMarker — fixes F4+F5+livelock with one mechanism) vs narrow per-seam detection. *Recommended: Option 1.*
2. **A/F3:** reject *mixed* shared/fresh frames under Strict, or docs-only now. *Recommended: docs-only, guard as follow-up.*
3. **B/partial-commit item 3:** sync `Bridge.publish` throws stop aborting commits (routed to `uncaughtObserverHandler`). *Recommended: yes — coordinates with C's F13 wording and E's FailingBridge tests.*
4. **B/lazy-registration:** eager registration via `provideDelegate` is the lane's biggest behavior break (initializers run at construction). *Recommended: ship per ROADMAP with MIGRATING recipe; fallback is docs-only.*
5. **D/F18:** `rememberDisposable(vararg keys)` replacing the lambda-keyed form (binary break, source compatible). *Recommended: yes.*
6. **E/F21:** `AwaitingTimeoutException` becomes `AssertionError` subclass with the CRCE→quiet-teardown split. *Recommended: yes.*
7. **F/P1-install:** adopt vanniktech maven-publish plugin (publish.yml was written for it). *Recommended: yes — never point publish.yml at the homegrown task; it fails at upload by construction.*
8. **F/F33 waivers:** defer `TransactionResult` naming and `middlewares()` rename to the pre-1.0 break window. *Recommended: waive with written rationale in ROADMAP.*

## How to run this

Each phase-1 lane is an independent sub-agent (worktree isolation) implementing its
`planning/lane-*.md` verbatim, gating on `./gradlew check` for its modules before push.
Merge order within Phase 1 is A → C → E/D/F-infra (any order); every later merge rebases
and re-runs `apiDump` for touched modules. Phases 2 and 3 are single agents. PR-per-lane
against `main`, referencing PR #17 and the finding IDs each commit resolves.
