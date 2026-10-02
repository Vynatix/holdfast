@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi

/**
 * One finding of `Root.verifyPersistedNames(node)` (T6): a name that
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
         * `persistenceKeys` — sits at a leaf named by its class
         * (`NameOrigin.ClassName`). Pin it: `branch(store).named(store, "...")`.
         */
        ClassDerivedNameOnPersistedStore,

        /** A keyed branch declared without a key codec: `encode()` skips it, empty or not. */
        KeyedBranchNotEncodable,
    }

    override fun toString(): String = "NamingIssue($kind at ${node.name}: $message)"
}
