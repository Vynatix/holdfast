@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreMembership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private class TypingSettingsStore : Store<TypingSettingsStore>() {
    val theme by state { "light" }
}

private class TypingThreadStore(
    val id: String,
    root: TypingRoot,
) : Store<TypingThreadStore>(root.threads.at(id)) {
    val title by state { "" }
}

private class TypingTokenStore(
    token: StoreMembership<TypingTokenStore>,
) : Store<TypingTokenStore>(token)

private class TypingRoot : Root() {
    val settings by branch(TypingSettingsStore())
    val threads by keyed<String, TypingThreadStore>(under = settings)
    val tokens by keyed<String, TypingTokenStore>()
}

private class OtherTypingRoot : Root() {
    val other by branch(TypingSettingsStore())
}

/**
 * T7.1 positive compile fixtures: the tree is typed end to end. Each
 * assignment below has an explicit static type that would not compile if
 * the API leaked `Any`, `Store<*>` where the branch type is known, or a
 * string where a node is expected. The negative cases (a string into a
 * subtree operation, a foreign root's node as `under`, the wrong store
 * class into `at`) are enforced by signature review plus `CrossRootTest`
 * and `KeyedMembershipTest`; no negative-compilation harness is used.
 */
class TreeTypingCompileTest {
    @Test
    fun typedLookupAndCreateReturnTheDeclaredStoreType() {
        val root = TypingRoot()
        val created: TypingThreadStore = root.threads.create("a") { TypingThreadStore(it, root) }
        val looked: TypingThreadStore? = root[root.threads, "a"]
        val entries: Map<String, TypingThreadStore> = root.entries(root.threads)
        assertSame(created, looked)
        assertEquals(setOf("a"), entries.keys)
    }

    @Test
    fun theTokenIsTypedByTheBranchStoreType() {
        val root = TypingRoot()
        val created =
            root.tokens.create("token") { id ->
                val token: StoreMembership<TypingTokenStore> = root.tokens.at(id)
                TypingTokenStore(token)
            }
        assertSame(created, root[root.tokens, "token"])
    }

    @Test
    fun underAcceptsBranchValuesOnlyAndSubtreeOperationsTakeNodeValues() {
        val root = TypingRoot()
        val under: Branch = root.settings
        val keyed: KeyedBranch<String, TypingThreadStore> = root.threads
        val node: StoreNode = keyed
        val stores: List<Store<*>> = root.children(node)
        val leaf: LeafNode? = root.nodeOf(root.settings.stores.single())
        assertSame(under, keyed.parent)
        assertEquals(0, stores.size, "no keyed store has been created yet")
        assertSame(root.settings, leaf!!.parent)
    }

    @Test
    fun membershipIsTypedByTheBranch() {
        val root = TypingRoot()
        // `root.threads.at(id)` is a StoreMembership<TypingThreadStore>; only a
        // Store<TypingThreadStore> constructor accepts it, so a class of another
        // type cannot take it without an unchecked cast (KeyedMembershipTest
        // pins the runtime check behind such a cast).
        val error = assertFailsWith<IllegalStateException> { TypingThreadStore("bare", root) }
        assertEquals(true, "create" in error.message!!)
    }

    @Test
    fun aNodeOfAnotherRootIsRejectedByValueNotByName() {
        val root = TypingRoot()
        val other = OtherTypingRoot()
        assertFailsWith<IllegalArgumentException> { root.children(other.other) }
        assertFailsWith<IllegalArgumentException> {
            root.entries(root.threads).let {
                root[root.threads, "x"]
                root.children(other)
            }
        }
    }
}
