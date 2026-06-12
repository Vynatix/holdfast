// Twin of GUIDE.md §14.10 "Encrypted-at-rest credential". Compile-only.
package com.vynatix.holdfast.snippets.twins.guideidiomcreds

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.FileSystemKvStore
import com.vynatix.holdfast.bridge.KvBridge
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.Cipher
import com.vynatix.holdfast.crypto.EncryptingTransformer

// Scaffold: the doc names a production AES cipher it does not define.
class SystemAesCipher : Cipher {
    override fun encrypt(plaintext: String): String = plaintext.reversed()

    override fun decrypt(ciphertext: String): String = ciphertext.reversed()
}

// Scaffold: the doc block mutates a `holdfast` instance it never constructs,
// typed as the store class the block itself declares. Pre-declaring an
// identical store here lets the trailing statement typecheck; the block's own
// (local) CredsStore shadows this one, which is exactly the intent.
class CredsStore : Store<CredsStore>() {
    val token by state(EncryptingTransformer(SystemAesCipher())) { "" }
}

private val home: String get() = System.getProperty("user.home")

@Suppress("unused")
private fun encryptedAtRestCredential() {
    val holdfast = CredsStore()
    // DOC-SNIPPET holdfast/GUIDE.md#44
    class CredsStore : Store<CredsStore>() {
        val token by state(EncryptingTransformer(SystemAesCipher())) { "" }
    }
    val kv = FileSystemKvStore("$home/.app/creds")
    holdfast { token bridge KvBridge(kv, "session", StringCodec) }
    // token is plaintext on read; persisted file contains ciphertext.
    // DOC-SNIPPET-END
}
