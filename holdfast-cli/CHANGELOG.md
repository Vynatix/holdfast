# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This module is internal tooling and is never published; versions below track the
library's version.

## [Unreleased]

### Added

- `trace render FILE` and `trace stats FILE` — offline rendering and aggregation
  of JSONL traces emitted by the new `:holdfast-debug` module's
  `DebugTraceMiddleware` (savepoint trees, frame grouping, rolled-back
  transactions with errors, orphans; counts, durations, slowest, rollback rate).
- `run repro.kts` — compile-and-run a Kotlin repro script (JSR-223) with
  `DebugSandbox.trace(store) { … }`; prints each traced store's transaction tree
  and a final redaction-aware snapshot — the bug-report attachment format.

### Added (initial release)

- `holdfast` CLI with `snapshot inspect` (schema version, states, unencodable
  names, redaction-aware rendered values), `snapshot validate` (decode check
  with offset-bearing failures, canonicality note, `--strict`), `snapshot diff`
  (schema drift, added/removed state names, line-diff of rendered values), and
  `doctor` (build/runtime environment). All parsing goes through holdfast's
  public `StoreSnapshot` API; the wire format is never reimplemented.
