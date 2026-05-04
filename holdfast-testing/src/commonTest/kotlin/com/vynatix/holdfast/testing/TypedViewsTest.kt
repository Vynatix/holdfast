package com.vynatix.vault.testing

import com.vynatix.vault.Middleware
import com.vynatix.vault.Vault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class MultiVault : Vault<MultiVault>() {
    val a by state { 0 }
    val b by state { "" }
    val c by state { listOf<Int>() }
}

/** Plain user middleware — see TypedViewsTest.middlewareEventsOfReturnsEmptyForUserClasses. */
private class NoopMiddleware : Middleware<MultiVault>()

class TypedViewsTest {

    @Test
    fun transactionsViewFiltersToTransactionEvents() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action { a mutate 1 }
        ctr.action { b mutate "x" }

        val tx = ctr.transactions
        // 2 starts + 2 commits = 4 transaction events; the type system already
        // guarantees these are TransactionEvent instances (filterIsInstance).
        assertEquals(4, tx.size, "expected 4 transaction events, got $tx")
        assertEquals(2, tx.filterIsInstance<TransactionStarted>().size)
        assertEquals(2, tx.filterIsInstance<TransactionCommitted>().size)
    }

    @Test
    fun emissionsFilterByPropertyReference() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action {
            a mutate 1
            b mutate "x"
        }
        ctr.action { a mutate 2 }
        ctr.action { c mutate listOf(1, 2, 3) }

        // a was mutated in 2 actions, b in 1, c in 1.
        assertEquals(2, ctr.emissions(MultiVault::a).size)
        assertEquals(1, ctr.emissions(MultiVault::b).size)
        assertEquals(1, ctr.emissions(MultiVault::c).size)
    }

    @Test
    fun emissionsCarryOldAndNewValues() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action { a mutate 7 }
        ctr.action { a mutate 12 }

        val em = ctr.emissions(MultiVault::a)
        assertEquals(2, em.size)
        // First emission: 0 -> 7
        assertEquals(0, em[0].oldValue)
        assertEquals(7, em[0].newValue)
        // Second emission: 7 -> 12
        assertEquals(7, em[1].oldValue)
        assertEquals(12, em[1].newValue)
    }

    @Test
    fun emissionEventStateIsExactReference() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action { a mutate 1 }
        val em = ctr.emissions(MultiVault::a).single()
        assertSame(ctr.vault.a, em.state)
    }

    @Test
    fun bridgeEventsViewIsEmptyInV1() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action {
            a mutate 1
            b mutate "x"
        }
        // No bridge attached to either state, so this view is empty for both.
        assertTrue(ctr.bridgeEvents(MultiVault::a).isEmpty())
        assertTrue(ctr.bridgeEvents(MultiVault::b).isEmpty())
    }

    @Test
    fun middlewareEventsOfReturnsEmptyForUserClasses() = vaultTest {
        // v1 caveat: user middlewares installed via vault.middlewares() are
        // NOT auto-wrapped — :vault has no public hook to enumerate or
        // replace entries in the chain, and Middleware.invoke is final. Their
        // lifecycle events therefore don't make it into the timeline.
        val v = MultiVault()
        val m = NoopMiddleware()
        v.middlewares(m)
        val ctr = track(v)
        ctr.action { a mutate 1 }

        // The typed view for any user class returns empty in v1.
        assertEquals(0, ctr.middlewareEventsOf<NoopMiddleware>().size)
        // The instance overload also returns empty for user-installed middlewares.
        assertEquals(0, ctr.middlewareEventsOf(m).size)
    }

    @Test
    fun middlewareEventsOfCapturesRecorderSelfEvents() = vaultTest {
        // The recorder DOES push self-events for itself (so the typed view is
        // not entirely empty). Sanity-check by asking for ALL middleware events
        // and verifying we got Started + Completed for each action.
        val ctr = track(MultiVault())
        ctr.action { a mutate 1 }

        val all = ctr.timeline.filterIsInstance<MiddlewareEvent>()
        // 1 Started + 1 Completed for the recorder itself.
        assertEquals(2, all.size, "expected recorder self-events, got $all")
        assertTrue(all.any { it is MiddlewareStarted })
        assertTrue(all.any { it is MiddlewareCompleted })
    }

    @Test
    fun emissionsEmptyWhenStateNotMutated() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action { a mutate 1 } // only 'a'

        assertTrue(ctr.emissions(MultiVault::b).isEmpty())
        assertTrue(ctr.emissions(MultiVault::c).isEmpty())
    }

    @Test
    fun timelinePreservesPushOrder() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action { a mutate 1 }
        ctr.action { b mutate "y" }

        // For each action: TxStarted before EmissionEvent before TxCommitted.
        val tl = ctr.timeline
        // First action's TxStarted index must precede first EmissionEvent index.
        val firstStartIdx = tl.indexOfFirst { it is TransactionStarted }
        val firstEmissionIdx = tl.indexOfFirst { it is EmissionEvent }
        val firstCommitIdx = tl.indexOfFirst { it is TransactionCommitted }
        assertTrue(firstStartIdx < firstEmissionIdx, "TxStarted must precede EmissionEvent in $tl")
        assertTrue(firstEmissionIdx < firstCommitIdx, "EmissionEvent must precede TxCommitted in $tl")
    }

    @Test
    fun emissionsIncludeAllStatesInModifiedSet() = vaultTest {
        val ctr = track(MultiVault())
        ctr.action {
            a mutate 5
            b mutate "x"
            c mutate listOf(1)
        }
        // Single transaction mutating 3 states -> 3 EmissionEvents.
        val em = ctr.timeline.filterIsInstance<EmissionEvent>()
        assertEquals(3, em.size)

        // Verify each state appears exactly once.
        assertEquals(1, em.count { it.state === ctr.vault.a })
        assertEquals(1, em.count { it.state === ctr.vault.b })
        assertEquals(1, em.count { it.state === ctr.vault.c })
    }

    @Test
    fun lastResultExistsForBothSuccessAndError() = vaultTest {
        val ctr = track(MultiVault())
        val ok = ctr.action { a mutate 1 }
        assertNotNull(ctr.lastResult)
        assertSame(ok, ctr.lastResult)

        val err = ctr.action<Unit> { throw IllegalStateException("x") }
        ctr.consumeAllPendingErrors()
        assertSame(err, ctr.lastResult)
    }
}
