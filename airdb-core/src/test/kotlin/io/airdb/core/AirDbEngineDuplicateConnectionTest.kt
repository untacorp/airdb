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

import io.airdb.adapter.MemoryDbAdapter
import io.airdb.core.engine.AirDbEngine
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class AirDbEngineDuplicateConnectionTest {

    private class TestTransport : AirDbTransport {
        var currentListener: TransportListener? = null
        val started = AtomicBoolean(false)
        val sentPackets = mutableListOf<AirDbPacket>()

        override fun setClusterKey(key: ByteArray?) {}

        override fun setListener(listener: TransportListener) {
            this.currentListener = listener
        }

        override fun start() {
            started.set(true)
        }

        override fun stop() {
            started.set(false)
        }

        override suspend fun send(packet: AirDbPacket, targetPeerId: String?): Boolean {
            sentPackets.add(packet)
            return true
        }
    }

    @Test
    fun testDuplicateLinkConnectionDeduplication() = runBlocking {
        val adapter = MemoryDbAdapter()
        val transport = TestTransport()
        val engine = AirDbEngine("local-node", adapter, transport)
        engine.start()

        val listener = transport.currentListener
        assertNotNull(listener)

        // 1. Establish client link to peer-1
        listener!!.onTransportEvent(TransportEvent.LinkConnected("peer-1", "client-peer-1"))
        delay(50)
        assertEquals("client-peer-1", engine.getActiveLinkId("peer-1"))
        assertNotNull(engine.getSession("peer-1"))
        val initialPackets = transport.sentPackets.size
        assertTrue(initialPackets >= 1, "Handshake should be dispatched on initial link connection")

        // 2. Incoming duplicate server link from same peer
        listener.onTransportEvent(TransportEvent.LinkConnected("peer-1", "server-peer-1"))
        delay(50)

        // Duplicate connection should NOT override active client link
        assertEquals("client-peer-1", engine.getActiveLinkId("peer-1"))
        assertEquals(initialPackets, transport.sentPackets.size, "Duplicate link should be rejected without dispatching duplicate handshake")

        // 3. Disconnect redundant server link
        listener.onTransportEvent(TransportEvent.LinkDisconnected("peer-1", "server-peer-1"))
        delay(50)

        // Active client session must NOT be removed when redundant link disconnects
        assertEquals("client-peer-1", engine.getActiveLinkId("peer-1"))
        assertNotNull(engine.getSession("peer-1"))

        // 4. Disconnect authoritative client link
        listener.onTransportEvent(TransportEvent.LinkDisconnected("peer-1", "client-peer-1"))
        delay(50)

        // After authoritative disconnect, session is cleaned up
        assertNull(engine.getActiveLinkId("peer-1"))
        assertNull(engine.getSession("peer-1"))

        engine.stop()
    }

    @Test
    fun testServerToClientLinkUpgrade() = runBlocking {
        val adapter = MemoryDbAdapter()
        val transport = TestTransport()
        val engine = AirDbEngine("local-node", adapter, transport)
        engine.start()

        val listener = transport.currentListener
        assertNotNull(listener)

        // 1. Establish server link first
        listener!!.onTransportEvent(TransportEvent.LinkConnected("peer-2", "server-peer-2"))
        delay(50)
        assertEquals("server-peer-2", engine.getActiveLinkId("peer-2"))
        val packetsAfterServer = transport.sentPackets.size
        assertTrue(packetsAfterServer >= 1)

        // 2. Outbound client link connects (upgrades server link)
        listener.onTransportEvent(TransportEvent.LinkConnected("peer-2", "client-peer-2"))
        delay(50)
        assertEquals("client-peer-2", engine.getActiveLinkId("peer-2"))
        val packetsAfterClient = transport.sentPackets.size
        assertTrue(packetsAfterClient > packetsAfterServer, "Client link should upgrade session and dispatch handshake")

        // 3. Late disconnect of old server link should not destroy upgraded client link
        listener.onTransportEvent(TransportEvent.LinkDisconnected("peer-2", "server-peer-2"))
        delay(50)
        assertEquals("client-peer-2", engine.getActiveLinkId("peer-2"))
        assertNotNull(engine.getSession("peer-2"))

        // 4. Client link disconnects
        listener.onTransportEvent(TransportEvent.LinkDisconnected("peer-2", "client-peer-2"))
        delay(50)
        assertNull(engine.getActiveLinkId("peer-2"))
        assertNull(engine.getSession("peer-2"))

        engine.stop()
    }
}
