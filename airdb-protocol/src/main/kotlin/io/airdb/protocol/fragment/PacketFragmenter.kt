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

package io.airdb.protocol.fragment

import io.airdb.protocol.codec.BinaryCodec
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import java.util.concurrent.ThreadLocalRandom

/**
 * Splits oversized packets into link-friendly fragments and orchestrates reassembly.
 */
object PacketFragmenter {

    // Default BLE safe payload chunk size (MTU 517 - 3 ATT - 23 AirDB Header - 13 Frag Header ~ 478 bytes)
    const val DEFAULT_MAX_CHUNK_SIZE = 460

    /**
     * Splits an [AirDbPacket] into a list of smaller [AirDbPacket] instances of type [PacketType.FRAGMENT]
     * if the serialized data exceeds [maxChunkSize].
     */
    fun fragment(
        packet: AirDbPacket,
        clusterKey: ByteArray? = null,
        maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE
    ): List<AirDbPacket> {
        val encodedBytes = BinaryCodec.encode(packet, clusterKey = clusterKey)
        if (encodedBytes.size <= maxChunkSize) {
            return listOf(packet)
        }

        val fragmentId = ThreadLocalRandom.current().nextLong()
        val totalFragments = (encodedBytes.size + maxChunkSize - 1) / maxChunkSize
        val fragments = ArrayList<AirDbPacket>(totalFragments)

        for (i in 0 until totalFragments) {
            val start = i * maxChunkSize
            val end = minOf(start + maxChunkSize, encodedBytes.size)
            val chunk = encodedBytes.copyOfRange(start, end)

            val header = FragmentHeader(
                fragmentId = fragmentId,
                index = i,
                total = totalFragments,
                originalTypeCode = packet.type.code
            )

            val fragmentPayload = FragmentHeader.encode(header, chunk)

            fragments.add(
                AirDbPacket(
                    version = packet.version,
                    type = PacketType.FRAGMENT,
                    flags = 0,
                    timestamp = packet.timestamp,
                    senderId = packet.senderId,
                    recipientId = packet.recipientId,
                    payload = fragmentPayload
                )
            )
        }

        return fragments
    }
}
