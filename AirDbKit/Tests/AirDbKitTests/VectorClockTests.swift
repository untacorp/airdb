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

final class VectorClockTests: XCTestCase {

    func testVectorClockUpdateAndMerge() {
        let vcA = VectorClock()
        vcA.update(nodeId: "node-1", hlcTimestamp: "1000-0-node-1")
        vcA.update(nodeId: "node-2", hlcTimestamp: "1000-2-node-2")

        let vcB = VectorClock()
        vcB.update(nodeId: "node-2", hlcTimestamp: "1000-5-node-2")
        vcB.update(nodeId: "node-3", hlcTimestamp: "1050-0-node-3")

        vcA.merge(vcB)

        XCTAssertEqual(vcA.get("node-1"), "1000-0-node-1")
        XCTAssertEqual(vcA.get("node-2"), "1000-5-node-2")
        XCTAssertEqual(vcA.get("node-3"), "1050-0-node-3")
    }

    func testVectorClockBinaryRoundtrip() {
        let original = VectorClock(initialState: [
            "posko-merapi-1": "1776543210000-1-posko-merapi-1",
            "posko-merapi-2": "1776543220000-4-posko-merapi-2"
        ])

        let binary = original.toBinary()
        let decoded = VectorClock.fromBinary(binary)

        XCTAssertEqual(decoded.get("posko-merapi-1"), "1776543210000-1-posko-merapi-1")
        XCTAssertEqual(decoded.get("posko-merapi-2"), "1776543220000-4-posko-merapi-2")
    }
}
