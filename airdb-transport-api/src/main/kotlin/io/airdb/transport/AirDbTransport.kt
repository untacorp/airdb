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

package io.airdb.transport

import io.airdb.protocol.packet.AirDbPacket

/**
 * Events emitted by the transport layer up to the AirDB sync engine.
 */
sealed class TransportEvent {

    /** A remote peer was discovered advertising in radio proximity. */
    data class PeerDiscovered(val peerId: String, val rssi: Int) : TransportEvent()

    /** A physical link was established with a remote peer. */
    data class LinkConnected(val peerId: String, val linkId: String) : TransportEvent()

    /** An existing physical link was terminated. */
    data class LinkDisconnected(val peerId: String, val linkId: String) : TransportEvent()

    /** An inbound packet was successfully received and decoded. */
    data class PacketReceived(
        val packet: AirDbPacket,
        val peerId: String,
        val linkId: String
    ) : TransportEvent()

    /** The transport buffer has space to accept more writes. */
    data class LinkWritable(val linkId: String) : TransportEvent()
}

/**
 * Callback listener invoked by transport implementations.
 */
interface TransportListener {
    fun onTransportEvent(event: TransportEvent)
}

/**
 * Universal P2P transport abstraction contract for AirDB.
 */
interface AirDbTransport {

    /** Start advertising and scanning. */
    fun start()

    /** Stop radio operations and close active links. */
    fun stop()

    /**
     * Enqueue a packet for transmission.
     *
     * @param packet The packet to send.
     * @param targetPeerId The destination peer ID, or null to broadcast to all connected peers.
     * @return True if accepted into the transmit buffer; false if backpressure rejected it.
     */
    suspend fun send(packet: AirDbPacket, targetPeerId: String? = null): Boolean

    /** Set event listener for inbound packets and link state updates. */
    fun setListener(listener: TransportListener)
}
