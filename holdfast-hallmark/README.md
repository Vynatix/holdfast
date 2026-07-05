<!--
  This README is deliberately NOT in :doc-snippets' tracked/compiled set: every
  snippet here needs the sibling Hallmark library, which is off the default
  build (mavenLocal-only). The code blocks are illustrative and are not
  compile-checked by DocSnippetDriftTest.
-->

# `:holdfast-hallmark` — refinement types at the transactional boundary

Bridges [Hallmark](https://github.com/vynatix/hallmark)'s refinement types
(`Boxed<P>` + `Validator`) into Holdfast state, so a value that has been
*validated once at the boundary* stays validated for the life of the state:
every write re-runs the validator, and an invalid write rolls the whole
transaction back.

- **`ValidatingTransformer`** — a Holdfast `Transformer` that re-validates on
  every `set`, so constructor bypass (e.g. `data class copy`) is rejected.
- **`Store.boxed { }`** — state factory whose property type is `State<O>`
  (the wrapped/`Boxed` form).
- **`Store.boxedHandle { }`** — like `boxed`, but the property is a
  `BoxedHandle` bundling state + validator; enables the `assign` infix.
- **`BoxedCodec`** — round-trips a `Boxed<P>` through any `Codec<P>` for
  bridge persistence.
- **`shouldBeBoxedAs`** — test matcher asserting a state holds a given wrapped
  value. It lives **here**, not in `:holdfast-testing`, because that module must
  not depend on the unpublished Hallmark artifact.

## Status — unreleased (mavenLocal-only)

`com.vynatix:hallmark` is not yet on Maven Central, so this module is
**excluded from the default build**. To work on it (or depend on it), publish
the sibling Hallmark repo to `mavenLocal` first, then opt in:

```bash
# 1. Build/publish the Hallmark library to ~/.m2
git clone https://github.com/vynatix/hallmark && cd hallmark
./gradlew publishToMavenLocal

# 2. Back in holdfast, enable the hallmark modules
./gradlew -Pholdfast.includeHallmark=true :holdfast-hallmark:allTests
./gradlew -Pholdfast.includeHallmark=true :holdfast-hallmark:apiCheck
./gradlew -Pholdfast.includeHallmark=true :holdfast-hallmark:publishToMavenLocal
```

Without `-Pholdfast.includeHallmark=true`, `:holdfast-hallmark` and
`:holdfast-hallmark-coroutines` are not part of the build at all.

## Quick start

```kotlin
data class Email(override val value: String) : Boxed<String>

object EmailValidator : BoxedValidator<String, Email>() {
    override val specs = listOf(
        Spec(listOf(NonBlankRule(), MinLengthRule(3)), SpecMode.ALL) { Email(it) },
    )
}

class UserStore : Store<UserStore>() {
    val email       by boxed(EmailValidator) { "init@example.com" }       // State<Email>
    val displayName by boxedHandle(NameValidator) { "init" }              // BoxedHandle<UserStore, String, Name>
}

val store = UserStore()

store action {
    email mutate (EmailValidator of "alice@example.com")   // explicit validator at the call site
    email mutate Email("not-an-email")                     // rolls back via ValidatingTransformer
}
```

`boxed(validator) { initial }` is sugar for
`state(transformer = ValidatingTransformer(v)) { v of initial() }`; mutate it
with the explicit validator (`v of primitive`). `boxedHandle(validator) { … }`
bundles the validator alongside the state so you don't have to name it again at
the call site — and unlocks the `assign` one-liner below.

### Persistence — `BoxedCodec`

```kotlin
store {
    email bridge KvBridge(
        kv    = kvStore,
        key   = "user.email",
        codec = BoxedCodec(StringCodec, EmailValidator),   // re-validates on decode
    )
}
```

### Testing — `shouldBeBoxedAs`

```kotlin
store action { email mutate (EmailValidator of "alice@example.com") }
store.email shouldBeBoxedAs "alice@example.com"
```

## Using `assign`

`boxedHandle` unlocks a one-line civilize-and-mutate infix:

```kotlin
store action {
    displayName assign "Alice"        // == displayName.state mutate displayName.civilize("Alice")
}
```

Two things to know before you reach for it:

1. **`assign` requires Kotlin context parameters.** It is declared with a
   `context(store: V)` receiver, so the *consuming* module must enable the
   compiler flag:

   ```kotlin
   // build.gradle.kts
   kotlin {
       compilerOptions {
           freeCompilerArgs.add("-Xcontext-parameters")
       }
   }
   ```

   If you can't (or don't want to) enable that flag, use the **two-step form**,
   which needs no flag and does exactly the same thing:

   ```kotlin
   store action {
       displayName.state mutate displayName.civilize("Alice")
   }
   ```

2. **`assign` is gated to its owning store's open action.** The infix is typed
   to the handle's owning store (`BoxedHandle<V : Store<V>, …>`), so using a
   handle inside a *different* store's `action { }` is a **compile error** — the
   `@StoreActionDsl` marker hides every outer receiver, leaving only the store
   whose action you are in. Past the type system, a runtime gate throws
   `IllegalStateException` with a teaching message if you call `assign`:
   - outside any `action { }` (it would otherwise commit a silent one-shot
     transaction that can't roll back with your surrounding logic),
   - on a thread that doesn't own the active transaction, or
   - against a handle owned by a different `Store` instance.

   In short: `assign` only ever runs inside its own store's action, on that
   action's thread. For validation that needs I/O (async uniqueness checks,
   remote gates), use `suspendValidateAndMutate` from
   [`:holdfast-hallmark-coroutines`](../holdfast-hallmark-coroutines/README.md).

## Building

```bash
./gradlew -Pholdfast.includeHallmark=true :holdfast-hallmark:allTests
./gradlew -Pholdfast.includeHallmark=true :holdfast-hallmark:apiCheck
```
