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

package io.airdb.protocol

import io.airdb.protocol.codec.BinaryCodec
import io.airdb.protocol.fragment.AssemblyBuffer
import io.airdb.protocol.fragment.PacketFragmenter
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BinaryCodecTest {

    @Test
    fun testEncodeDecodeRoundtripBroadcast() {
        val payload = "Hello AirDB P2P BLE World!".toByteArray()
        val packet = AirDbPacket(
            version = 1,
            type = PacketType.HANDSHAKE,
            senderId = 0x1234567890ABCDEFL,
            recipientId = null,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet)
        val decoded = BinaryCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(packet.version, decoded!!.version)
        assertEquals(packet.type, decoded.type)
        assertEquals(packet.senderId, decoded.senderId)
        assertNull(decoded.recipientId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun testEncodeDecodeRoundtripWithRecipient() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = AirDbPacket(
            version = 1,
            type = PacketType.DELTA_BATCH,
            senderId = 111L,
            recipientId = 222L,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet)
        val decoded = BinaryCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(111L, decoded!!.senderId)
        assertEquals(222L, decoded.recipientId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun testAutoCompressionForLargePayload() {
        // Highly repetitive string that compresses well
        val largeString = "AIRDB_COMPRESSION_TEST_KEY_VALUE_RECORD_DATA_".repeat(20)
        val payload = largeString.toByteArray()

        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 999L,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet, autoCompressThreshold = 100)
        assertTrue(encoded.size < payload.size, "Encoded packet should be smaller due to zlib compression")

        val decoded = BinaryCodec.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
    }

    @Test
    fun testFragmentationAndReassembly() {
        // Create 2KB payload that exceeds BLE safe chunk size
        val rawData = ByteArray(2000) { (it % 256).toByte() }
        val original = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 777L,
            recipientId = 888L,
            payload = rawData
        )

        val fragments = PacketFragmenter.fragment(original, maxChunkSize = 300)
        assertTrue(fragments.size > 1, "Should split into multiple fragments")

        val assemblyBuffer = AssemblyBuffer()
        var assembled: AirDbPacket? = null

        // Ingest shuffled fragments to test out-of-order reassembly
        for (fragment in fragments.reversed()) {
            val res = assemblyBuffer.ingest(fragment)
            if (res != null) {
                assembled = res
            }
        }

        assertNotNull(assembled, "Packet should successfully reassemble")
        assertEquals(original.type, assembled!!.type)
        assertEquals(original.senderId, assembled.senderId)
        assertEquals(original.recipientId, assembled.recipientId)
        assertArrayEquals(original.payload, assembled.payload)
    }

    @Test
    fun testEncryptedPacketEncodeDecodeRoundtrip() {
        val clusterKey = ByteArray(32) { (it * 3).toByte() }
        val payload = "Confidential disaster survivor vitals: priority RED".toByteArray(Charsets.UTF_8)
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 12345L,
            recipientId = 67890L,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet, clusterKey = clusterKey)
        val decoded = BinaryCodec.decode(encoded, clusterKey = clusterKey)

        assertNotNull(decoded)
        assertTrue(decoded!!.isEncrypted)
        assertEquals(packet.type, decoded.type)
        assertEquals(packet.senderId, decoded.senderId)
        assertEquals(packet.recipientId, decoded.recipientId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun testEncryptedPacketFailsWithoutKeyOrWithWrongKey() {
        val clusterKey = ByteArray(32) { 0x42.toByte() }
        val wrongKey = ByteArray(32) { 0x99.toByte() }
        val payload = "Top secret medical inventory".toByteArray(Charsets.UTF_8)
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 100L,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet, clusterKey = clusterKey)

        // Without key must return null
        assertNull(BinaryCodec.decode(encoded, clusterKey = null))

        // With wrong key must return null (Poly1305 MAC fails)
        assertNull(BinaryCodec.decode(encoded, clusterKey = wrongKey))
    }

    @Test
    fun testEncryptedPacketFailsOnTamperedCiphertext() {
        val clusterKey = ByteArray(32) { 0x55.toByte() }
        val payload = "Sensitive search and rescue grid coordinates".toByteArray(Charsets.UTF_8)
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 888L,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet, clusterKey = clusterKey)

        // Corrupt a byte in the encrypted payload region
        val tampered = encoded.clone()
        tampered[tampered.size - 5] = (tampered[tampered.size - 5].toInt() xor 0x01).toByte()

        assertNull(BinaryCodec.decode(tampered, clusterKey = clusterKey))
    }

    @Test
    fun testEncryptedPacketFailsOnTamperedAadHeader() {
        val clusterKey = ByteArray(32) { 0x77.toByte() }
        val payload = "Authentic field triage order".toByteArray(Charsets.UTF_8)
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 555L,
            payload = payload
        )

        val encoded = BinaryCodec.encode(packet, clusterKey = clusterKey)

        // Tamper with the senderId inside the header (bytes 15..22)
        val tampered = encoded.clone()
        tampered[16] = (tampered[16].toInt() xor 0xFF).toByte()

        // Decryption MUST fail because the header was part of Authenticated Additional Data (AAD)
        assertNull(BinaryCodec.decode(tampered, clusterKey = clusterKey))
    }

    @Test
    fun testEncryptedPacketWithCompressionAndFragmentation() {
        val clusterKey = ByteArray(32) { (it + 1).toByte() }
        val largeRepetitive = "PATIENT_RECORD_TRIAGE_EMERGENCY_DATA_".repeat(30).toByteArray(Charsets.UTF_8)
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 999L,
            payload = largeRepetitive
        )

        val fragments = PacketFragmenter.fragment(packet, clusterKey = clusterKey, maxChunkSize = 50)
        assertTrue(fragments.size > 1, "Should be fragmented across multiple packets")

        val assemblyBuffer = AssemblyBuffer()
        var assembled: AirDbPacket? = null

        for (fragment in fragments) {
            val res = assemblyBuffer.ingest(fragment, clusterKey = clusterKey)
            if (res != null) assembled = res
        }

        assertNotNull(assembled)
        assertTrue(assembled!!.isEncrypted)
        assertTrue(assembled.isCompressed)
        assertArrayEquals(largeRepetitive, assembled.payload)
    }
}
