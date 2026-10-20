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

final class DeltaBatchTests: XCTestCase {

    func testSerializeAndDeserialize() {
        let payload1 = "{\"name\":\"Budi\"}".data(using: .utf8)!
        let payload2 = "{\"status\":\"Evacuated\"}".data(using: .utf8)!

        let records = [
            ChangeRecord(
                id: "rec-1",
                collection: "survivors",
                type: .insert,
                payload: payload1,
                hlcTimestamp: "1700000000000-0-nodeA"
            ),
            ChangeRecord(
                id: "rec-2",
                collection: "survivors",
                type: .update,
                payload: payload2,
                hlcTimestamp: "1700000000001-0-nodeB"
            )
        ]

        let serialized = DeltaBatch.serialize(records)
        XCTAssertFalse(serialized.isEmpty)

        let deserialized = DeltaBatch.deserialize(serialized)
        XCTAssertEqual(deserialized.count, 2)

        XCTAssertEqual(deserialized[0].id, "rec-1")
        XCTAssertEqual(deserialized[0].collection, "survivors")
        XCTAssertEqual(deserialized[0].type, MutationType.insert)
        XCTAssertEqual(deserialized[0].payload, payload1)
        XCTAssertEqual(deserialized[0].hlcTimestamp, "1700000000000-0-nodeA")

        XCTAssertEqual(deserialized[1].id, "rec-2")
        XCTAssertEqual(deserialized[1].collection, "survivors")
        XCTAssertEqual(deserialized[1].type, MutationType.update)
        XCTAssertEqual(deserialized[1].payload, payload2)
        XCTAssertEqual(deserialized[1].hlcTimestamp, "1700000000001-0-nodeB")
    }

    func testTruncatedDataSafeHandling() {
        let payload = "{\"test\":true}".data(using: .utf8)!
        let records = [
            ChangeRecord(
                id: "rec-1",
                collection: "test",
                type: .insert,
                payload: payload,
                hlcTimestamp: "1700000000000-0-nodeA"
            )
        ]

        let serialized = DeltaBatch.serialize(records)
        // Truncate serialized bytes progressively and assert deserializer returns gracefully without throwing
        for i in 0..<serialized.count {
            let truncated = serialized.subdata(in: 0..<i)
            let result = DeltaBatch.deserialize(truncated)
            XCTAssertTrue(result.count <= 1)
        }
    }
}
