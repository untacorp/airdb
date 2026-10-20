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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header preceding every fragment chunk in an AirDB fragmentation payload.
 *
 * Layout (13 bytes):
 * ```
 * +--------------------+------------------+------------------+--------------------+
 * | Fragment ID (8B)   | Index (2B BE)    | Total (2B BE)    | Original Type (1B) |
 * +--------------------+------------------+------------------+--------------------+
 * ```
 */
data class FragmentHeader(
    val fragmentId: Long,
    val index: Int,
    val total: Int,
    val originalTypeCode: Byte
) {
    companion object {
        const val HEADER_SIZE = 13

        fun decode(bytes: ByteArray): Pair<FragmentHeader, ByteArray>? {
            if (bytes.size < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val fragmentId = buffer.getLong()
            val index = buffer.getShort().toInt() and 0xFFFF
            val total = buffer.getShort().toInt() and 0xFFFF
            val originalTypeCode = buffer.get()

            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)

            return Pair(FragmentHeader(fragmentId, index, total, originalTypeCode), chunk)
        }

        fun encode(header: FragmentHeader, chunk: ByteArray): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_SIZE + chunk.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putLong(header.fragmentId)
            buffer.putShort(header.index.toShort())
            buffer.putShort(header.total.toShort())
            buffer.put(header.originalTypeCode)
            buffer.put(chunk)
            return buffer.array()
        }
    }
}
