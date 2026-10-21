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

private final class MockTimeSource: @unchecked Sendable {
    var currentTime: Int64
    init(_ initial: Int64) {
        self.currentTime = initial
    }
}

final class HlcTimestampTests: XCTestCase {

    func testHlcTimestampOrdering() {
        let t1 = HlcTimestamp(physicalTime: 1000, logicalCounter: 0, nodeId: "node-a")
        let t2 = HlcTimestamp(physicalTime: 1000, logicalCounter: 1, nodeId: "node-a")
        let t3 = HlcTimestamp(physicalTime: 1001, logicalCounter: 0, nodeId: "node-a")
        let t4 = HlcTimestamp(physicalTime: 1001, logicalCounter: 0, nodeId: "node-b")

        XCTAssertLessThan(t1, t2)
        XCTAssertLessThan(t2, t3)
        XCTAssertLessThan(t3, t4)
        XCTAssertEqual(t1, HlcTimestamp(physicalTime: 1000, logicalCounter: 0, nodeId: "node-a"))
    }

    func testHlcTimestampFormattingAndParsing() {
        let original = HlcTimestamp(physicalTime: 1776543210000, logicalCounter: 42, nodeId: "device-xyz")
        let str = original.description

        XCTAssertEqual(str, "1776543210000-42-device-xyz")

        let parsed = HlcTimestamp.parse(str)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.physicalTime, 1776543210000)
        XCTAssertEqual(parsed?.logicalCounter, 42)
        XCTAssertEqual(parsed?.nodeId, "device-xyz")
    }

    func testHlcClockAdvanceOnLocalEvent() {
        let timeSource = MockTimeSource(1000)
        let clock = HybridLogicalClock(nodeId: "node-1") { timeSource.currentTime }

        let t1 = clock.now()
        XCTAssertEqual(t1.physicalTime, 1000)
        XCTAssertEqual(t1.logicalCounter, 0)

        // Event occurs during same physical millisecond
        let t2 = clock.now()
        XCTAssertEqual(t2.physicalTime, 1000)
        XCTAssertEqual(t2.logicalCounter, 1)

        // Clock moves forward
        timeSource.currentTime = 1005
        let t3 = clock.now()
        XCTAssertEqual(t3.physicalTime, 1005)
        XCTAssertEqual(t3.logicalCounter, 0)
    }

    func testHlcClockAdvanceOnRemoteMessage() {
        let timeSource = MockTimeSource(1000)
        let clock = HybridLogicalClock(nodeId: "node-1") { timeSource.currentTime }

        // Remote message from future clock
        let remoteTs = HlcTimestamp(physicalTime: 1200, logicalCounter: 5, nodeId: "node-2")
        let updated = clock.update(remote: remoteTs)

        XCTAssertEqual(updated.physicalTime, 1200)
        XCTAssertEqual(updated.logicalCounter, 6)

        // Local clock ticks after remote update
        timeSource.currentTime = 1100 // wall clock still behind remote
        let tLocal = clock.now()
        XCTAssertEqual(tLocal.physicalTime, 1200)
        XCTAssertEqual(tLocal.logicalCounter, 7)
    }
}
