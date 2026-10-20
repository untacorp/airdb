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

package io.airdb.core.clock

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the highest known HLC timestamp per originating node ID.
 * Used during the reconciliation handshake to compute missing delta ranges.
 */
class VectorClock(
    initialState: Map<String, String> = emptyMap()
) {

    private val clocks = ConcurrentHashMap<String, String>(initialState)

    fun get(nodeId: String): String? = clocks[nodeId]

    fun update(nodeId: String, hlcTimestamp: String) {
        clocks.compute(nodeId) { _, current ->
            if (current == null || hlcTimestamp > current) hlcTimestamp else current
        }
    }

    fun merge(other: VectorClock) {
        for ((nodeId, ts) in other.asMap()) {
            update(nodeId, ts)
        }
    }

    fun asMap(): Map<String, String> = clocks.toMap()

    /**
     * Serializes this vector clock into binary form for packet transmission.
     */
    fun toBinary(): ByteArray {
        val entries = clocks.entries.toList()
        var estimatedSize = 2 // count (Short)
        for ((k, v) in entries) {
            estimatedSize += 2 + k.toByteArray().size + 2 + v.toByteArray().size
        }

        val buffer = ByteBuffer.allocate(estimatedSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(entries.size.toShort())
        for ((k, v) in entries) {
            val kb = k.toByteArray(Charsets.UTF_8)
            val vb = v.toByteArray(Charsets.UTF_8)
            buffer.putShort(kb.size.toShort())
            buffer.put(kb)
            buffer.putShort(vb.size.toShort())
            buffer.put(vb)
        }
        return buffer.array()
    }

    companion object {
        fun fromBinary(bytes: ByteArray): VectorClock {
            if (bytes.size < 2) return VectorClock()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val count = buffer.getShort().toInt() and 0xFFFF
            val result = mutableMapOf<String, String>()

            for (i in 0 until count) {
                if (buffer.remaining() < 2) break
                val kLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < kLen) break
                val kb = ByteArray(kLen)
                buffer.get(kb)

                if (buffer.remaining() < 2) break
                val vLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < vLen) break
                val vb = ByteArray(vLen)
                buffer.get(vb)

                result[String(kb, Charsets.UTF_8)] = String(vb, Charsets.UTF_8)
            }
            return VectorClock(result)
        }
    }
}
