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
    public let vectorClock = VectorClock()
    public let adapter: AirDbAdapter
    public let transport: AirDbTransport
    public let clusterKey: Data?
    public let authorKeyPair: Ed25519KeyPair?
    public let enforceSignatures: Bool

    private let lock = NSLock()
    private var sessions: [String: SyncSession] = [:]
    private var sessionTasks: [String: Task<Void, Never>] = [:]
    private var syncListeners: [@Sendable (String, Int) -> Void] = []
    private var isStarted: Bool = false

    public init(
        nodeName: String,
        adapter: AirDbAdapter,
        transport: AirDbTransport,
        clusterKey: Data? = nil,
        authorKeyPair: Ed25519KeyPair? = nil,
        enforceSignatures: Bool = false
    ) {
        self.nodeName = nodeName
        self.localNodeId = AirDbEngine.deterministicNodeId(from: nodeName)
        self.clock = HybridLogicalClock(nodeId: nodeName)
        self.adapter = adapter
        self.transport = transport
        self.clusterKey = clusterKey
        self.authorKeyPair = authorKeyPair
        self.enforceSignatures = enforceSignatures

        if let clusterKey = clusterKey {
            self.transport.setClusterKey(clusterKey)
        }
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
        let activeSessions = Array(sessions.values)
        let activeTasks = Array(sessionTasks.values)
        sessions.removeAll()
        sessionTasks.removeAll()
        lock.unlock()

        for task in activeTasks {
            task.cancel()
        }
        for session in activeSessions {
            session.stop()
        }

        transport.stop()
    }

    /// Records a local mutation (INSERT/UPDATE/DELETE) in the database with an authoritative HLC timestamp.
    @discardableResult
    public func saveRecord(
        collection: String,
        id: String,
        type: MutationType,
        payload: Data
    ) async throws -> ChangeRecord {
        let hlc = clock.now().description
        vectorClock.update(nodeId: nodeName, hlcTimestamp: hlc)

        var record = ChangeRecord(
            id: id,
            collection: collection,
            type: type,
            payload: payload,
            hlcTimestamp: hlc
        )

        if let keyPair = authorKeyPair {
            record = try record.signed(with: keyPair)
        }

        try await adapter.applyChanges([record])

        let activeSessions = lock.withScopedLock { Array(sessions.values) }

        for session in activeSessions {
            await session.notifyLocalMutation()
        }

        return record
    }

    /// Convenience insert/update saving serialized String UTF-8 payload.
    @discardableResult
    public func put(collection: String, id: String, jsonString: String) async throws -> ChangeRecord {
        try await saveRecord(
            collection: collection,
            id: id,
            type: .update,
            payload: Data(jsonString.utf8)
        )
    }

    /// Convenience soft-delete saving empty tombstone payload.
    @discardableResult
    public func delete(collection: String, id: String) async throws -> ChangeRecord {
        try await saveRecord(
            collection: collection,
            id: id,
            type: .delete,
            payload: Data()
        )
    }

    /// Registers a callback listener invoked whenever changes are applied from a peer.
    public func addSyncListener(_ listener: @escaping @Sendable (String, Int) -> Void) {
        lock.withScopedLock {
            syncListeners.append(listener)
        }
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
                vectorClock: vectorClock,
                transport: transport,
                clusterKey: clusterKey,
                authorKeyPair: authorKeyPair,
                enforceSignatures: enforceSignatures
            ) { [weak self] pId, changeCount in
                guard let self = self else { return }
                let listeners = self.lock.withScopedLock { self.syncListeners }
                for listener in listeners {
                    listener(pId, changeCount)
                }
            }

            let task = Task<Void, Never> { [weak session] in
                await session?.start()
            }

            lock.withScopedLock {
                sessions[peerId] = session
                sessionTasks[peerId] = task
            }

        case .linkDisconnected(let peerId, _):
            let (session, task) = lock.withScopedLock {
                let s = sessions.removeValue(forKey: peerId)
                let t = sessionTasks.removeValue(forKey: peerId)
                return (s, t)
            }
            task?.cancel()
            session?.stop()

        case .packetReceived(let packet, let peerId, let linkId):
            let session = lock.withScopedLock { () -> SyncSession in
                if let s = sessions[peerId] {
                    return s
                }
                let s = SyncSession(
                    peerId: peerId,
                    linkId: linkId,
                    localNodeId: localNodeId,
                    localNodeName: nodeName,
                    adapter: adapter,
                    clock: clock,
                    vectorClock: vectorClock,
                    transport: transport,
                    clusterKey: clusterKey,
                    authorKeyPair: authorKeyPair,
                    enforceSignatures: enforceSignatures
                ) { [weak self] pId, changeCount in
                    guard let self = self else { return }
                    let listeners = self.lock.withScopedLock { self.syncListeners }
                    for listener in listeners {
                        listener(pId, changeCount)
                    }
                }
                sessions[peerId] = s
                return s
            }

            session.enqueue(packet: packet)

        default:
            break
        }
    }
}
