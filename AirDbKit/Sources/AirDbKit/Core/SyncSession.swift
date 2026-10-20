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

/// State machine managing the 1:1 synchronization session with a single peer.
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
    private let transport: AirDbTransport
    private let onSyncCompleted: @Sendable (String, Int) -> Void

    private var currentState: State = .idle
    private let peerVectorClock = VectorClock()
    private var isSyncing: Bool = false
    private var lastSentBatchMaxHlc: String?

    public init(
        peerId: String,
        linkId: String,
        localNodeId: Int64,
        localNodeName: String,
        adapter: AirDbAdapter,
        clock: HybridLogicalClock,
        transport: AirDbTransport,
        onSyncCompleted: @escaping @Sendable (String, Int) -> Void
    ) {
        self.peerId = peerId
        self.linkId = linkId
        self.localNodeId = localNodeId
        self.localNodeName = localNodeName
        self.adapter = adapter
        self.clock = clock
        self.transport = transport
        self.onSyncCompleted = onSyncCompleted
    }

    public func state() -> State {
        return currentState
    }

    public func start() async {
        guard !isSyncing else { return }
        isSyncing = true
        currentState = .handshaking
        await sendHandshake()
    }

    private func sendHandshake() async {
        guard let nameData = localNodeName.data(using: .utf8) else { return }
        let packet = AirDbPacket(
            type: .handshake,
            senderId: localNodeId,
            payload: nameData
        )
        _ = await transport.send(packet: packet, targetPeerId: peerId)
    }

    public func handlePacket(_ packet: AirDbPacket) async {
        switch packet.type {
        case .handshake:
            if let nameData = localNodeName.data(using: .utf8) {
                let ack = AirDbPacket(
                    type: .handshakeAck,
                    senderId: localNodeId,
                    payload: nameData
                )
                _ = await transport.send(packet: ack, targetPeerId: peerId)
            }
            await startReconciliation()

        case .handshakeAck:
            await startReconciliation()

        case .reconcileReq:
            let remoteClock = VectorClock.fromBinary(packet.payload)
            peerVectorClock.merge(remoteClock)

            let latestLocalHlc = (try? await adapter.getLatestHlc()) ?? clock.current().description
            let myClock = VectorClock(initialState: [localNodeName: latestLocalHlc])
            let resp = AirDbPacket(
                type: .reconcileResp,
                senderId: localNodeId,
                payload: myClock.toBinary()
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
                if let maxHlc = changes.map(\.hlcTimestamp).max(),
                   let parsed = HlcTimestamp.parse(maxHlc) {
                    _ = clock.update(remote: parsed)
                }

                do {
                    try await adapter.applyChanges(changes)
                    onSyncCompleted(peerId, changes.count)
                } catch {
                    // Log or handle adapter error
                }
            }

            let ack = AirDbPacket(
                type: .deltaAck,
                senderId: localNodeId,
                payload: Data()
            )
            _ = await transport.send(packet: ack, targetPeerId: peerId)

        case .deltaAck:
            if let acknowledgedHlc = lastSentBatchMaxHlc {
                peerVectorClock.update(nodeId: localNodeName, hlcTimestamp: acknowledgedHlc)
                lastSentBatchMaxHlc = nil
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
        let latestHlc = (try? await adapter.getLatestHlc()) ?? clock.current().description
        let localSummary = VectorClock(initialState: [localNodeName: latestHlc])
        let req = AirDbPacket(
            type: .reconcileReq,
            senderId: localNodeId,
            payload: localSummary.toBinary()
        )
        _ = await transport.send(packet: req, targetPeerId: peerId)
    }

    private func streamDeltasToPeer() async {
        currentState = .streaming
        let peerKnownHlc = peerVectorClock.get(localNodeName)
        let missingChanges = (try? await adapter.getChangesSince(sinceHlcTimestamp: peerKnownHlc, limit: 500)) ?? []

        if !missingChanges.isEmpty {
            lastSentBatchMaxHlc = missingChanges.last?.hlcTimestamp
            let deltaBytes = DeltaBatch.serialize(missingChanges)
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
