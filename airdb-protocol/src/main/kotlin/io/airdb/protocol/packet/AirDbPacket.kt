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

package io.airdb.protocol.packet

/**
 * Flag bitmask values for AirDB packets.
 */
object PacketFlags {
    /** Indicates the payload is compressed using Deflate. */
    const val FLAG_COMPRESSED: Byte = 0x01

    /** Indicates the packet contains a specific 8-byte recipient ID. */
    const val FLAG_HAS_RECIPIENT: Byte = 0x02

    /** Indicates the payload is encrypted using ChaCha20-Poly1305 AEAD. */
    const val FLAG_ENCRYPTED: Byte = 0x04
}

/**
 * Fixed envelope for AirDB packets transmitted across BLE links.
 *
 * Binary header layout (15 or 23 bytes):
 * ```
 * +------------+---------+------+-------+-----------+----------------+-----------+--------------------+
 * | Magic (2B) | Ver(1B) | Type | Flags | Timestamp | Payload Length | Sender ID | Recipient ID (opt) |
 * | 0x41 0x44  | 0x01    | 1B   | 1B    | 8B        | 2B (UInt16)    | 8B        | 8B                 |
 * +------------+---------+------+-------+-----------+----------------+-----------+--------------------+
 * ```
 */
data class AirDbPacket(
    val version: Byte = 1,
    val type: PacketType,
    val flags: Byte = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val senderId: Long,
    val recipientId: Long? = null,
    val payload: ByteArray
) {
    val isCompressed: Boolean get() = (flags.toInt() and PacketFlags.FLAG_COMPRESSED.toInt()) != 0
    val hasRecipient: Boolean get() = recipientId != null
    val isEncrypted: Boolean get() = (flags.toInt() and PacketFlags.FLAG_ENCRYPTED.toInt()) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AirDbPacket

        if (version != other.version) return false
        if (type != other.type) return false
        if (flags != other.flags) return false
        if (timestamp != other.timestamp) return false
        if (senderId != other.senderId) return false
        if (recipientId != other.recipientId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + flags.toInt()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + (recipientId?.hashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
