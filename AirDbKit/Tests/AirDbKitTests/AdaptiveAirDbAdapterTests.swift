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

import XCTest
@testable import AirDbKit

private final class MockAdaptiveStorage: AdaptiveStorageContract, @unchecked Sendable {
    var entities: [String: StoredEntity] = [:]
    var changelog: [ChangeRecord] = []
    let lock = NSLock()

    private func key(_ collection: String, _ id: String) -> String {
        "\(collection):\(id)"
    }

    func readEntity(collection: String, id: String) async throws -> StoredEntity? {
        lock.lock()
        defer { lock.unlock() }
        return entities[key(collection, id)]
    }

    func writeEntity(_ entity: StoredEntity) async throws {
        lock.lock()
        defer { lock.unlock() }
        entities[key(entity.collection, entity.id)] = entity
    }

    func readChangelogSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord] {
        lock.lock()
        defer { lock.unlock() }
        if let since = sinceHlcTimestamp {
            return changelog.filter { HlcComparator.compare($0.hlcTimestamp, since) == .orderedDescending }.prefix(limit).map { $0 }
        }
        return Array(changelog.prefix(limit))
    }

    func appendChangelog(_ record: ChangeRecord) async throws {
        lock.lock()
        defer { lock.unlock() }
        changelog.append(record)
    }
}

final class AdaptiveAirDbAdapterTests: XCTestCase {

    func testLwwReconciliation() async throws {
        let storage = MockAdaptiveStorage()
        let adapter = AdaptiveAirDbAdapter(storage: storage)

        let payload1 = "{\"v\":1}".data(using: .utf8)!
        let rec1 = ChangeRecord(
            id: "doc-1",
            collection: "users",
            type: .insert,
            payload: payload1,
            hlcTimestamp: "1000-2-nodeA"
        )
        try await adapter.applyChanges([rec1])

        let stored1 = try await adapter.readEntity(collection: "users", id: "doc-1")
        XCTAssertEqual(stored1?.payload, payload1)

        // Mutation 2: newer counter (10 > 2 numerically)
        let payload2 = "{\"v\":2}".data(using: .utf8)!
        let rec2 = ChangeRecord(
            id: "doc-1",
            collection: "users",
            type: .update,
            payload: payload2,
            hlcTimestamp: "1000-10-nodeA"
        )
        try await adapter.applyChanges([rec2])

        let stored2 = try await adapter.readEntity(collection: "users", id: "doc-1")
        XCTAssertEqual(stored2?.payload, payload2)

        // Mutation 3: stale update (1 < 10)
        let stalePayload = "{\"v\":stale}".data(using: .utf8)!
        let recStale = ChangeRecord(
            id: "doc-1",
            collection: "users",
            type: .update,
            payload: stalePayload,
            hlcTimestamp: "1000-1-nodeA"
        )
        try await adapter.applyChanges([recStale])

        let stored3 = try await adapter.readEntity(collection: "users", id: "doc-1")
        XCTAssertEqual(stored3?.payload, payload2)
    }

    func testTombstoneAntiResurrection() async throws {
        let storage = MockAdaptiveStorage()
        let adapter = AdaptiveAirDbAdapter(storage: storage)

        // Insert
        let insertRec = ChangeRecord(
            id: "item-1",
            collection: "inventory",
            type: .insert,
            payload: "tent".data(using: .utf8)!,
            hlcTimestamp: "1000-0-nodeA"
        )
        try await adapter.applyChanges([insertRec])

        // Delete (tombstone)
        let deleteRec = ChangeRecord(
            id: "item-1",
            collection: "inventory",
            type: .delete,
            payload: Data(),
            hlcTimestamp: "1000-5-nodeA"
        )
        try await adapter.applyChanges([deleteRec])

        let entityDeleted = try await adapter.readEntity(collection: "inventory", id: "item-1")
        XCTAssertEqual(entityDeleted?.type, .delete)

        // Late arriving insert with older HLC
        let lateInsert = ChangeRecord(
            id: "item-1",
            collection: "inventory",
            type: .insert,
            payload: "tent-resurrected".data(using: .utf8)!,
            hlcTimestamp: "1000-2-nodeA"
        )
        try await adapter.applyChanges([lateInsert])

        // Must remain deleted (anti-resurrection guaranteed)
        let entityAfterLate = try await adapter.readEntity(collection: "inventory", id: "item-1")
        XCTAssertEqual(entityAfterLate?.type, .delete)
    }
}
