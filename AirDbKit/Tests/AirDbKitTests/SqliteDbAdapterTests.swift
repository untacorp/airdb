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

final class SqliteDbAdapterTests: XCTestCase {

    func testInMemoryInitialization() async throws {
        let adapter = try SqliteDbAdapter.inMemory()
        let latestHlc = try await adapter.getLatestHlc()
        XCTAssertNil(latestHlc)
        await adapter.close()
    }

    func testEntityInsertAndRead() async throws {
        let adapter = try SqliteDbAdapter.inMemory()

        let payload = Data("{\"status\":\"active\",\"code\":42}".utf8)
        let record = ChangeRecord(
            id: "rec-1",
            collection: "reports",
            type: .insert,
            payload: payload,
            hlcTimestamp: "1000-0-node1"
        )

        try await adapter.applyChanges([record])

        let fetched = try await adapter.getActiveRecord(collection: "reports", id: "rec-1")
        XCTAssertNotNil(fetched)
        XCTAssertEqual(fetched?.id, "rec-1")
        XCTAssertEqual(fetched?.collection, "reports")
        XCTAssertEqual(fetched?.type, .insert)
        XCTAssertEqual(fetched?.payload, payload)
        XCTAssertEqual(fetched?.hlcTimestamp, "1000-0-node1")

        await adapter.close()
    }

    func testLwwConflictResolution() async throws {
        let adapter = try SqliteDbAdapter.inMemory()

        // Mutation 1: Initial insert
        let payload1 = Data("{\"version\":1}".utf8)
        let rec1 = ChangeRecord(
            id: "doc-1",
            collection: "docs",
            type: .insert,
            payload: payload1,
            hlcTimestamp: "1000-2-nodeA"
        )
        try await adapter.applyChanges([rec1])

        // Mutation 2: Stale mutation (lower HLC: 999 < 1000)
        let stalePayload = Data("{\"version\":stale}".utf8)
        let recStale = ChangeRecord(
            id: "doc-1",
            collection: "docs",
            type: .update,
            payload: stalePayload,
            hlcTimestamp: "999-5-nodeA"
        )
        try await adapter.applyChanges([recStale])

        // Verify rec1 is retained
        let current1 = try await adapter.getActiveRecord(collection: "docs", id: "doc-1")
        XCTAssertEqual(current1?.payload, payload1)

        // Mutation 3: Newer mutation (higher counter: 10 > 2 numerically)
        let newerPayload = Data("{\"version\":2}".utf8)
        let recNewer = ChangeRecord(
            id: "doc-1",
            collection: "docs",
            type: .update,
            payload: newerPayload,
            hlcTimestamp: "1000-10-nodeA"
        )
        try await adapter.applyChanges([recNewer])

        // Verify recNewer overwrote
        let current2 = try await adapter.getActiveRecord(collection: "docs", id: "doc-1")
        XCTAssertEqual(current2?.payload, newerPayload)

        await adapter.close()
    }

    func testTombstonesAndCompaction() async throws {
        let adapter = try SqliteDbAdapter.inMemory()

        let payload = Data("{\"note\":\"temporary\"}".utf8)
        let insertRec = ChangeRecord(
            id: "tomb-1",
            collection: "notes",
            type: .insert,
            payload: payload,
            hlcTimestamp: "1000-0-nodeA"
        )
        try await adapter.applyChanges([insertRec])

        let isDeletedBefore = try await adapter.isRecordDeleted(collection: "notes", id: "tomb-1")
        XCTAssertFalse(isDeletedBefore)
        let activeBefore = try await adapter.getActiveRecord(collection: "notes", id: "tomb-1")
        XCTAssertNotNil(activeBefore)

        // Soft delete via tombstone
        let deleteRec = ChangeRecord(
            id: "tomb-1",
            collection: "notes",
            type: .delete,
            payload: Data(),
            hlcTimestamp: "1001-0-nodeA"
        )
        try await adapter.applyChanges([deleteRec])

        let isDeletedAfter = try await adapter.isRecordDeleted(collection: "notes", id: "tomb-1")
        XCTAssertTrue(isDeletedAfter)
        let activeAfter = try await adapter.getActiveRecord(collection: "notes", id: "tomb-1")
        XCTAssertNil(activeAfter)

        // Purge via compact
        try await adapter.compact(pruneHlcThreshold: "1002-0-nodeA")
        let entity = try await adapter.readEntity(collection: "notes", id: "tomb-1")
        XCTAssertNil(entity)

        await adapter.close()
    }

