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

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 Authenticated Encryption with Associated Data (AEAD) cipher per RFC 8439.
 *
 * Output format:
 * [Nonce (12 bytes)] + [Ciphertext (N bytes)] + [Poly1305 Tag (16 bytes)]
 */
object ChaCha20Poly1305Cipher {

    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32

    private val secureRandom = SecureRandom()

    /**
     * Encrypts plaintext using ChaCha20-Poly1305 AEAD.
     *
     * @param plaintext Data to be encrypted.
     * @param key 256-bit (32-byte) symmetric encryption key.
     * @param aad Optional Authenticated Additional Data (e.g. packet header).
     * @param nonce Optional 96-bit (12-byte) initialization vector; generated randomly if null.
     * @return Byte array containing [Nonce (12B)] + [Ciphertext] + [Tag (16B)].
     */
    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        aad: ByteArray? = null,
        nonce: ByteArray? = null
    ): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be exactly $KEY_SIZE bytes, got ${key.size}" }

        val iv = if (nonce != null) {
            require(nonce.size == NONCE_SIZE) { "Nonce must be exactly $NONCE_SIZE bytes, got ${nonce.size}" }
            nonce
        } else {
            ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }
        }

        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }

            val ciphertextAndTag = cipher.doFinal(plaintext)
            val output = ByteBuffer.allocate(NONCE_SIZE + ciphertextAndTag.size)
            output.put(iv)
            output.put(ciphertextAndTag)
            return output.array()
        } catch (e: Exception) {
            throw AirDbCryptoException("Failed to encrypt data using ChaCha20-Poly1305: ${e.message}", e)
        }
    }

    /**
     * Decrypts and authenticates a ChaCha20-Poly1305 payload.
     *
     * @param encryptedPayload Byte array containing [Nonce (12B)] + [Ciphertext] + [Tag (16B)].
     * @param key 256-bit (32-byte) symmetric key.
     * @param aad Optional Authenticated Additional Data; must match the AAD used during encryption.
     * @return Decrypted plaintext bytes.
     * @throws AirDbCryptoException if authentication tag does not match or data is corrupt.
     */
    fun decrypt(
        encryptedPayload: ByteArray,
        key: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be exactly $KEY_SIZE bytes, got ${key.size}" }
        if (encryptedPayload.size < NONCE_SIZE + TAG_SIZE) {
            throw AirDbCryptoException("Payload too short for AEAD: ${encryptedPayload.size} bytes (minimum ${NONCE_SIZE + TAG_SIZE})")
        }

        val iv = encryptedPayload.copyOfRange(0, NONCE_SIZE)
        val ciphertextAndTag = encryptedPayload.copyOfRange(NONCE_SIZE, encryptedPayload.size)

        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }

            return cipher.doFinal(ciphertextAndTag)
        } catch (e: AEADBadTagException) {
            throw AirDbCryptoException("Poly1305 authentication tag verification failed. Data corrupted or tampered.", e)
        } catch (e: Exception) {
            throw AirDbCryptoException("Failed to decrypt ChaCha20-Poly1305 payload: ${e.message}", e)
        }
    }
}
