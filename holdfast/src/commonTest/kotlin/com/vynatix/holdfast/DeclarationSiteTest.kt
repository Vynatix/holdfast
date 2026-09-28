@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.reflect.KProperty
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #20, R5 and R7: a store's states and keyed state families share one
// set of names and one rule for telling the same declaration site running
// again from a second declaration of a name. Both registries call the rule
// (DeclarationSite.isSameSiteAs); this pins the rule itself.

/** A declaration site by hand: all the predicate sees of a declaration. */
private class Site(
    override val local: Boolean,
    override val property: KProperty<*>?,
) : DeclarationSite

private class Holder {
    val first = 0
    val second = 0
}

private class SiteStore : Store<SiteStore>() {
    val member by state { 0 }
    val docs by keyedState<Int, Int> { 0 }
}

class DeclarationSiteTest {
    private val first: KProperty<*> = Holder::first
    private val second: KProperty<*> = Holder::second

    @Test fun theSameMemberPropertyIsOneSite() {
        assertTrue(Site(local = false, first) isSameSiteAs Site(local = false, first))
    }

    @Test fun twoMemberPropertiesAreTwoSites() {
        assertFalse(Site(local = false, first) isSameSiteAs Site(local = false, second))
    }

    @Test fun localPropertiesOfOneNameAreOneSiteWhateverTheirReferences() {
        // The registries keep one declaration per name, so two locals under one
        // name are one site — by name alone, since Kotlin/Native hands out no
        // stable property reference per local declaration site.
        assertTrue(Site(local = true, first) isSameSiteAs Site(local = true, second))
        assertTrue(Site(local = true, first) isSameSiteAs Site(local = true, first))
    }

    @Test fun aLocalAndAMemberPropertyAreNeverOneSite() {
        assertFalse(Site(local = true, first) isSameSiteAs Site(local = false, first))
        assertFalse(Site(local = false, first) isSameSiteAs Site(local = true, first))
    }

    @Test fun aDeclarationWithNoPropertyRepeatsNothing() {
        // An eagerly registered state has no site; two of them are not one site
        // — whatever they say about being local (no local site lacks a property).
        assertFalse(Site(local = false, null) isSameSiteAs Site(local = false, null))
        assertFalse(Site(local = false, null) isSameSiteAs Site(local = false, first))
        assertFalse(Site(local = true, null) isSameSiteAs Site(local = true, null))
        assertFalse(Site(local = true, null) isSameSiteAs Site(local = true, first))
    }

    @Test fun stateDeclarationsAndFamiliesAreSites() {
        val store = SiteStore()
        val local: State<Int> by store.state { 1 }
        local.value
        val member = checkNotNull(store.registry.declaration("member"))
        val localDecl = checkNotNull(store.registry.declaration("local"))
        val docs = checkNotNull(store.registry.keyed.family("docs"))

        assertTrue(member isSameSiteAs member)
        assertTrue(docs isSameSiteAs docs)
        assertFalse(member isSameSiteAs localDecl, "a member and a local property")
        assertFalse(member isSameSiteAs docs, "two member properties")
    }
}