    func testVectorClockMissingChanges() async throws {
        let adapter = try SqliteDbAdapter.inMemory()

        let changes = [
            ChangeRecord(id: "1", collection: "items", type: .insert, payload: Data([0x01]), hlcTimestamp: "100-0-nodeA"),
            ChangeRecord(id: "2", collection: "items", type: .insert, payload: Data([0x02]), hlcTimestamp: "200-0-nodeA"),
            ChangeRecord(id: "3", collection: "items", type: .insert, payload: Data([0x03]), hlcTimestamp: "150-0-nodeB")
        ]
        try await adapter.applyChanges(changes)

        // Peer knows nodeA up to 100-0-nodeA, knows nothing of nodeB
        let peerClock = ["nodeA": "100-0-nodeA"]
        let missing = try await adapter.getMissingChanges(peerClock: peerClock, limit: 100)

        let missingIds = missing.map { $0.id }
        XCTAssertEqual(missingIds.count, 2)
        XCTAssertTrue(missingIds.contains("2"))
        XCTAssertTrue(missingIds.contains("3"))

        await adapter.close()
    }

    func testDiskPersistenceAcrossRestarts() async throws {
        let tempDir = FileManager.default.temporaryDirectory
        let dbPath = tempDir.appendingPathComponent("airdb_test_\(UUID().uuidString).sqlite").path

        let payload = Data("{\"mission\":\"survival\",\"code\":99}".utf8)

        // Session 1: Create and write to disk
        let adapter1 = try SqliteDbAdapter.open(path: dbPath)
        let record = ChangeRecord(
            id: "persisted-1",
            collection: "missions",
            type: .insert,
            payload: payload,
            hlcTimestamp: "5000-0-nodeX"
        )
        try await adapter1.applyChanges([record])
        await adapter1.close()

        // Session 2: Reopen same file and verify contents exist
        let adapter2 = try SqliteDbAdapter.open(path: dbPath)
        let readBack = try await adapter2.getActiveRecord(collection: "missions", id: "persisted-1")

        XCTAssertNotNil(readBack)
        XCTAssertEqual(readBack?.id, "persisted-1")
        XCTAssertEqual(readBack?.payload, payload)
        XCTAssertEqual(readBack?.hlcTimestamp, "5000-0-nodeX")

        let active = try await adapter2.getActiveRecords(collection: "missions")
        XCTAssertEqual(active.count, 1)

        await adapter2.close()
        try? FileManager.default.removeItem(atPath: dbPath)
    }

    func testEntropyBlobPayload() async throws {
        let adapter = try SqliteDbAdapter.inMemory()

        // Generate 2048 non-repeating entropy bytes
        var entropyBytes = [UInt8]()
        for i in 0..<2048 {
            entropyBytes.append(UInt8((i * 37 + 13) % 256))
        }
        let entropyData = Data(entropyBytes)

        let record = ChangeRecord(
            id: "entropy-1",
            collection: "blobs",
            type: .insert,
            payload: entropyData,
            hlcTimestamp: "1000-1-nodeZ"
        )
        try await adapter.applyChanges([record])

        let fetched = try await adapter.getActiveRecord(collection: "blobs", id: "entropy-1")
        XCTAssertNotNil(fetched)
        XCTAssertEqual(fetched?.payload, entropyData)
        XCTAssertEqual(fetched?.payload.count, 2048)

        await adapter.close()
    }
}
