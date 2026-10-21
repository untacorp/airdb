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

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays

/**
 * Raw 32-byte Ed25519 key pair container.
 */
data class Ed25519KeyPair(
    val privateKeyRaw: ByteArray,
    val publicKeyRaw: ByteArray
) {
    init {
        require(privateKeyRaw.size == 32) { "Private key must be 32 bytes" }
        require(publicKeyRaw.size == 32) { "Public key must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Ed25519KeyPair
        return privateKeyRaw.contentEquals(other.privateKeyRaw) &&
                publicKeyRaw.contentEquals(other.publicKeyRaw)
    }

    override fun hashCode(): Int {
        var result = privateKeyRaw.contentHashCode()
        result = 31 * result + publicKeyRaw.contentHashCode()
        return result
    }
}

/**
 * Ed25519 asymmetric digital signature utility per RFC 8032.
 *
 * Operates on raw 32-byte keys and 64-byte signatures for direct wire parity with Apple CryptoKit.
 */
object Ed25519Signer {

    const val PRIVATE_KEY_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    // RFC 8410 ASN.1 prefix for X.509 SubjectPublicKeyInfo (12 bytes)
    private val X509_ED25519_PREFIX = byteArrayOf(
        0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(),
        0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(),
        0x70.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
    )

    // RFC 8410 ASN.1 prefix for PKCS#8 PrivateKeyInfo (16 bytes)
    private val PKCS8_ED25519_PREFIX = byteArrayOf(
        0x30.toByte(), 0x2e.toByte(), 0x02.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x30.toByte(), 0x05.toByte(), 0x06.toByte(),
        0x03.toByte(), 0x2b.toByte(), 0x65.toByte(), 0x70.toByte(),
        0x04.toByte(), 0x22.toByte(), 0x04.toByte(), 0x20.toByte()
    )

    /**
     * Generates a fresh random Ed25519 key pair.
     */
    fun generateKeyPair(): Ed25519KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()

        val privEncoded = kp.private.encoded
        val pubEncoded = kp.public.encoded

        // Extract raw 32 bytes from PKCS#8 (offset 16) and X.509 (offset 12)
        val privateKeyRaw = privEncoded.copyOfRange(16, 16 + PRIVATE_KEY_SIZE)
        val publicKeyRaw = pubEncoded.copyOfRange(12, 12 + PUBLIC_KEY_SIZE)

        return Ed25519KeyPair(privateKeyRaw, publicKeyRaw)
    }

    /**
     * Signs a message with a 32-byte Ed25519 private key seed.
     *
     * @param message Canonical message bytes to sign.
     * @param privateKeyRaw 32-byte raw private key.
     * @return 64-byte Ed25519 signature.
     */
    fun sign(message: ByteArray, privateKeyRaw: ByteArray): ByteArray {
        require(privateKeyRaw.size == PRIVATE_KEY_SIZE) {
            "Private key must be exactly $PRIVATE_KEY_SIZE bytes, got ${privateKeyRaw.size}"
        }

        try {
            val pkcs8Bytes = ByteArray(PKCS8_ED25519_PREFIX.size + PRIVATE_KEY_SIZE)
            System.arraycopy(PKCS8_ED25519_PREFIX, 0, pkcs8Bytes, 0, PKCS8_ED25519_PREFIX.size)
            System.arraycopy(privateKeyRaw, 0, pkcs8Bytes, PKCS8_ED25519_PREFIX.size, PRIVATE_KEY_SIZE)

            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))

            val signature = Signature.getInstance("Ed25519")
            signature.initSign(privateKey)
            signature.update(message)
            val sig = signature.sign()

            check(sig.size == SIGNATURE_SIZE) { "Expected signature size $SIGNATURE_SIZE, got ${sig.size}" }
            return sig
        } catch (e: Exception) {
            throw AirDbCryptoException("Failed to generate Ed25519 signature: ${e.message}", e)
        }
    }

    /**
     * Verifies an Ed25519 signature against the message and author's 32-byte raw public key.
     *
     * @param message Canonical message bytes that were signed.
     * @param signature 64-byte signature to verify.
     * @param publicKeyRaw 32-byte author public key.
     * @return true if signature is valid; false otherwise.
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKeyRaw: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE || publicKeyRaw.size != PUBLIC_KEY_SIZE) {
            return false
        }

        return try {
            val x509Bytes = ByteArray(X509_ED25519_PREFIX.size + PUBLIC_KEY_SIZE)
            System.arraycopy(X509_ED25519_PREFIX, 0, x509Bytes, 0, X509_ED25519_PREFIX.size)
            System.arraycopy(publicKeyRaw, 0, x509Bytes, X509_ED25519_PREFIX.size, PUBLIC_KEY_SIZE)

            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(x509Bytes))

            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates a canonical byte representation of a database change for signing and verification.
     */
    fun canonicalChangeDigest(
        id: String,
        collection: String,
        typeOrdinal: Byte,
        hlcTimestamp: String,
        payload: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("airdb:v1:change\n".toByteArray(Charsets.UTF_8))
        out.write(id.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.write(collection.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.write(typeOrdinal.toInt())
        out.write(0)
        out.write(hlcTimestamp.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.write(payload)
        return out.toByteArray()
    }
}
