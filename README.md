# Holdfast

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.vynatix/holdfast)](https://central.sonatype.com/artifact/com.vynatix/holdfast)

**Transactional state for Kotlin Multiplatform — atomic commits, savepoints, middleware bridges.**

A *holdfast* is the part of a kelp that anchors it to the seabed against the
tides. This library does the analogous thing for application state: a
`Store<Self : Store<Self>>` is a state container whose unit of consistency
is a **transaction** — mutations buffer, observers see only committed values,
failed transactions never leak, and the type system enforces that a state
class anchors itself to its own type.

```kotlin
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val label by state { "init" }
}

val counter = CounterStore()
val sub = counter { count effect { println("count=$this") } }   // count=0

val result = counter action {
    count update { it + 1 }
    label mutate "ready"
    "transitioned to ${count.value}"
}
when (result) {
    is TransactionResult.Success -> println(result.value)        // "transitioned to 1"
    is TransactionResult.Error   -> handle(result.exception)
}

// Failed transactions roll back atomically — observers never fire.
counter action {
    count mutate 99
    error("simulated")
}
```

## Modules

| Artifact | Role |
|---|---|
| [`com.vynatix:holdfast`](holdfast/) | Core — transactions, state, middleware, bridges, snapshot/restore, derived state, cross-holdfast `atomic`, encryption transformer, file-system store. |
| [`com.vynatix:holdfast-coroutines`](holdfast-coroutines/) | `Flow` / `StateFlow` adapters + `suspendAction { … }` for async transactional bodies. |
| [`com.vynatix:holdfast-compose`](holdfast-compose/) | `@Composable` `collectAsState` / `rememberDisposable`. |
| [`com.vynatix:holdfast-testing`](holdfast-testing/) | Testing harness — `holdfastTest { }`, `StoreHandle`, timeline matchers. |
| [`com.vynatix:holdfast-hallmark`](holdfast-hallmark/) | [Hallmark](https://github.com/vynatix/hallmark) bridge — `ValidatingTransformer`, `Store.boxed { }` state factory, `BoxedCodec`. |
| [`com.vynatix:holdfast-hallmark-coroutines`](holdfast-hallmark-coroutines/) | Suspend-side Hallmark bridge — `Store.suspendValidateAndMutate`. |

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// build.gradle.kts
dependencies {
    implementation("com.vynatix:holdfast:0.1.0")
    implementation("com.vynatix:holdfast-coroutines:0.1.0")   // optional
    implementation("com.vynatix:holdfast-compose:0.1.0")      // optional, Compose Multiplatform
    testImplementation("com.vynatix:holdfast-testing:0.1.0")  // optional
}
```

## Documentation

- [`holdfast/README.md`](holdfast/README.md) — full guide: mental model, transactions, state, middleware, positioning vs. other state-management libraries.
- [`holdfast/GUIDE.md`](holdfast/GUIDE.md) — long-form tutorial with decision charts, feature differentiation tables, technique cookbook, and API reference.
- [`holdfast/CHANGELOG.md`](holdfast/CHANGELOG.md) — release history (with internal pre-rename design archive preserved).

## Companion library

[Hallmark](https://github.com/vynatix/hallmark) — refinement types for KMP. The
`:holdfast-hallmark` adapter (in this repo) bridges Hallmark's typed primitives
with Holdfast's transactional state, so validated values live in state that
respects them.

## Stability

**0.x — pre-stable.** The public API may break in any 0.x bump. Consumers should
pin to exact versions. SemVer guarantees apply once 1.0 is declared.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). For security disclosures, email
front.desk@vynatix.com.

## License

Apache 2.0. See [`LICENSE`](LICENSE).
