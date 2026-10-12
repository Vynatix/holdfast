# holdfast-debug

Experimental transaction tracing (issue 016): `DebugTraceMiddleware` emits
versioned JSONL trace events to a `TraceSink`, on every platform holdfast targets.
`:holdfast-cli` (internal) renders them offline with `holdfast trace render|stats`.

```kotlin
import com.vynatix.holdfast.debug.DebugTraceMiddleware
import com.vynatix.holdfast.debug.TraceSink

store.middlewares(DebugTraceMiddleware("CounterStore", TraceSink.standardOutput()))
```

## Event format (v1)

One JSON object per line; `v` is the format version:

```json
{"v":1,"e":"txn-started","id":"…","label":"CounterStore","parent":null,"savepoint":false,"frame":null}
{"v":1,"e":"txn-ended","id":"…","label":"CounterStore","parent":null,"status":"committed","dur_ns":12400,"states":["count","notes[*]"],"err":null}
```

- `dur_ns` brackets the action body plus inner middleware — not the commit fanout
  (register the middleware LAST so it is outermost and brackets the whole chain).
- `states` holds state NAMES only — keyed families as `family[*]`, never a key —
  so a `StateTag.Secret` value can never reach a trace. The one value-bearing
  field is `err`: an exception YOUR code throws is recorded with the message you
  gave it (the same stance as `LoggingMiddleware`).
- A transaction that started but never ended renders as an orphan: process death,
  or attribution lost to a thread hop under `suspendAction`.
- Sinks are called from arbitrary threads; keep them passive and synchronized.

## Boundary use

The module opts into `@StoreInternalApi` for `Transaction.parent` (savepoint
linkage) and `State.keyedEntryName` (keyed-family naming) — the documented
companion-module boundary, never application API. Everything else rides the
public `Middleware` SPI.

## Repro scripts

`holdfast run repro.kts` (in `:holdfast-cli`) traces a store through
`DebugSandbox.trace(store) { … }` and prints the rendered trace plus a final
redaction-aware snapshot — the standard bug-report attachment. In scripts,
declare states with an explicit `codec =` / `tags =` argument (the scripting
frontend cannot disambiguate holdfast's two `state` overloads the way a normal
compilation can).
