# `:vault-validation-coroutines` — suspend Vault adapter

Bridges `:validation-coroutines`' `SuspendValidator` into Vault's
`suspendAction { }`. Use when validation involves I/O (DB unique-name lookup,
remote feature gate, moderation API call) and you want the result mutated
into a Vault state atomically.

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

class UserVault : Vault<UserVault>() {
    val username by boxed(/* sync leaf */ UsernameFormatValidator) { "init" }
}

suspend fun adoptUsername(name: String): TransactionResult<Unit> =
    vault.suspendValidateAndMutate(vault.username, UsernameValidator(taken = …), name)
```

`suspendValidateAndMutate` runs the suspend validator, then mutates the
Vault state inside a `suspendAction { }`. On validation failure, throws
`ValidationException` inside the action — every other state mutation in
the transaction rolls back.

## Building

```
./gradlew :vault-validation-coroutines:allTests
./gradlew :vault-validation-coroutines:apiCheck
./gradlew :vault-validation-coroutines:publishToMavenLocal
```
