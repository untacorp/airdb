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

import io.airdb.protocol.codec.BinaryCodec
import io.airdb.protocol.fragment.AssemblyBuffer
import io.airdb.protocol.fragment.PacketFragmenter
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory virtual radio mesh environment connecting multiple [SimulatedTransport] nodes.
 */
class SimulatedNetwork(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val nodes = ConcurrentHashMap<String, SimulatedTransport>()

    fun register(nodeId: String, transport: SimulatedTransport) {
        nodes[nodeId] = transport

        // Inform other nodes about this new node (discovery & link-up)
        for ((existingId, existingTransport) in nodes) {
            if (existingId != nodeId) {
                scope.launch {
                    existingTransport.notifyPeerConnected(nodeId)
                    transport.notifyPeerConnected(existingId)
                }
            }
        }
    }

    fun unregister(nodeId: String) {
        nodes.remove(nodeId)
        for ((_, other) in nodes) {
            scope.launch {
                other.notifyPeerDisconnected(nodeId)
            }
        }
    }

    fun dispatch(senderNodeId: String, packet: AirDbPacket, targetPeerId: String?) {
        scope.launch {
            if (targetPeerId != null) {
                nodes[targetPeerId]?.deliver(senderNodeId, packet)
            } else {
                for ((peerId, transport) in nodes) {
                    if (peerId != senderNodeId) {
                        transport.deliver(senderNodeId, packet)
                    }
                }
            }
        }
    }
}

/**
 * Sans-I/O simulated P2P transport implementing [AirDbTransport].
 * Accurately models MTU-based fragmentation and reassembly.
 */
class SimulatedTransport(
    val nodeId: String,
    private val network: SimulatedNetwork,
    private val maxChunkSize: Int = 460,
    private var clusterKey: ByteArray? = null
) : AirDbTransport {

    private var listener: TransportListener? = null
    private val assemblyBuffer = AssemblyBuffer()
    private var isRunning = false

    override fun setClusterKey(key: ByteArray?) {
        this.clusterKey = key
    }

    override fun start() {
        isRunning = true
        network.register(nodeId, this)
    }

    override fun stop() {
        isRunning = false
        network.unregister(nodeId)
    }

    override suspend fun send(packet: AirDbPacket, targetPeerId: String?): Boolean {
        if (!isRunning) return false

        // Emulate MTU fragmentation
        val fragments = PacketFragmenter.fragment(packet, clusterKey = clusterKey, maxChunkSize = maxChunkSize)
        for (fragment in fragments) {
            val wirePacket = if (clusterKey != null && fragment.type != PacketType.FRAGMENT) {
                val encoded = BinaryCodec.encode(fragment, clusterKey = clusterKey)
                val headerSize = BinaryCodec.MIN_HEADER_SIZE + (if (fragment.recipientId != null) BinaryCodec.RECIPIENT_ID_SIZE else 0)
                val ciphertext = encoded.copyOfRange(headerSize, encoded.size)
                AirDbPacket(
                    version = fragment.version,
                    type = fragment.type,
                    flags = (fragment.flags.toInt() or io.airdb.protocol.packet.PacketFlags.FLAG_ENCRYPTED.toInt()).toByte(),
                    timestamp = fragment.timestamp,
                    senderId = fragment.senderId,
                    recipientId = fragment.recipientId,
                    payload = ciphertext
                )
            } else {
                fragment
            }
            network.dispatch(nodeId, wirePacket, targetPeerId)
        }
        return true
    }

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    internal fun deliver(senderNodeId: String, rawPacket: AirDbPacket) {
        if (!isRunning) return

        val packet = if (rawPacket.type == PacketType.FRAGMENT) {
            assemblyBuffer.ingest(rawPacket, clusterKey = clusterKey) ?: return
        } else if (rawPacket.isEncrypted) {
            if (clusterKey == null) return
            val wireBytes = BinaryCodec.encode(rawPacket, clusterKey = null)
            BinaryCodec.decode(wireBytes, clusterKey = clusterKey) ?: return
        } else {
            rawPacket
        }

        if (clusterKey != null && !packet.isEncrypted) {
            return
        }

        listener?.onTransportEvent(
            TransportEvent.PacketReceived(
                packet = packet,
                peerId = senderNodeId,
                linkId = "sim-link-$senderNodeId"
            )
        )
    }

    internal fun notifyPeerConnected(peerId: String) {
        if (!isRunning) return
        listener?.onTransportEvent(TransportEvent.PeerDiscovered(peerId, -45))
        listener?.onTransportEvent(TransportEvent.LinkConnected(peerId, "sim-link-$peerId"))
    }

    internal fun notifyPeerDisconnected(peerId: String) {
        if (!isRunning) return
        listener?.onTransportEvent(TransportEvent.LinkDisconnected(peerId, "sim-link-$peerId"))
    }
}
