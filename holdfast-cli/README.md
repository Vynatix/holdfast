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
| `holdfast doctor` | CLI + bundled holdfast versions, Kotlin, JVM, OS. |

## Scope notes

- Store snapshot bodies only (`store.snapshot().encode()` output). Tree captures
  need a public standalone `TreeSnapshot` decode first (see `issues/015`).
- Exit codes: `0` success, non-zero on any rejection or (with `--strict`)
  non-canonical input.
