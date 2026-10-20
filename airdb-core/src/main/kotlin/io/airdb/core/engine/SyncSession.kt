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

package io.airdb.core.engine

import io.airdb.adapter.AirDbAdapter
import io.airdb.core.clock.HybridLogicalClock
import io.airdb.core.clock.VectorClock
import io.airdb.core.model.DeltaBatch
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.AirDbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State machine managing the 1:1 synchronization session with a single peer.
 */
class SyncSession(
    val peerId: String,
    val linkId: String,
    private val localNodeId: Long,
    private val localNodeName: String,
    private val adapter: AirDbAdapter,
    private val clock: HybridLogicalClock,
    private val transport: AirDbTransport,
    private val scope: CoroutineScope,
    private val onSyncCompleted: (peerId: String, changesApplied: Int) -> Unit
) {

    enum class State {
        IDLE,
        HANDSHAKING,
        RECONCILING,
        STREAMING,
        SYNCHRONIZED
    }

    private var currentState = State.IDLE
    private val peerVectorClock = VectorClock()
    private val isSyncing = AtomicBoolean(false)
    @Volatile private var lastSentBatchMaxHlc: String? = null

    fun start() {
        if (!isSyncing.compareAndSet(false, true)) return
        currentState = State.HANDSHAKING
        sendHandshake()
    }

    private fun sendHandshake() {
        scope.launch {
            val packet = AirDbPacket(
                type = PacketType.HANDSHAKE,
                senderId = localNodeId,
                payload = localNodeName.toByteArray(Charsets.UTF_8)
            )
            transport.send(packet, targetPeerId = peerId)
        }
    }

    fun handlePacket(packet: AirDbPacket) {
        when (packet.type) {
            PacketType.HANDSHAKE -> {
                // Respond with Handshake ACK
                scope.launch {
                    val ack = AirDbPacket(
                        type = PacketType.HANDSHAKE_ACK,
                        senderId = localNodeId,
                        payload = localNodeName.toByteArray(Charsets.UTF_8)
                    )
                    transport.send(ack, targetPeerId = peerId)
                    startReconciliation()
                }
            }

            PacketType.HANDSHAKE_ACK -> {
                startReconciliation()
            }

            PacketType.RECONCILE_REQ -> {
                val remoteClock = VectorClock.fromBinary(packet.payload)
                peerVectorClock.merge(remoteClock)

                scope.launch {
                    // Send response with our latest known state
                    val latestLocalHlc = adapter.getLatestHlc() ?: clock.current().toString()
                    val myClock = VectorClock(mapOf(localNodeName to latestLocalHlc))
                    val resp = AirDbPacket(
                        type = PacketType.RECONCILE_RESP,
                        senderId = localNodeId,
                        payload = myClock.toBinary()
                    )
                    transport.send(resp, targetPeerId = peerId)

                    // Also immediately push deltas that peer needs
                    streamDeltasToPeer()
                }
            }

            PacketType.RECONCILE_RESP -> {
                val remoteClock = VectorClock.fromBinary(packet.payload)
                peerVectorClock.merge(remoteClock)
                scope.launch {
                    streamDeltasToPeer()
                }
            }

            PacketType.DELTA_BATCH -> {
                scope.launch {
                    val changes = DeltaBatch.deserialize(packet.payload)
                    if (changes.isNotEmpty()) {
                        // Advance local clock based on highest incoming change HLC
                        val maxHlc = changes.maxOf { it.hlcTimestamp }
                        clock.update(io.airdb.core.clock.HlcTimestamp.parse(maxHlc))

                        // Apply to database
                        adapter.applyChanges(changes)
                        onSyncCompleted(peerId, changes.size)
                    }

                    // Acknowledge receipt
                    val ack = AirDbPacket(
                        type = PacketType.DELTA_ACK,
                        senderId = localNodeId,
                        payload = byteArrayOf()
                    )
                    transport.send(ack, targetPeerId = peerId)
                }
            }

            PacketType.DELTA_ACK -> {
                val acknowledgedHlc = lastSentBatchMaxHlc
                if (acknowledgedHlc != null) {
                    peerVectorClock.update(localNodeName, acknowledgedHlc)
                    lastSentBatchMaxHlc = null
                    scope.launch {
                        streamDeltasToPeer()
                    }
                } else {
                    currentState = State.SYNCHRONIZED
                }
            }

            else -> Unit
        }
    }

    private fun startReconciliation() {
        currentState = State.RECONCILING
        scope.launch {
            val latestHlc = adapter.getLatestHlc() ?: clock.current().toString()
            val localSummary = VectorClock(mapOf(localNodeName to latestHlc))
            val req = AirDbPacket(
                type = PacketType.RECONCILE_REQ,
                senderId = localNodeId,
                payload = localSummary.toBinary()
            )
            transport.send(req, targetPeerId = peerId)
        }
    }

    private suspend fun streamDeltasToPeer() {
        currentState = State.STREAMING
        val peerKnownHlc = peerVectorClock.get(localNodeName)
        val missingChanges = adapter.getChangesSince(peerKnownHlc, limit = 500)

        if (missingChanges.isNotEmpty()) {
            lastSentBatchMaxHlc = missingChanges.last().hlcTimestamp
            val deltaBytes = DeltaBatch.serialize(missingChanges)
            val packet = AirDbPacket(
                type = PacketType.DELTA_BATCH,
                senderId = localNodeId,
                payload = deltaBytes
            )
            transport.send(packet, targetPeerId = peerId)
        } else {
            currentState = State.SYNCHRONIZED
        }
    }
}
