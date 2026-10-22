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

package io.airdb.transport.sim

import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class SimulatedTransportEncryptionTest {

    private class RecordingListener : TransportListener {
        val events = CopyOnWriteArrayList<TransportEvent>()
        override fun onTransportEvent(event: TransportEvent) {
            events.add(event)
        }
    }

    @Test
    fun testEncryptedTransportRoundtrip() = runBlocking {
        val network = SimulatedNetwork()
        val clusterKey = ByteArray(32) { 0x42 }

        val transportA = SimulatedTransport("node-a", network)
        transportA.setClusterKey(clusterKey)

        val transportB = SimulatedTransport("node-b", network)
        transportB.setClusterKey(clusterKey)

        val listenerB = RecordingListener()
        transportB.setListener(listenerB)

        transportA.start()
        transportB.start()

        val payload = "Confidential triage data: red tag emergency".toByteArray(Charsets.UTF_8)
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 100L,
            payload = payload
        )

        transportA.send(packet, targetPeerId = "node-b")

        Thread.sleep(100)

        val receivedEvents = listenerB.events.filterIsInstance<TransportEvent.PacketReceived>()
        assertEquals(1, receivedEvents.size, "Transport B should have received exactly one packet")
        val delivered = receivedEvents.first().packet
        assertTrue(delivered.isEncrypted, "Delivered packet must be marked encrypted")
        assertArrayEquals(payload, delivered.payload)

        transportA.stop()
        transportB.stop()
    }

    @Test
    fun testEncryptedTransportFragmentationRoundtrip() = runBlocking {
        val network = SimulatedNetwork()
        val clusterKey = ByteArray(32) { 0x77 }

        val transportA = SimulatedTransport("node-a", network, maxChunkSize = 200)
        transportA.setClusterKey(clusterKey)

        val transportB = SimulatedTransport("node-b", network, maxChunkSize = 200)
        transportB.setClusterKey(clusterKey)

        val listenerB = RecordingListener()
        transportB.setListener(listenerB)

        transportA.start()
        transportB.start()

        val largePayload = ByteArray(1500) { (it % 256).toByte() }
        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 200L,
            payload = largePayload
        )

        transportA.send(packet, targetPeerId = "node-b")

        Thread.sleep(200)

        val receivedEvents = listenerB.events.filterIsInstance<TransportEvent.PacketReceived>()
        assertEquals(1, receivedEvents.size, "Transport B should have reassembled the fragmented packet")
        val delivered = receivedEvents.first().packet
        assertTrue(delivered.isEncrypted, "Delivered packet must be marked encrypted")
        assertArrayEquals(largePayload, delivered.payload)

        transportA.stop()
        transportB.stop()
    }

    @Test
    fun testPlaintextPacketRejectedByEncryptedTransport() = runBlocking {
        val network = SimulatedNetwork()
        val clusterKey = ByteArray(32) { 0x55 }

        // Node A has clusterKey (encrypted cluster)
        val transportA = SimulatedTransport("node-a", network)
        transportA.setClusterKey(clusterKey)
        val listenerA = RecordingListener()
        transportA.setListener(listenerA)

        // Node C is an unauthorized node transmitting without clusterKey
        val transportC = SimulatedTransport("node-c", network)
        transportC.setClusterKey(null)

        transportA.start()
        transportC.start()

        val plaintextPacket = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 999L,
            payload = "Malicious unencrypted injection".toByteArray(Charsets.UTF_8)
        )

        transportC.send(plaintextPacket, targetPeerId = "node-a")

        Thread.sleep(100)

        val receivedPackets = listenerA.events.filterIsInstance<TransportEvent.PacketReceived>()
        assertTrue(receivedPackets.isEmpty(), "Node A must reject plaintext packet when clusterKey is enforced")

        transportA.stop()
        transportC.stop()
    }

    @Test
    fun testEncryptedPacketDroppedByUnkeyedTransport() = runBlocking {
        val network = SimulatedNetwork()
        val clusterKey = ByteArray(32) { 0x88.toByte() }

        val transportA = SimulatedTransport("node-a", network)
        transportA.setClusterKey(clusterKey)

        // Node C has no clusterKey
        val transportC = SimulatedTransport("node-c", network)
        val listenerC = RecordingListener()
        transportC.setListener(listenerC)

        transportA.start()
        transportC.start()

        val packet = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 100L,
            payload = "Secret data".toByteArray(Charsets.UTF_8)
        )

        transportA.send(packet, targetPeerId = "node-c")

        Thread.sleep(100)

        val receivedPackets = listenerC.events.filterIsInstance<TransportEvent.PacketReceived>()
        assertTrue(receivedPackets.isEmpty(), "Unkeyed Node C must not be able to decrypt or receive encrypted packet")

        transportA.stop()
        transportC.stop()
    }
}
