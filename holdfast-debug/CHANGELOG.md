# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- First cut: `DebugTraceMiddleware` (versioned JSONL `txn-started`/`txn-ended`
  events — ids, savepoint parent links, frame identity, monotonic durations,
  written state names with keyed families as `family[*]`) and `TraceSink`
  (with `standardOutput()`). Secrets never reach a trace; `err` carries only
  exception class + message. Uses the `@StoreInternalApi` boundary
  (`Transaction.parent`, `State.keyedEntryName`) — never application API.
