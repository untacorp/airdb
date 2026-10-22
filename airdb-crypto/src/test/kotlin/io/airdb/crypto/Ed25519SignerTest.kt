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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519SignerTest {

    @Test
    fun testKeyPairGenerationRawSizes() {
        val kp = Ed25519Signer.generateKeyPair()
        assertEquals(32, kp.privateKeyRaw.size)
        assertEquals(32, kp.publicKeyRaw.size)
    }

    @Test
    fun testSignAndVerifySuccess() {
        val kp = Ed25519Signer.generateKeyPair()
        val message = "airdb:change:patient-1:status=STABLE:hlc=100-0-nodeA".toByteArray(Charsets.UTF_8)

        val signature = Ed25519Signer.sign(message, kp.privateKeyRaw)
        assertEquals(64, signature.size)

        val isValid = Ed25519Signer.verify(message, signature, kp.publicKeyRaw)
        assertTrue(isValid)
    }

    @Test
    fun testVerifyFailsOnTamperedMessage() {
        val kp = Ed25519Signer.generateKeyPair()
        val originalMessage = "amount=100".toByteArray(Charsets.UTF_8)
        val tamperedMessage = "amount=999".toByteArray(Charsets.UTF_8)

        val signature = Ed25519Signer.sign(originalMessage, kp.privateKeyRaw)

        val isValid = Ed25519Signer.verify(tamperedMessage, signature, kp.publicKeyRaw)
        assertFalse(isValid)
    }

    @Test
    fun testVerifyFailsOnTamperedSignature() {
        val kp = Ed25519Signer.generateKeyPair()
        val message = "critical rescue coordinates".toByteArray(Charsets.UTF_8)

        val signature = Ed25519Signer.sign(message, kp.privateKeyRaw)
        val corruptedSignature = signature.clone()
        corruptedSignature[10] = (corruptedSignature[10].toInt() xor 0xFF).toByte()

        val isValid = Ed25519Signer.verify(message, corruptedSignature, kp.publicKeyRaw)
        assertFalse(isValid)
    }

    @Test
    fun testVerifyFailsOnDifferentPublicKey() {
        val kp1 = Ed25519Signer.generateKeyPair()
        val kp2 = Ed25519Signer.generateKeyPair()
        val message = "authentic message".toByteArray(Charsets.UTF_8)

        val signature = Ed25519Signer.sign(message, kp1.privateKeyRaw)

        val isValid = Ed25519Signer.verify(message, signature, kp2.publicKeyRaw)
        assertFalse(isValid)
    }

    @Test
    fun testCanonicalDigestIntegration() {
        val kp = Ed25519Signer.generateKeyPair()
        val payload = """{"triage":"RED","bed":4}""".toByteArray(Charsets.UTF_8)

        val digest = Ed25519Signer.canonicalChangeDigest(
            id = "patient-uuid-1234",
            collection = "triages",
            typeOrdinal = 1,
            hlcTimestamp = "1710928300000-0-medicNode",
            payload = payload
        )

        val signature = Ed25519Signer.sign(digest, kp.privateKeyRaw)
        assertTrue(Ed25519Signer.verify(digest, signature, kp.publicKeyRaw))

        // Any change in hlcTimestamp must invalidate verification
        val forgedDigest = Ed25519Signer.canonicalChangeDigest(
            id = "patient-uuid-1234",
            collection = "triages",
            typeOrdinal = 1,
            hlcTimestamp = "1710928300001-0-medicNode", // forged HLC
            payload = payload
        )
        assertFalse(Ed25519Signer.verify(forgedDigest, signature, kp.publicKeyRaw))
    }

    @Test
    fun testConcurrentSignAndVerify() {
        val kp = Ed25519Signer.generateKeyPair()
        val threadCount = 8
        val iterations = 50
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until iterations) {
                        val msg = "Message-$i-$j".toByteArray(Charsets.UTF_8)
                        val sig = Ed25519Signer.sign(msg, kp.privateKeyRaw)
                        if (!Ed25519Signer.verify(msg, sig, kp.publicKeyRaw)) {
                            errors.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errors.get(), "Errors occurred during concurrent signing/verification")
    }
}
