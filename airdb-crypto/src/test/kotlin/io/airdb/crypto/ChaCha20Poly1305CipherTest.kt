/*
 * Copyright 2026 AirDB Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.airdb.crypto

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChaCha20Poly1305CipherTest {

    private val random = SecureRandom()

    private fun generateKey(): ByteArray {
        val key = ByteArray(32)
        random.nextBytes(key)
        return key
    }

    @Test
    fun testEncryptionAndDecryptionRoundtrip() {
        val key = generateKey()
        val plaintext = "AirDB Mesh Confidential Payload: Patient #1042 vitals stable".toByteArray(Charsets.UTF_8)
        val aad = "packet-header-metadata-aad".toByteArray(Charsets.UTF_8)

        val ciphertextWithNonceAndTag = ChaCha20Poly1305Cipher.encrypt(plaintext, key, aad)

        // Nonce(12) + Plaintext.size + Tag(16)
        assertEquals(12 + plaintext.size + 16, ciphertextWithNonceAndTag.size)

        val decrypted = ChaCha20Poly1305Cipher.decrypt(ciphertextWithNonceAndTag, key, aad)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun testDecryptionFailsOnAadMismatch() {
        val key = generateKey()
        val plaintext = "Triage update".toByteArray(Charsets.UTF_8)
        val originalAad = "header-v1-sender-100".toByteArray(Charsets.UTF_8)
        val alteredAad = "header-v1-sender-999".toByteArray(Charsets.UTF_8)

        val encrypted = ChaCha20Poly1305Cipher.encrypt(plaintext, key, originalAad)

        assertThrows<AirDbCryptoException> {
            ChaCha20Poly1305Cipher.decrypt(encrypted, key, alteredAad)
        }
    }

    @Test
    fun testDecryptionFailsOnCiphertextBitFlip() {
        val key = generateKey()
        val plaintext = "Coordinates: Lat 37.7749, Lng -122.4194".toByteArray(Charsets.UTF_8)

        val encrypted = ChaCha20Poly1305Cipher.encrypt(plaintext, key)

        // Flip a bit in the middle of ciphertext
        val corrupted = encrypted.clone()
        val flipIndex = 12 + (plaintext.size / 2)
        corrupted[flipIndex] = (corrupted[flipIndex].toInt() xor 0x01).toByte()

        assertThrows<AirDbCryptoException> {
            ChaCha20Poly1305Cipher.decrypt(corrupted, key)
        }
    }

    @Test
    fun testDecryptionFailsOnWrongKey() {
        val keyA = generateKey()
        val keyB = generateKey()
        val plaintext = "Sensitive disaster rescue plan".toByteArray(Charsets.UTF_8)

        val encrypted = ChaCha20Poly1305Cipher.encrypt(plaintext, keyA)

        assertThrows<AirDbCryptoException> {
            ChaCha20Poly1305Cipher.decrypt(encrypted, keyB)
        }
    }

    @Test
    fun testDecryptionFailsOnTruncatedPayload() {
        val key = generateKey()
        val plaintext = "Short message".toByteArray(Charsets.UTF_8)
        val encrypted = ChaCha20Poly1305Cipher.encrypt(plaintext, key)

        val truncated = encrypted.copyOfRange(0, 20) // Less than Nonce(12) + Tag(16) = 28 bytes

        assertThrows<AirDbCryptoException> {
            ChaCha20Poly1305Cipher.decrypt(truncated, key)
        }
    }

    @Test
    fun testEmptyPlaintextEncryption() {
        val key = generateKey()
        val plaintext = ByteArray(0)

        val encrypted = ChaCha20Poly1305Cipher.encrypt(plaintext, key)
        assertEquals(12 + 16, encrypted.size) // Nonce + Tag

        val decrypted = ChaCha20Poly1305Cipher.decrypt(encrypted, key)
        assertEquals(0, decrypted.size)
    }
}
