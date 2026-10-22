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

/// Internal model representing an entity stored in an underlying persistence engine.
public struct StoredEntity: Sendable, Hashable {
    public let collection: String
    public let id: String
    public let type: MutationType
    public let payload: Data
    public let hlcTimestamp: String
    public let updatedAt: Int64

    public init(
        collection: String,
        id: String,
        type: MutationType,
        payload: Data,
        hlcTimestamp: String,
        updatedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.collection = collection
        self.id = id
        self.type = type
        self.payload = payload
        self.hlcTimestamp = hlcTimestamp
        self.updatedAt = updatedAt
    }
}

/// Primitive storage contract required to adapt any custom storage engine (GRDB, CoreData, Realm) to AirDB.
public protocol AdaptiveStorageContract: Sendable {
    /// Read entity state by collection and id, or nil if not found.
    func readEntity(collection: String, id: String) async throws -> StoredEntity?

    /// Persist or update entity state in storage.
    func writeEntity(_ entity: StoredEntity) async throws

    /// Read mutations from the changelog strictly newer than `sinceHlcTimestamp`, up to `limit`.
    func readChangelogSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord]

    /// Append an applied mutation to the changelog.
    func appendChangelog(_ record: ChangeRecord) async throws

    /// Execute the given closure within an atomic transaction.
    func executeInTransaction<T: Sendable>(_ block: @Sendable () async throws -> T) async throws -> T

    /// Purge tombstones older than `pruneHlcThreshold`.
    func compact(pruneHlcThreshold: String) async throws
}

public extension AdaptiveStorageContract {
    func executeInTransaction<T: Sendable>(_ block: @Sendable () async throws -> T) async throws -> T {
        try await block()
    }
}

/// Universal actor implementing `AirDbAdapter` over any `AdaptiveStorageContract`.
///
/// Encapsulates canonical Last-Write-Wins (LWW) conflict resolution, tombstone anti-resurrection guards,
/// and peer vector clock filtering.
public actor AdaptiveAirDbAdapter: AirDbAdapter {
    private let storage: any AdaptiveStorageContract

    public init(storage: any AdaptiveStorageContract) {
        self.storage = storage
    }

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int = 100) async throws -> [ChangeRecord] {
        try await storage.readChangelogSince(sinceHlcTimestamp: sinceHlcTimestamp, limit: limit)
    }

    public func getMissingChanges(peerClock: [String: String], limit: Int = 500) async throws -> [ChangeRecord] {
        let candidates = try await storage.readChangelogSince(sinceHlcTimestamp: nil, limit: limit * 2)
        return candidates.filter { record in
            let origin = HlcComparator.extractNodeId(from: record.hlcTimestamp) ?? ""
            guard let peerKnown = peerClock[origin] else { return true }
            return HlcComparator.compare(record.hlcTimestamp, peerKnown) == .orderedDescending
        }.prefix(limit).map { $0 }
    }

    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        try await storage.executeInTransaction {
            for change in changes {
                let existing = try await self.storage.readEntity(collection: change.collection, id: change.id)

                // Last-Write-Wins (LWW) comparison via canonical HLC total order
                if let existing = existing {
                    if HlcComparator.compare(change.hlcTimestamp, existing.hlcTimestamp) != .orderedDescending {
                        continue // Discard stale mutation
                    }
                }

                let entity = StoredEntity(
                    collection: change.collection,
                    id: change.id,
                    type: change.type,
                    payload: change.payload,
                    hlcTimestamp: change.hlcTimestamp
                )
                try await self.storage.writeEntity(entity)
                try await self.storage.appendChangelog(change)
                self.updateMaxSeenHlc(change.hlcTimestamp)
            }
        }
    }

    private var maxSeenHlc: String?

    private func updateMaxSeenHlc(_ hlc: String) {
        if let current = maxSeenHlc {
            if HlcComparator.compare(hlc, current) == .orderedDescending {
                maxSeenHlc = hlc
            }
        } else {
            maxSeenHlc = hlc
        }
    }

    public func getLatestHlc() async throws -> String? {
        if let cached = maxSeenHlc {
            return cached
        }

        var cursor: String? = nil
        var maxHlc: String? = nil
        let pageSize = 1000

        while true {
            let batch = try await storage.readChangelogSince(sinceHlcTimestamp: cursor, limit: pageSize)
            if batch.isEmpty { break }
            for record in batch {
                if let current = maxHlc {
                    if HlcComparator.compare(record.hlcTimestamp, current) == .orderedDescending {
                        maxHlc = record.hlcTimestamp
                    }
                } else {
                    maxHlc = record.hlcTimestamp
                }
            }
            if batch.count < pageSize { break }
            cursor = batch.last?.hlcTimestamp
        }

        maxSeenHlc = maxHlc
        return maxHlc
    }

    public func readEntity(collection: String, id: String) async throws -> StoredEntity? {
        try await storage.readEntity(collection: collection, id: id)
    }

    public func compact(pruneHlcThreshold: String) async throws {
        try await storage.compact(pruneHlcThreshold: pruneHlcThreshold)
    }
}
