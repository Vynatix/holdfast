# `:holdfast-hallmark-coroutines` — suspend Holdfast adapter

Bridges the `SuspendValidator` from [Hallmark](https://github.com/vynatix/hallmark)'s
`com.vynatix:hallmark-coroutines` (a separate repository) into Holdfast's
`suspendAction { }`. Use when validation involves I/O (DB unique-name lookup,
remote feature gate, moderation API call) and you want the result mutated
into a Holdfast state atomically.

## Quick start

```kotlin
class Username(override val value: String) : Boxed<String>

class UniqueUsernameRule(private val taken: Set<String>) : SuspendRule<String>(
    code = "username.unique",
    messageTemplate = "username already taken",
) {
    override suspend fun validate(value: String): Boolean {
        delay(20)
        return value !in taken
    }
}

class UsernameValidator(taken: Set<String>) : SuspendBoxedValidator<String, Username>() {
    override val specs = listOf(
        SuspendSpec(listOf(UniqueUsernameRule(taken)), SpecMode.ALL) { Username(it) },
    )
}

class UserStore : Store<UserStore>() {
    val username by boxed(/* sync leaf */ UsernameFormatValidator) { "init" }
}

suspend fun adoptUsername(store: UserStore, name: String): TransactionResult<Unit> =
    store.suspendValidateAndMutate(store.username, UsernameValidator(taken = …), name)
```

`suspendValidateAndMutate` runs the suspend validator, then mutates the
Holdfast state inside a `suspendAction { }`. On validation failure, throws
`HallmarkException` inside the action — every other state mutation in
the transaction rolls back.

For a `StateTag.Secret` state (declared with `:holdfast-hallmark`'s
experimental `boxed(validator, codec, tags)` or
`boxedHandle(validator, codec, tags)`), the `HallmarkException` withholds the
rejected value: each violation keeps its code, path and rule, reads
"`<code>` rejected the value (withheld: a Secret state)" and carries no
arguments, so a mistyped secret never reaches the `TransactionResult.Error`
or a middleware's log line. Any other state gets hallmark's own messages,
which may quote the value.

## Building

```
./gradlew :holdfast-hallmark-coroutines:allTests
./gradlew :holdfast-hallmark-coroutines:apiCheck
./gradlew :holdfast-hallmark-coroutines:publishToMavenLocal
```
