@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.bridge.BridgeView
import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.matcher.shouldHaveLastPublished
import com.vynatix.holdfast.testing.matcher.shouldHavePublished
import com.vynatix.holdfast.testing.matcher.shouldHavePublishedInOrder
import com.vynatix.holdfast.testing.matcher.shouldMatch
import com.vynatix.holdfast.testing.matcher.shouldMatchExactly
import com.vynatix.holdfast.testing.matcher.shouldMatchSnapshotOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val PASSWORD = "pa55-5ECRET-11"
private const val GUESS = "gue55-5ECRET-12"

private class LoginStore : Store<LoginStore>() {
    val user by state { "guest" }
    val password by state(tags = setOf(StateTag.Secret)) { "" }
}

/**
 * The harness's matchers never print a Secret state's value (issue #20, R3):
 * state and snapshot matchers compare it but withhold it from their failure
 * messages, and a Secret state's bridge view withholds its published values,
 * refusing value matchers with a teaching error.
 */
class SecretRedactionMatcherTest {
    private fun assertNoSecretIn(message: String?) {
        listOf(PASSWORD, GUESS).forEach { secret -> assertFalse(secret in message.orEmpty(), "leaked: $message") }
    }

    @Test fun stateMatchersCompareSecretsButNeverPrintThem() =
        storeTest {
            val handle = track(LoginStore())
            handle.action { password mutate PASSWORD }
            handle shouldMatch { LoginStore::password shouldEqual PASSWORD }

            val lenient = assertFailsWith<AssertionError> { handle shouldMatch { LoginStore::password shouldEqual GUESS } }
            assertContains(lenient.message.orEmpty(), "password: does not match (a Secret state: values withheld)")
            assertNoSecretIn(lenient.message)

            val exact =
                assertFailsWith<AssertionError> {
                    handle shouldMatchExactly {
                        LoginStore::user shouldEqual "nobody"
                        LoginStore::password shouldEqual GUESS
                    }
                }
            assertContains(exact.message.orEmpty(), "user: expected=nobody actual=guest", message = "other states still show values")
            assertNoSecretIn(exact.message)
        }

    @Test fun snapshotMatchingWithholdsSecrets() =
        storeTest {
            val handle = track(LoginStore())
            handle.action { password mutate PASSWORD }
            val other = LoginStore()
            other action { password mutate GUESS }
            val failure = assertFailsWith<AssertionError> { handle shouldMatchSnapshotOf other }
            assertContains(failure.message.orEmpty(), "password: does not match")
            assertNoSecretIn(failure.message)
        }

    @Test fun aSecretStatesBridgeViewWithholdsItsValues() =
        storeTest {
            val bridge = RecordingBridge(initial = "")
            val handle = track(LoginStore().also { s -> s { password bridge bridge } })
            handle.action { password mutate PASSWORD }
            handle.action { password mutate "$PASSWORD-2" }

            val view = handle.bridge(LoginStore::password)
            assertEquals(listOf<Any>(Redacted, Redacted), view.published, "one entry per publish, never the value")
            assertEquals(Redacted, view.lastPublished)

            @Suppress("UNCHECKED_CAST")
            val typed = view as BridgeView<String>
            val refusals =
                listOf(
                    assertFailsWith<IllegalStateException> { typed shouldHavePublished PASSWORD },
                    assertFailsWith<IllegalStateException> { typed shouldHavePublishedInOrder listOf(PASSWORD) },
                    assertFailsWith<IllegalStateException> { typed shouldHaveLastPublished GUESS },
                )
            refusals.forEach {
                assertContains(it.message.orEmpty(), "'password' is a Secret state")
                assertNoSecretIn(it.message)
            }
            typed receiving "from-outside"
            assertEquals("from-outside", handle.read { password.value }, "inbound updates still pass")
        }

    @Test fun aBridgeAttachedAfterTrackingIsWithheldToo() =
        storeTest {
            val handle = track(LoginStore())
            val bridge = RecordingBridge(initial = "")
            handle.read { password bridge bridge }
            handle.action { password mutate PASSWORD }
            assertEquals(listOf<Any>(Redacted), handle.bridge(LoginStore::password).published)
            assertEquals(listOf(PASSWORD), BridgeView(bridge).published, "a view of your own bridge shows what it recorded")
        }
}
