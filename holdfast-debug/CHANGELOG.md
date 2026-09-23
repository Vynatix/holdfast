# Changelog — `:holdfast-debug`

All notable changes to `:holdfast-debug` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **New module: on-device debugging console** (`@ExperimentalStoreApi`
  throughout). `HoldfastDebug.register(store, name, redact)` makes a store
  visible; `HoldfastDebug.execute("…")` runs one line of the command
  language: `stores`, `dump`, `get`, `set`, `batch`, `journal`, `autopsy`,
  `stats`, `mark`/`diff`, `checkpoint`/`checkpoints`/`rewind`, `quarantine`,
  `clear`, `verbs`/`run`, `help`. Several commands per line with `;`.
- **`JournalMiddleware`**: bounded ring of finished transactions with each
  write's before/after value, rolled-back transactions' discarded writes and
  error (the autopsy), savepoint depth, frame id, thread and duration; bridge
  inbound updates are captured through a per-state observer. Outcome is read
  live from `Transaction.status`, so later vetoes show up.
- **`QuarantineMiddleware`**: per-store kill switch — every action rolls back
  with `StoreQuarantinedException` before its body runs.
- **Typed `set`/`batch`**: literals are parsed into the runtime class of the
  state's current value and applied through the real `mutate` path in one
  transaction, so transformers, validation middleware, observers and bridges
  behave exactly as for an app write. Unsupported types are refused with a
  pointer to verbs.
- **Android transport** `HoldfastDebugProvider`, declared by the module
  manifest: `adb shell dumpsys activity provider <pkg>/…HoldfastDebugProvider
  <cmd>` streams the output; `content call --method exec` reaches the same
  interpreter from a binder thread. Guarded by `android.permission.DUMP`.
  Argument-less dumps print one summary line and never a value, so
  bugreports capture no state. `MainThreadMutationRunner` hops mutating
  commands to the main thread with a cancel-if-not-started timeout.
- `DebugConsole.repl(...)` helper for stdin/stdout or socket front ends on
  JVM and elsewhere.
