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

package io.airdb.protocol.codec

import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketFlags
import io.airdb.protocol.packet.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire format encoder and decoder for AirDB packets.
 *
 * All multi-byte values use Network Byte Order (Big-Endian).
 */
object BinaryCodec {

    const val MAGIC_BYTE_0: Byte = 0x41 // 'A'
    const val MAGIC_BYTE_1: Byte = 0x44 // 'D'
    const val MIN_HEADER_SIZE = 23 // Magic(2) + Ver(1) + Type(1) + Flags(1) + Ts(8) + Len(2) + Sender(8)
    const val RECIPIENT_ID_SIZE = 8
    const val MAX_PAYLOAD_SIZE = 65535

    /**
     * Serializes an [AirDbPacket] into binary representation for BLE transmission.
     */
    fun encode(packet: AirDbPacket, autoCompressThreshold: Int = 256): ByteArray {
        var flags = packet.flags.toInt()
        var payloadBytes = packet.payload

        // Auto compress if payload is above threshold and not already marked compressed
        if (payloadBytes.size > autoCompressThreshold && (flags and PacketFlags.FLAG_COMPRESSED.toInt()) == 0) {
            val compressed = Compression.compress(payloadBytes)
            if (compressed.size < payloadBytes.size) {
                payloadBytes = compressed
                flags = flags or PacketFlags.FLAG_COMPRESSED.toInt()
            }
        }

        if (packet.recipientId != null) {
            flags = flags or PacketFlags.FLAG_HAS_RECIPIENT.toInt()
        }

        require(payloadBytes.size <= MAX_PAYLOAD_SIZE) {
            "Payload exceeds max allowable size $MAX_PAYLOAD_SIZE: ${payloadBytes.size}"
        }

        val hasRecipient = packet.recipientId != null
        val totalSize = MIN_HEADER_SIZE + (if (hasRecipient) RECIPIENT_ID_SIZE else 0) + payloadBytes.size

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_BYTE_0)
        buffer.put(MAGIC_BYTE_1)
        buffer.put(packet.version)
        buffer.put(packet.type.code)
        buffer.put(flags.toByte())
        buffer.putLong(packet.timestamp)
        buffer.putShort(payloadBytes.size.toShort())
        buffer.putLong(packet.senderId)

        if (hasRecipient) {
            buffer.putLong(packet.recipientId!!)
        }

        buffer.put(payloadBytes)
        return buffer.array()
    }

    /**
     * Deserializes binary wire data into an [AirDbPacket].
     * Returns null if data is corrupt or does not match AirDB framing.
     */
    fun decode(bytes: ByteArray): AirDbPacket? {
        if (bytes.size < MIN_HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val m0 = buffer.get()
        val m1 = buffer.get()
        if (m0 != MAGIC_BYTE_0 || m1 != MAGIC_BYTE_1) return null

        val version = buffer.get()
        val typeCode = buffer.get()
        val type = PacketType.fromCode(typeCode) ?: return null

        val flags = buffer.get()
        val timestamp = buffer.getLong()
        val payloadLength = buffer.getShort().toInt() and 0xFFFF
        val senderId = buffer.getLong()

        val hasRecipient = (flags.toInt() and PacketFlags.FLAG_HAS_RECIPIENT.toInt()) != 0
        val recipientId = if (hasRecipient) {
            if (buffer.remaining() < RECIPIENT_ID_SIZE) return null
            buffer.getLong()
        } else {
            null
        }

        if (buffer.remaining() < payloadLength) return null

        var payload = ByteArray(payloadLength)
        buffer.get(payload)

        // Decompress if compressed
        val isCompressed = (flags.toInt() and PacketFlags.FLAG_COMPRESSED.toInt()) != 0
        if (isCompressed) {
            try {
                payload = Compression.decompress(payload)
            } catch (e: Exception) {
                return null
            }
        }

        return AirDbPacket(
            version = version,
            type = type,
            flags = flags,
            timestamp = timestamp,
            senderId = senderId,
            recipientId = recipientId,
            payload = payload
        )
    }
}
