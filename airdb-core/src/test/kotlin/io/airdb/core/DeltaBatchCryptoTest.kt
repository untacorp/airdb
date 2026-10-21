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

package io.airdb.core

import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import io.airdb.core.model.DeltaBatch
import io.airdb.core.model.sign
import io.airdb.core.model.verifySignature
import io.airdb.crypto.Ed25519Signer
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeltaBatchCryptoTest {

    @Test
    fun testUnsignedDeltaBatchRoundtrip() {
        val record = ChangeRecord(
            id = "item-101",
            collection = "supplies",
            type = MutationType.INSERT,
            payload = """{"item":"bandage","qty":50}""".toByteArray(Charsets.UTF_8),
            hlcTimestamp = "1710928300000-0-medicNode"
        )

        val serialized = DeltaBatch.serialize(listOf(record))
        val deserialized = DeltaBatch.deserialize(serialized)

        assertEquals(1, deserialized.size)
        val decoded = deserialized[0]
        assertEquals(record.id, decoded.id)
        assertEquals(record.collection, decoded.collection)
        assertEquals(record.type, decoded.type)
        assertEquals(record.hlcTimestamp, decoded.hlcTimestamp)
        assertContentEquals(record.payload, decoded.payload)
        assertFalse(decoded.isSigned)
    }

    @Test
    fun testSignedDeltaBatchRoundtripAndVerification() {
        val keyPair = Ed25519Signer.generateKeyPair()

        val unsignedRecord = ChangeRecord(
            id = "patient-777",
            collection = "triage",
            type = MutationType.UPDATE,
            payload = """{"status":"RED","pulse":120}""".toByteArray(Charsets.UTF_8),
            hlcTimestamp = "1710928300100-0-doctorA"
        )
        val signedRecord = unsignedRecord.sign(keyPair)
        assertTrue(signedRecord.isSigned)
        assertTrue(signedRecord.verifySignature())

        val serialized = DeltaBatch.serialize(listOf(signedRecord))
        val deserialized = DeltaBatch.deserialize(serialized)

        assertEquals(1, deserialized.size)
        val decoded = deserialized[0]
        assertTrue(decoded.isSigned)
        assertEquals(signedRecord.id, decoded.id)
        assertEquals(signedRecord.collection, decoded.collection)
        assertEquals(signedRecord.type, decoded.type)
        assertEquals(signedRecord.hlcTimestamp, decoded.hlcTimestamp)
        assertContentEquals(signedRecord.payload, decoded.payload)
        assertContentEquals(keyPair.publicKeyRaw, decoded.authorPublicKey)
        assertTrue(decoded.verifySignature())
    }

    @Test
    fun testTamperingInvalidatesSignature() {
        val keyPair = Ed25519Signer.generateKeyPair()

        val record = ChangeRecord(
            id = "patient-888",
            collection = "triage",
            type = MutationType.UPDATE,
            payload = """{"status":"YELLOW"}""".toByteArray(Charsets.UTF_8),
            hlcTimestamp = "1710928300200-0-nurseB"
        ).sign(keyPair)

        assertTrue(record.verifySignature())

        // 1. Tampering with payload
        val forgedPayload = record.copy(payload = """{"status":"BLACK"}""".toByteArray(Charsets.UTF_8))
        assertFalse(forgedPayload.verifySignature(), "Tampered payload must fail signature verification")

        // 2. Tampering with HLC timestamp
        val forgedHlc = record.copy(hlcTimestamp = "1710928300201-0-nurseB")
        assertFalse(forgedHlc.verifySignature(), "Forged HLC must fail signature verification")

        // 3. Tampering with ID
        val forgedId = record.copy(id = "patient-999")
        assertFalse(forgedId.verifySignature(), "Forged ID must fail signature verification")

        // 4. Tampering with mutation type
        val forgedType = record.copy(type = MutationType.DELETE)
        assertFalse(forgedType.verifySignature(), "Forged MutationType must fail signature verification")
    }

    @Test
    fun testMixedSignedAndUnsignedBatch() {
        val keyPair = Ed25519Signer.generateKeyPair()

        val signed = ChangeRecord(
            id = "sig-1",
            collection = "data",
            type = MutationType.INSERT,
            payload = "signed data".toByteArray(Charsets.UTF_8),
            hlcTimestamp = "100-0-node1"
        ).sign(keyPair)

        val unsigned = ChangeRecord(
            id = "unsig-2",
            collection = "data",
            type = MutationType.UPDATE,
            payload = "unsigned data".toByteArray(Charsets.UTF_8),
            hlcTimestamp = "101-0-node2"
        )

        val batch = listOf(signed, unsigned)
        val serialized = DeltaBatch.serialize(batch)
        val deserialized = DeltaBatch.deserialize(serialized)

        assertEquals(2, deserialized.size)
        assertTrue(deserialized[0].isSigned)
        assertTrue(deserialized[0].verifySignature())

        assertFalse(deserialized[1].isSigned)
        assertFalse(deserialized[1].verifySignature())
    }
}
