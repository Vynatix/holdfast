@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi

/**
 * One finding of `tree.verifyPersistedNames(node)` (T6): a name that
 * `encode()` writes and that the program's identifiers, not literals,
 * chose — so obfuscation or a rename would orphan what was persisted under
 * it — or a keyed branch whose leaves can never be written at all.
 */
@ExperimentalStoreApi
class NamingIssue internal constructor(
    val node: StoreNode,
    val kind: Kind,
    val message: String,
) {
    @ExperimentalStoreApi
    enum class Kind {
        /**
         * A persisted store — one with a `UserAuthored` state or family,
         * one that `is SchemaVersioned`, or one whose attachments report
         * `persistenceKeys` — sits at a group leaf named by its class
         * (`NameOrigin.ClassName`). Pin it in the group lambda:
         * `group { listOf(SettingsStore() named "settings") }`.
         */
        ClassDerivedNameOnPersistedStore,

        /**
         * A `store { }` child, a `group { }` or a `keyed { }` branch named by
         * its Kotlin property (`NameOrigin.Property`) holds a persisted store
         * at or under it: renaming or obfuscating the property orphans what
         * was persisted under the name. Pin it with `named =`
         * (`store(named = "...")`, `group(named = "...")`, `keyed(named = "...")`).
         */
        PropertyDerivedNameOnPersistedSubtree,

        /**
         * The receiver is a top-level store (its node name is its class name
         * minus `Store`), its subtree holds a persisted store, and it does
         * not implement [TreeIdentified]: `encode()` writes the receiver's
         * class-derived name, and `decode` refuses the text once the class
         * is renamed or obfuscated. Implement [TreeIdentified].
         */
        ReceiverNameIsClassDerived,

        /** A keyed branch declared without a key codec: `encode()` skips it, empty or not. */
        KeyedBranchNotEncodable,
    }

    override fun toString(): String = "NamingIssue($kind at ${node.name}: $message)"
}
