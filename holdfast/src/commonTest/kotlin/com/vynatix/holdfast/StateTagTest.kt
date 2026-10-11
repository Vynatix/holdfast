@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.StringCodec
import kotlin.reflect.KProperty
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TaggedStore : Store<TaggedStore>() {
    var initializersRun = 0

    val plain by state { 0 }
    val token by state(tags = setOf(StateTag.Secret)) {
        initializersRun++
        "hunter2"
    }
    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) {
        initializersRun++
        "pinned"
    }
    val feed by state(tags = setOf(StateTag.Remote)) {
        initializersRun++
        "none"
    }
    val session by state(tags = setOf(StateTag.Secret, StateTag.Remote)) {
        initializersRun++
        "sid"
    }
    val draft by state(tags = setOf(StateTag.UserAuthored)) {
        initializersRun++
        ""
    }
}

private class SecretAndUserAuthored : Store<SecretAndUserAuthored>() {
    val bad by state(tags = setOf(StateTag.Secret, StateTag.UserAuthored)) { "" }
}

private class UserAuthoredAndRemote : Store<UserAuthoredAndRemote>() {
    val mixed by state(tags = setOf(StateTag.UserAuthored, StateTag.Remote)) { "" }
}

/** Forwards only `getValue`, as code compiled before `provideDelegate` existed: the state is declared on first read. */
private class GetValueOnly<T : Any>(
    private val inner: StateDelegate<T>,
) {
    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): State<T> = inner.getValue(thisRef, property)
}

private class UnboundRefusedTags : Store<UnboundRefusedTags>() {
    var ran = false
    val mixed by GetValueOnly(
        state(tags = setOf(StateTag.UserAuthored, StateTag.Remote)) {
            ran = true
            ""
        },
    )
}

/**
 * Declaration rules, the one lookup (`State.tags`, `taggedStates`) and the
 * Secret taint of derived states (issue #20, R3).
 */
class StateTagTest {
    @Test fun secretWithUserAuthoredFailsWhereTheStateIsDeclared() {
        val failure = assertFailsWith<IllegalArgumentException> { SecretAndUserAuthored() }
        assertContains(failure.message!!, "SecretAndUserAuthored.bad")
        assertContains(failure.message!!, "Secret and UserAuthored")
    }

    @Test fun userAuthoredWithRemoteFailsWhereTheStateIsDeclared() {
        val failure = assertFailsWith<IllegalArgumentException> { UserAuthoredAndRemote() }
        assertContains(failure.message!!, "UserAuthoredAndRemote.mixed")
        assertContains(failure.message!!, "UserAuthored and Remote")
    }

    @Test fun aRefusedCombinationRunsNoInitializer() {
        var ran = false
        val store = TaggedStore()
        assertFailsWith<IllegalArgumentException> {
            val local by store.state(tags = setOf(StateTag.UserAuthored, StateTag.Remote)) {
                ran = true
                0
            }
            local.value
        }
        assertFalse(ran, "the declaration fails before any initializer runs")
    }

    @Test fun aRefusedCombinationOnAnUnboundDelegateFailsAtItsFirstRead() {
        val store = UnboundRefusedTags() // the wrapper does not forward provideDelegate: nothing declared yet
        val failure = assertFailsWith<IllegalArgumentException> { store.mixed }
        assertContains(failure.message!!, "UnboundRefusedTags.mixed")
        assertContains(failure.message!!, "UserAuthored and Remote")
        assertFalse(store.ran, "the declaration fails before the initializer runs")
        assertFalse(store.hasState("mixed"))
    }

    @Test fun secretWithRemoteIsAllowed() {
        assertEquals(setOf(StateTag.Secret, StateTag.Remote), TaggedStore().session.tags)
    }

    @Test fun tagsAreWhatTheDeclarationGave() {
        val store = TaggedStore()
        assertEquals(emptySet(), store.plain.tags)
        assertEquals(setOf(StateTag.Secret), store.token.tags)
        assertEquals(setOf(StateTag.UserAuthored), store.pins.tags)
        assertEquals(setOf(StateTag.Remote), store.feed.tags)
        assertEquals(emptySet(), store.computed { plain.value }.tags, "a computed state has no declaration")
        assertEquals(emptySet(), MutableState(1, owningStore = store).tags, "nor has a hand-made one")
    }

