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

import Foundation

/// Per-peer synchronization state machine and reconciliation coordinator actor.
public actor SyncSession {

    public enum State: Sendable {
        case idle
        case handshaking
        case reconciling
        case streaming
        case synchronized
    }

    public let peerId: String
    public let linkId: String

    private let localNodeId: Int64
    private let localNodeName: String
    private let adapter: AirDbAdapter
    private let clock: HybridLogicalClock
    private let vectorClock: VectorClock
    private let transport: AirDbTransport
    public let clusterKey: Data?
    public let authorKeyPair: Ed25519KeyPair?
    public let enforceSignatures: Bool
    private let onSyncCompleted: @Sendable (String, Int) -> Void

    private var currentState: State = .idle
    private let peerVectorClock = VectorClock()
    private var isSyncing: Bool = false
    private var lastSentBatch: [ChangeRecord]?

    public init(
        peerId: String,
        linkId: String,
        localNodeId: Int64,
        localNodeName: String,
        adapter: AirDbAdapter,
        clock: HybridLogicalClock,
        vectorClock: VectorClock,
        transport: AirDbTransport,
        clusterKey: Data? = nil,
        authorKeyPair: Ed25519KeyPair? = nil,
        enforceSignatures: Bool = false,
        onSyncCompleted: @escaping @Sendable (String, Int) -> Void
    ) {
        self.peerId = peerId
        self.linkId = linkId
        self.localNodeId = localNodeId
        self.localNodeName = localNodeName
        self.adapter = adapter
        self.clock = clock
        self.vectorClock = vectorClock
        self.transport = transport
        self.clusterKey = clusterKey
        self.authorKeyPair = authorKeyPair
        self.enforceSignatures = enforceSignatures
        self.onSyncCompleted = onSyncCompleted
    }

    public func start() async {
        guard !isSyncing else { return }
        isSyncing = true
        currentState = .handshaking
        await sendHandshake()
    }

    public func notifyLocalMutation() async {
        if currentState == .synchronized || currentState == .idle {
            await streamDeltasToPeer()
        }
    }

    private func sendHandshake() async {
        let packet = AirDbPacket(
            type: .handshake,
            senderId: localNodeId,
            payload: Data(localNodeName.utf8)
        )
        _ = await transport.send(packet: packet, targetPeerId: peerId)
    }

    public func handlePacket(_ packet: AirDbPacket) async {
        switch packet.type {
        case .handshake:
            let ack = AirDbPacket(
                type: .handshakeAck,
                senderId: localNodeId,
                payload: Data(localNodeName.utf8)
            )
            _ = await transport.send(packet: ack, targetPeerId: peerId)
            await startReconciliation()

        case .handshakeAck:
            await startReconciliation()

        case .reconcileReq:
            let remoteClock = VectorClock.fromBinary(packet.payload)
            peerVectorClock.merge(remoteClock)

            let resp = AirDbPacket(
                type: .reconcileResp,
                senderId: localNodeId,
                payload: vectorClock.toBinary()
            )
            _ = await transport.send(packet: resp, targetPeerId: peerId)
            await streamDeltasToPeer()

        case .reconcileResp:
            let remoteClock = VectorClock.fromBinary(packet.payload)
            peerVectorClock.merge(remoteClock)
            await streamDeltasToPeer()

        case .deltaBatch:
            let changes = DeltaBatch.deserialize(packet.payload)
            if !changes.isEmpty {
                let validChanges: [ChangeRecord]
                if enforceSignatures {
                    validChanges = changes.filter { $0.verifySignature() }
                } else {
                    validChanges = changes
                }

                if !validChanges.isEmpty {
                    // Advance local clock based on highest incoming change HLC
                    let maxHlcRecord = validChanges.max(by: { HlcComparator.compare($0.hlcTimestamp, $1.hlcTimestamp) == .orderedAscending })
                    if let maxHlc = maxHlcRecord?.hlcTimestamp, let parsed = HlcTimestamp.parse(maxHlc) {
                        _ = clock.update(remote: parsed)
                    }

                    // Update local vector clock for each incoming change
                    for change in validChanges {
                        if let origin = HlcComparator.extractNodeId(from: change.hlcTimestamp) {
                            vectorClock.update(nodeId: origin, hlcTimestamp: change.hlcTimestamp)
                        }
                    }

                    do {
                        try await adapter.applyChanges(validChanges)
                        onSyncCompleted(peerId, validChanges.count)
                    } catch {
                        // Log or handle adapter error
                    }
                }
            }

            let ack = AirDbPacket(
                type: .deltaAck,
                senderId: localNodeId,
                payload: Data()
            )
            _ = await transport.send(packet: ack, targetPeerId: peerId)

        case .deltaAck:
            if let sent = lastSentBatch, !sent.isEmpty {
                for change in sent {
                    let origin = HlcComparator.extractNodeId(from: change.hlcTimestamp) ?? localNodeName
                    peerVectorClock.update(nodeId: origin, hlcTimestamp: change.hlcTimestamp)
                }
                lastSentBatch = nil
                await streamDeltasToPeer()
            } else {
                currentState = .synchronized
            }

        default:
            break
        }
    }

    private func startReconciliation() async {
        currentState = .reconciling
        let req = AirDbPacket(
            type: .reconcileReq,
            senderId: localNodeId,
            payload: vectorClock.toBinary()
        )
        _ = await transport.send(packet: req, targetPeerId: peerId)
    }

    private func streamDeltasToPeer() async {
        currentState = .streaming
        let missingChanges = (try? await adapter.getMissingChanges(peerClock: peerVectorClock.asMap(), limit: 500)) ?? []

        if !missingChanges.isEmpty {
            let signedChanges: [ChangeRecord]
            if let keyPair = authorKeyPair {
                signedChanges = missingChanges.map { change in
                    if !change.isSigned {
                        return (try? change.signed(with: keyPair)) ?? change
                    }
                    return change
                }
            } else {
                signedChanges = missingChanges
            }

            lastSentBatch = signedChanges
            let deltaBytes = DeltaBatch.serialize(signedChanges)
            let packet = AirDbPacket(
                type: .deltaBatch,
                senderId: localNodeId,
                payload: deltaBytes
            )
            _ = await transport.send(packet: packet, targetPeerId: peerId)
        } else {
            currentState = .synchronized
        }
    }
}
