# holdfast-cli

Internal developer CLI (never published). Offline debugging of holdfast snapshot
artifacts — it analyzes files, never connects to a running process.

```bash
./gradlew :holdfast-cli:installDist
./holdfast-cli/build/install/holdfast/bin/holdfast --help
```

## Design rule

The CLI never reimplements the snapshot wire format and never decodes a state
value itself. Every parse goes through holdfast's **public** API
(`StoreSnapshot.decode`, `encode`, `render`), so what the CLI reports is exactly
what `restore` would see, and a library-side format change breaks the CLI's
tests instead of silently drifting. No `@StoreInternalApi` usage.

## Commands

| Command | What it does |
|---|---|
| `holdfast snapshot inspect FILE [--no-values]` | Decode one snapshot body; print schema version, state count, unencodable (codec-less) names, and the redaction-aware `render()` (secrets show `<redacted>`, keyed entries by key). |
| `holdfast snapshot validate FILE [--strict]` | Check the file decodes; report the offset-bearing reason when it does not. Note byte-canonicality (the reader accepts liberal input; the writer is canonical). `--strict` fails on non-canonical text. |
| `holdfast snapshot diff A B` | Schema drift, state names added/removed, and value-level changes as a line-diff of the two renderings. Value equality compares canonical encodings — secrets (encoded `null`) never leak. |
| `holdfast trace render FILE` | Transaction tree of a JSONL trace from `:holdfast-debug`'s `DebugTraceMiddleware`: savepoints nested, frames grouped, rolled-back transactions with their error, orphans (started, never ended) flagged. |
| `holdfast trace stats FILE` | Per-store counts, committed/rolled-back/savepoint totals, duration total/avg/max, five slowest transactions, rollback rate, malformed-line count. |
| `holdfast run repro.kts` | Compile and run a Kotlin repro script (JSR-223); every store traced through `DebugSandbox.trace(store) { … }` gets its rendered trace and a final redaction-aware snapshot at exit — the bug-report attachment format. |
| `holdfast doctor` | CLI + bundled holdfast versions, Kotlin, JVM, OS. |

## Scope notes

- Store snapshot bodies only (`store.snapshot().encode()` output). Tree captures
  need a public standalone `TreeSnapshot` decode first (see `issues/015`).
- Exit codes: `0` success, non-zero on any rejection or (with `--strict`)
  non-canonical input.

## Repro scripts (`holdfast run`)

```kotlin
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.cli.DebugSandbox

@OptIn(ExperimentalStoreApi::class)
class Counter : Store<Counter>() {
    val n by state(codec = null, initialize = { 0 })
}

val counter = Counter()
DebugSandbox.trace(counter) {
    counter action { n mutate 1 }
}
```

Script quirks (the scripting frontend differs from a normal compilation):
declare states with an explicit `codec =` or `tags =` argument — the engine
cannot disambiguate holdfast's two `state` overloads otherwise — and opt the
store class into `@OptIn(ExperimentalStoreApi::class)` for the codec/tags
overload.