    @Test fun theTagSetIsCopiedAtDeclaration() {
        val store = TaggedStore()
        val given = mutableSetOf<StateTag>(StateTag.Remote)
        val local by store.state(tags = given) { 0 }
        given += StateTag.Secret
        assertEquals(setOf(StateTag.Remote), local.tags)
    }

    @Test fun tagsKeepAnsweringAfterDispose() {
        val store = TaggedStore()
        val token = store.token
        store.dispose()
        assertEquals(setOf(StateTag.Secret), token.tags)
    }

    @Test fun taggedStatesListsTheTaggedStatesInDeclarationOrderMaterializingThem() {
        val store = TaggedStore()
        assertEquals(0, store.initializersRun, "declaring runs no initializer")
        val userAuthored = store.taggedStates(StateTag.UserAuthored)
        assertEquals(listOf<State<*>>(store.pins, store.draft), userAuthored)
        assertEquals(2, store.initializersRun, "only the tagged never-read states were materialized")
        assertEquals(listOf<State<*>>(store.token, store.session), store.taggedStates(StateTag.Secret))
        assertEquals(listOf<State<*>>(store.feed, store.session), store.taggedStates(StateTag.Remote))
    }

    @Test fun aDerivedOfASecretIsSecretAndNothingElse() {
        val store = TaggedStore()
        val (masked, d1) = store.derived(store.token, store.plain) { token.value.length + plain.value }
        val (maskedTwice, d2) = store.derived(masked) { masked.value * 2 }
        val (fromPins, d3) = store.derived(store.pins, store.feed) { pins.value + feed.value }
        try {
            assertEquals(setOf(StateTag.Secret), masked.tags, "a value computed from a secret is one")
            assertEquals(setOf(StateTag.Secret), maskedTwice.tags, "the taint follows derived chains")
            assertEquals(emptySet(), fromPins.tags, "a derived is never UserAuthored or Remote")
            assertEquals(emptySet(), derivedTags(listOf(store.computed { token.value })), "a computed source has no tags")
            assertEquals(listOf<State<*>>(store.token, store.session, masked, maskedTwice), store.taggedStates(StateTag.Secret))
        } finally {
            listOf(d1, d2, d3).forEach { it.dispose() }
        }
    }

    @Test fun taggedStatesIsGatedOnDispose() {
        val store = TaggedStore()
        store.dispose()
        val failure = assertFailsWith<IllegalStateException> { store.taggedStates(StateTag.Secret) }
        assertContains(failure.message!!, "disposed")
    }

    @Test fun tagsAndScopesNameThemselves() {
        val tags = listOf(StateTag.Secret, StateTag.UserAuthored, StateTag.Remote)
        val scopes = listOf(SnapshotScope.All, SnapshotScope.UserAuthored, SnapshotScope.Raw)
        assertEquals(listOf("Secret", "UserAuthored", "Remote"), tags.map { "$it" })
        assertEquals(listOf("All", "UserAuthored", "Raw"), scopes.map { "$it" })
    }

    @Test fun aMutableStateNamesItselfAndNeverItsValue() {
        val store = TaggedStore()
        store action { token mutate "s3cr3t-value" }
        assertEquals("MutableState(TaggedStore.token)", store.token.toString())
        val modified = mutableListOf<String>()
        store.middlewares(
            object : Middleware<TaggedStore>() {
                override fun onTransactionCompleted(context: MiddlewareContext<TaggedStore>) {
                    modified += context.transaction.modifiedStates.toString()
                }
            },
        )
        store action { token mutate "an0ther-s3cr3t" }
        assertEquals(listOf("[MutableState(TaggedStore.token)]"), modified)
        assertTrue(MutableState("x", owningStore = store).toString().startsWith("MutableState(a state of TaggedStore"))
    }
}
