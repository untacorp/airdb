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
import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.HlcComparator
import io.airdb.core.clock.HybridLogicalClock
import io.airdb.core.clock.HlcTimestamp
import io.airdb.core.clock.VectorClock
import io.airdb.core.model.DeltaBatch
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.AirDbTransport
import io.airdb.core.model.sign
import io.airdb.core.model.verifySignature
import io.airdb.crypto.Ed25519KeyPair
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
    private val vectorClock: VectorClock,
    private val transport: AirDbTransport,
    private val scope: CoroutineScope,
    val clusterKey: ByteArray? = null,
    val authorKeyPair: Ed25519KeyPair? = null,
    val enforceSignatures: Boolean = false,
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
    @Volatile private var lastSentBatch: List<ChangeRecord>? = null

    fun start() {
        if (!isSyncing.compareAndSet(false, true)) return
        currentState = State.HANDSHAKING
        sendHandshake()
    }

    /**
     * Triggered when a new local mutation is committed while peers are connected.
     */
    fun notifyLocalMutation() {
        scope.launch {
            if (currentState == State.SYNCHRONIZED || currentState == State.IDLE) {
                streamDeltasToPeer()
            }
        }
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
                    val resp = AirDbPacket(
                        type = PacketType.RECONCILE_RESP,
                        senderId = localNodeId,
                        payload = vectorClock.toBinary()
                    )
                    transport.send(resp, targetPeerId = peerId)
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

            PacketType.DELTA_BATCH -> handleDeltaBatch(packet)

            PacketType.DELTA_ACK -> {
                val sent = lastSentBatch
                if (sent.isNullOrEmpty()) {
                    currentState = State.SYNCHRONIZED
                } else {
                    for (change in sent) {
                        val origin = HlcComparator.extractNodeId(change.hlcTimestamp) ?: localNodeName
                        peerVectorClock.update(origin, change.hlcTimestamp)
                    }
                    lastSentBatch = null
                    scope.launch { streamDeltasToPeer() }
                }
            }

            else -> Unit
        }
    }

    private fun handleDeltaBatch(packet: AirDbPacket) {
        scope.launch {
            val raw = DeltaBatch.deserialize(packet.payload)
            val changes = if (enforceSignatures) raw.filter { it.verifySignature() } else raw
            if (changes.isNotEmpty()) {
                val maxRecord = changes.maxWithOrNull(Comparator.comparing({ it.hlcTimestamp }, HlcComparator))
                maxRecord?.let { HlcTimestamp.parseOrNull(it.hlcTimestamp) }?.let { clock.update(it) }

                for (change in changes) {
                    val origin = HlcComparator.extractNodeId(change.hlcTimestamp) ?: ""
                    if (origin.isNotEmpty()) vectorClock.update(origin, change.hlcTimestamp)
                }

                adapter.applyChanges(changes)
                onSyncCompleted(peerId, changes.size)
            }

            val ack = AirDbPacket(
                type = PacketType.DELTA_ACK,
                senderId = localNodeId,
                payload = byteArrayOf()
            )
            transport.send(ack, targetPeerId = peerId)
        }
    }

    private fun startReconciliation() {
        currentState = State.RECONCILING
        scope.launch {
            val req = AirDbPacket(
                type = PacketType.RECONCILE_REQ,
                senderId = localNodeId,
                payload = vectorClock.toBinary()
            )
            transport.send(req, targetPeerId = peerId)
        }
    }

    private suspend fun streamDeltasToPeer() {
        currentState = State.STREAMING
        val missingChanges = adapter.getMissingChanges(peerVectorClock.asMap(), limit = 500)

        if (missingChanges.isNotEmpty()) {
            val signedChanges = if (authorKeyPair != null) {
                missingChanges.map { change ->
                    if (!change.isSigned) change.sign(authorKeyPair) else change
                }
            } else {
                missingChanges
            }

            lastSentBatch = signedChanges
            val deltaBytes = DeltaBatch.serialize(signedChanges)
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
