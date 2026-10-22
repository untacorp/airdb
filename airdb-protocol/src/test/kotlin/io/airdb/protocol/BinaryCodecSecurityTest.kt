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
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketFlags
import io.airdb.protocol.packet.PacketType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edge-case and security fuzzing tests for binary decoding.
 * Ensures the decoder never throws unhandled exceptions or crashes on malicious/corrupted inputs.
 */
class BinaryCodecSecurityTest {

    @Test
    fun testCorruptMagicBytesReturnsNull() {
        val validPacket = AirDbPacket(type = PacketType.HANDSHAKE, senderId = 1L, payload = byteArrayOf(1, 2, 3))
        val encoded = BinaryCodec.encode(validPacket)

        // Corrupt magic byte 0
        encoded[0] = 0x00
        assertNull(BinaryCodec.decode(encoded), "Decoder must reject packet with invalid magic byte 0")

        // Corrupt magic byte 1
        encoded[0] = BinaryCodec.MAGIC_BYTE_0
        encoded[1] = 0xFF.toByte()
        assertNull(BinaryCodec.decode(encoded), "Decoder must reject packet with invalid magic byte 1")
    }

    @Test
    fun testTruncatedHeaderReturnsNull() {
        val validPacket = AirDbPacket(type = PacketType.HANDSHAKE, senderId = 1L, payload = byteArrayOf(1, 2, 3))
        val encoded = BinaryCodec.encode(validPacket)

        // Truncate at every length from 0 up to MIN_HEADER_SIZE - 1
        for (len in 0 until BinaryCodec.MIN_HEADER_SIZE) {
            val truncated = encoded.copyOfRange(0, len)
            assertNull(BinaryCodec.decode(truncated), "Truncated header of length $len must return null")
        }
    }

    @Test
    fun testUnknownPacketTypeReturnsNull() {
        val buffer = ByteBuffer.allocate(30).order(ByteOrder.BIG_ENDIAN)
        buffer.put(BinaryCodec.MAGIC_BYTE_0)
        buffer.put(BinaryCodec.MAGIC_BYTE_1)
        buffer.put(1.toByte()) // version
        buffer.put(0x7F.toByte()) // unknown packet type code
        buffer.put(0.toByte()) // flags
        buffer.putLong(System.currentTimeMillis())
        buffer.putShort(0.toShort()) // payload len
        buffer.putLong(1234L) // sender ID

        assertNull(BinaryCodec.decode(buffer.array()), "Unknown packet type must return null cleanly")
    }

    @Test
    fun testClaimedPayloadLengthExceedsBufferReturnsNull() {
        val buffer = ByteBuffer.allocate(BinaryCodec.MIN_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(BinaryCodec.MAGIC_BYTE_0)
        buffer.put(BinaryCodec.MAGIC_BYTE_1)
        buffer.put(1.toByte())
        buffer.put(PacketType.DELTA_BATCH.code)
        buffer.put(0.toByte())
        buffer.putLong(System.currentTimeMillis())
        buffer.putShort(500.toShort()) // Claims 500 bytes of payload
        buffer.putLong(1234L)
        // But 0 bytes of payload follow!

        assertNull(BinaryCodec.decode(buffer.array()), "Payload length larger than remaining bytes must return null")
    }

    @Test
    fun testCorruptedCompressedPayloadReturnsNull() {
        val buffer = ByteBuffer.allocate(BinaryCodec.MIN_HEADER_SIZE + 10).order(ByteOrder.BIG_ENDIAN)
        buffer.put(BinaryCodec.MAGIC_BYTE_0)
        buffer.put(BinaryCodec.MAGIC_BYTE_1)
        buffer.put(1.toByte())
        buffer.put(PacketType.DELTA_BATCH.code)
        buffer.put(PacketFlags.FLAG_COMPRESSED) // Flagged as compressed
        buffer.putLong(System.currentTimeMillis())
        buffer.putShort(10.toShort())
        buffer.putLong(1234L)
        buffer.put(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9)) // Random garbage, not valid zlib

        assertNull(BinaryCodec.decode(buffer.array()), "Corrupted zlib payload must return null rather than throw")
    }

    @Test
    fun testZipBombDecompressionRejected() {
        // Create 100 KB payload of zeroes that compresses extremely small (~100 bytes)
        val largeZeroes = ByteArray(100_000)
        val compressed = io.airdb.protocol.codec.Compression.compress(largeZeroes)

        // Decompressing with default 65535 limit must throw IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            io.airdb.protocol.codec.Compression.decompress(compressed, maxDecompressedSize = 65535)
        }

        // BinaryCodec.decode must catch this and return null safely rather than crashing
        val buffer = ByteBuffer.allocate(BinaryCodec.MIN_HEADER_SIZE + compressed.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(BinaryCodec.MAGIC_BYTE_0)
        buffer.put(BinaryCodec.MAGIC_BYTE_1)
        buffer.put(1.toByte())
        buffer.put(PacketType.DELTA_BATCH.code)
        buffer.put(PacketFlags.FLAG_COMPRESSED)
        buffer.putLong(System.currentTimeMillis())
        buffer.putShort(compressed.size.toShort())
        buffer.putLong(1234L)
        buffer.put(compressed)

        assertNull(BinaryCodec.decode(buffer.array()), "Decompression exceeding 64KB must be safely rejected")
    }
}
