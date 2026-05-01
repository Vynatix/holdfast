@file:Suppress("MagicNumber")

package com.vynatix.vault

import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.random.Random

class UUID {
    private val value: String

    init {
        val data = ByteArray(16).apply {
            Random.nextBytes(this)
            this[6] = ((this[6] and 0x0f) or 0x40)
            this[8] = ((this[8] and 0x3f.toByte()) or 0x80.toByte())
        }

        value = buildString {
            data.forEachIndexed { index, byte ->
                append(byte.toUByte().toString(16).padStart(2, '0'))
                when (index) {
                    3, 5, 7, 9 -> append('-')
                }
            }
        }
    }

    override fun toString(): String = value

    companion object {
        fun randomUUID(): UUID = UUID()
    }
}
