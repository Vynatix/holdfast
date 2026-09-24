// Twins of GUIDE §16 (Snapshots, persistence and boot — experimental). The
// §16.1 block is embedded at top level; the test below drives it and asserts
// the before/after values its comments claim.
package com.vynatix.holdfast.snippets.twins.guideboot

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.reset
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#64
class SessionStore : Store<SessionStore>() {
    val user by state { "guest" }
    val cart by state { emptyList<String>() }
    val greeting by state { "Hello, ${user.value}" }   // reads user
}

@OptIn(ExperimentalStoreApi::class)
fun signOut(session: SessionStore) {
    // Before: user = "ada", cart = [book], greeting = "Welcome back, ada".
    // After: "guest", [], "Hello, guest", as in a new SessionStore.
    session.reset().getOrThrow()
}
// DOC-SNIPPET-END

class GuideSnapshotBootTwin {
    @Test
    fun signOutResetsTheSessionToANewStoresValues() {
        val session = SessionStore()
        session action {
            user mutate "ada"
            cart mutate listOf("book")
            greeting mutate "Welcome back, ada"
        }

        signOut(session)

        assertEquals("guest", session.user.value)
        assertEquals(emptyList(), session.cart.value)
        assertEquals("Hello, guest", session.greeting.value, "greeting read the reset user")
    }
}
