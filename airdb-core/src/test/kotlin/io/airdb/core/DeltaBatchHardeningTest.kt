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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeltaBatchHardeningTest {

    @Test
    fun testTruncatedPayloadsDoNotThrowUnderflow() {
        val validBatch = listOf(
            ChangeRecord(
                id = "item-1",
                collection = "inventory",
                type = MutationType.INSERT,
                payload = byteArrayOf(10, 20, 30),
                hlcTimestamp = "1700000000000-0-nodeA"
            )
        )
        val serialized = DeltaBatch.serialize(validBatch)

        // Truncate at every single byte offset from 0 to serialized.size - 1
        for (cutoff in 0 until serialized.size) {
            val truncated = serialized.copyOfRange(0, cutoff)
            assertDoesNotThrow {
                val result = DeltaBatch.deserialize(truncated)
                assertNotNull(result)
            }
        }
    }

    @Test
    fun testNegativePayloadLengthHandledGracefully() {
        // Construct malformed delta buffer with negative payload length
        val buffer = ByteBuffer.allocate(50).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(1.toShort()) // count = 1

        val idBytes = "id-1".toByteArray()
        buffer.putShort(idBytes.size.toShort())
        buffer.put(idBytes)

        val collBytes = "coll-1".toByteArray()
        buffer.putShort(collBytes.size.toShort())
        buffer.put(collBytes)

        buffer.put(0.toByte()) // type = insert

        val hlcBytes = "1000-0-a".toByteArray()
        buffer.putShort(hlcBytes.size.toShort())
        buffer.put(hlcBytes)

        buffer.putInt(-500) // NEGATIVE payload length

        assertDoesNotThrow {
            val result = DeltaBatch.deserialize(buffer.array())
            assertTrue(result.isEmpty(), "Malformed record with negative payload length must be rejected")
        }
    }
}
