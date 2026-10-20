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

import io.airdb.protocol.fragment.AssemblyBuffer
import io.airdb.protocol.fragment.FragmentHeader
import io.airdb.protocol.fragment.PacketFragmenter
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AssemblyBufferEdgeCaseTest {

    @Test
    fun testDuplicateFragmentIgnored() {
        val randomPayload = ByteArray(2000)
        java.util.Random(42).nextBytes(randomPayload)

        val original = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 1L,
            payload = randomPayload
        )

        val fragments = PacketFragmenter.fragment(original, maxChunkSize = 250)
        assertTrue(fragments.size > 2, "Random 2KB payload should split into > 2 fragments even if compressed")

        val assemblyBuffer = AssemblyBuffer()

        // Ingest fragment 0
        assertNull(assemblyBuffer.ingest(fragments[0]))

        // Ingest duplicate fragment 0
        assertNull(assemblyBuffer.ingest(fragments[0]), "Duplicate fragment should be safely ignored")

        // Ingest remaining fragments
        var assembled: AirDbPacket? = null
        for (i in 1 until fragments.size) {
            val res = assemblyBuffer.ingest(fragments[i])
            if (res != null) assembled = res
        }

        assertNotNull(assembled, "Packet should successfully assemble despite duplicate fragment")
        assertArrayEquals(original.payload, assembled!!.payload)
    }

    @Test
    fun testMalformedOutOfBoundsFragmentIndexRejected() {
        val assemblyBuffer = AssemblyBuffer()

        // Fabricate a fragment with index 10 when total is 2
        val malformedHeader = FragmentHeader(
            fragmentId = 9999L,
            index = 10,
            total = 2,
            originalTypeCode = PacketType.DELTA_BATCH.code
        )
        val malformedPayload = FragmentHeader.encode(malformedHeader, byteArrayOf(1, 2, 3))
        val malformedPacket = AirDbPacket(
            type = PacketType.FRAGMENT,
            senderId = 1L,
            payload = malformedPayload
        )

        assertNull(assemblyBuffer.ingest(malformedPacket), "Out-of-bounds fragment index must not cause IndexOutOfBoundsException")
    }

    @Test
    fun testTtlEvictionOfStaleAssemblies() {
        // Assembly buffer with 50ms TTL
        val assemblyBuffer = AssemblyBuffer(ttlMs = 50L)

        val randomPayload = ByteArray(2000)
        java.util.Random(42).nextBytes(randomPayload)
        val original = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 1L,
            payload = randomPayload
        )
        val fragments = PacketFragmenter.fragment(original, maxChunkSize = 250)

        // Deliver only first fragment
        assemblyBuffer.ingest(fragments[0])
        assertEquals(1, assemblyBuffer.activeAssemblyCount())

        // Sleep to exceed TTL
        Thread.sleep(70)

        // Next ingest triggers cleanup of expired assembly
        val otherPacket = AirDbPacket(
            type = PacketType.HANDSHAKE,
            senderId = 2L,
            payload = byteArrayOf(1)
        )
        val otherFrag = PacketFragmenter.fragment(otherPacket, maxChunkSize = 500)
        assemblyBuffer.ingest(otherFrag[0])

        // The old assembly (fragmentId of original) should have been evicted
        assertNull(assemblyBuffer.ingest(fragments[1]), "Fragments for expired assembly should not reassemble with stale state")
    }
}
