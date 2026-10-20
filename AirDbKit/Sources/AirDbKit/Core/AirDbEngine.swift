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

/// Main coordinator and public API facade for AirDB on Apple platforms.
public final class AirDbEngine: TransportListener, @unchecked Sendable {
    public let nodeName: String
    public let localNodeId: Int64
    public let clock: HybridLogicalClock
    public let adapter: AirDbAdapter
    public let transport: AirDbTransport

    private let lock = NSLock()
    private var sessions: [String: SyncSession] = [:]
    private var syncListeners: [@Sendable (String, Int) -> Void] = []
    private var isStarted: Bool = false

    public init(
        nodeName: String,
        adapter: AirDbAdapter,
        transport: AirDbTransport
    ) {
        self.nodeName = nodeName
        self.localNodeId = AirDbEngine.deterministicNodeId(from: nodeName)
        self.clock = HybridLogicalClock(nodeId: nodeName)
        self.adapter = adapter
        self.transport = transport

        self.transport.setListener(self)
    }

    /// Computes a deterministic 64-bit node ID compatible with standard cross-platform hash representations.
    public static func deterministicNodeId(from name: String) -> Int64 {
        var hash: Int32 = 0
        for byte in name.utf8 {
            hash = 31 &* hash &+ Int32(byte)
        }
        return Int64(hash)
    }

    /// Starts radio advertising, scanning, and listening for peers.
    public func start() {
        lock.lock()
        guard !isStarted else {
            lock.unlock()
            return
        }
        isStarted = true
        lock.unlock()

        transport.start()
    }

    /// Stops all active sync sessions and shuts down radio operations.
    public func stop() {
        lock.lock()
        guard isStarted else {
            lock.unlock()
            return
        }
        isStarted = false
        sessions.removeAll()
        lock.unlock()

        transport.stop()
    }

    /// Records a local mutation (INSERT/UPDATE/DELETE) in the database with an authoritative HLC timestamp.
    ///
    /// - Parameters:
    ///   - collection: Table or collection name.
    ///   - id: Unique record identifier.
    ///   - type: Mutation type (insert, update, delete).
    ///   - payload: Serialized record body.
    /// - Returns: The created ``ChangeRecord`` stamped with its HLC.
    @discardableResult
    public func saveRecord(
        collection: String,
        id: String,
        type: MutationType,
        payload: Data
    ) async throws -> ChangeRecord {
        let hlc = clock.now().description
        let record = ChangeRecord(
            id: id,
            collection: collection,
            type: type,
            payload: payload,
            hlcTimestamp: hlc
        )
        try await adapter.applyChanges([record])
        return record
    }

    /// Registers a callback listener invoked whenever changes are applied from a peer.
    public func addSyncListener(_ listener: @escaping @Sendable (String, Int) -> Void) {
        lock.lock()
        syncListeners.append(listener)
        lock.unlock()
    }

    // MARK: - TransportListener

    public func onTransportEvent(_ event: TransportEvent) {
        switch event {
        case .linkConnected(let peerId, let linkId):
            let session = SyncSession(
                peerId: peerId,
                linkId: linkId,
                localNodeId: localNodeId,
                localNodeName: nodeName,
                adapter: adapter,
                clock: clock,
                transport: transport
            ) { [weak self] pId, changeCount in
                guard let self = self else { return }
                self.lock.lock()
                let listeners = self.syncListeners
                self.lock.unlock()
                for listener in listeners {
                    listener(pId, changeCount)
                }
            }

            lock.lock()
            sessions[peerId] = session
            lock.unlock()

            Task {
                await session.start()
            }

        case .linkDisconnected(let peerId, _):
            lock.lock()
            sessions.removeValue(forKey: peerId)
            lock.unlock()

        case .packetReceived(let packet, let peerId, _):
            lock.lock()
            let session = sessions[peerId]
            lock.unlock()

            if let session = session {
                Task {
                    await session.handlePacket(packet)
                }
            }

        default:
            break
        }
    }
}
