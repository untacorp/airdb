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
import io.airdb.adapter.MutationType
import io.airdb.core.clock.HybridLogicalClock
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import io.airdb.core.model.sign
import io.airdb.crypto.Ed25519KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Main coordinator and public API facade for AirDB.
 *
 * @param nodeName Human-readable or stable node identifier (e.g. "posko-merapi-1" or device fingerprint).
 * @param adapter Application database seam adapter.
 * @param transport Underlying peer-to-peer radio transport.
 */
class AirDbEngine(
    val nodeName: String,
    val adapter: AirDbAdapter,
    val transport: AirDbTransport,
    val clusterKey: ByteArray? = null,
    val authorKeyPair: Ed25519KeyPair? = null,
    val enforceSignatures: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : TransportListener {

    val localNodeId: Long = nodeName.hashCode().toLong()
    val clock = HybridLogicalClock(nodeName)
    val vectorClock = io.airdb.core.clock.VectorClock()

    private val sessions = ConcurrentHashMap<String, SyncSession>()
    private val syncListeners = CopyOnWriteArrayList<(peerId: String, changeCount: Int) -> Unit>()
    private var isStarted = false

    init {
        if (clusterKey != null) {
            transport.setClusterKey(clusterKey)
        }
        transport.setListener(this)
    }

    /**
     * Starts radio advertising, scanning, and listening for peers.
     */
    fun start() {
        if (isStarted) return
        isStarted = true
        transport.start()
    }

    /**
     * Stops all active sync sessions and shuts down radio operations.
     */
    fun stop() {
        if (!isStarted) return
        isStarted = false
        transport.stop()
        sessions.clear()
        scope.cancel()
    }

    /**
     * Records a local mutation (INSERT/UPDATE/DELETE) in the database with an authoritative HLC timestamp.
     *
     * @param collection Table or collection name.
     * @param id Unique record identifier.
     * @param type Mutation type (INSERT, UPDATE, DELETE).
     * @param payload Serialized record body.
     * @return The created [ChangeRecord] stamped with its HLC.
     */
    suspend fun saveRecord(
        collection: String,
        id: String,
        type: MutationType,
        payload: ByteArray
    ): ChangeRecord {
        val hlc = clock.now().toString()
        vectorClock.update(nodeName, hlc)
        var record = ChangeRecord(
            id = id,
            collection = collection,
            type = type,
            payload = payload,
            hlcTimestamp = hlc
        )
        if (authorKeyPair != null) {
            record = record.sign(authorKeyPair)
        }
        adapter.applyChanges(listOf(record))

        // Immediately trigger real-time delta streaming on active sessions
        sessions.values.forEach { it.notifyLocalMutation() }
        return record
    }

    /**
     * Registers a callback listener invoked whenever changes are applied from a peer.
     */
    fun addSyncListener(listener: (peerId: String, changeCount: Int) -> Unit) {
        syncListeners.add(listener)
    }

    override fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.LinkConnected -> {
                val session = SyncSession(
                    peerId = event.peerId,
                    linkId = event.linkId,
                    localNodeId = localNodeId,
                    localNodeName = nodeName,
                    adapter = adapter,
                    clock = clock,
                    vectorClock = vectorClock,
                    transport = transport,
                    scope = scope,
                    clusterKey = clusterKey,
                    authorKeyPair = authorKeyPair,
                    enforceSignatures = enforceSignatures
                ) { peerId, changesApplied ->
                    syncListeners.forEach { it(peerId, changesApplied) }
                }
                sessions[event.peerId] = session
                session.start()
            }

            is TransportEvent.LinkDisconnected -> {
                sessions.remove(event.peerId)
            }

            is TransportEvent.PacketReceived -> {
                val session = sessions.computeIfAbsent(event.peerId) {
                    val s = SyncSession(
                        peerId = event.peerId,
                        linkId = event.linkId,
                        localNodeId = localNodeId,
                        localNodeName = nodeName,
                        adapter = adapter,
                        clock = clock,
                        vectorClock = vectorClock,
                        transport = transport,
                        scope = scope,
                        clusterKey = clusterKey,
                        authorKeyPair = authorKeyPair,
                        enforceSignatures = enforceSignatures
                    ) { peerId, changesApplied ->
                        syncListeners.forEach { it(peerId, changesApplied) }
                    }
                    s
                }
                session.handlePacket(event.packet)
            }

            else -> Unit
        }
    }
}
