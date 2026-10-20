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

final class HlcComparatorTests: XCTestCase {

    func testLogicalCounterPastNine() {
        // String comparison fails: "1000-10-nodeA" < "1000-2-nodeA" ('1' < '2')
        // HlcComparator numerically orders 10 > 2!
        let t2 = "1000-2-nodeA"
        let t10 = "1000-10-nodeA"

        XCTAssertEqual(HlcComparator.compare(t10, t2), .orderedDescending)
        XCTAssertEqual(HlcComparator.compare(t2, t10), .orderedAscending)
    }

    func testPhysicalTimeDigitLength() {
        let tOld = "999999999-0-nodeA"
        let tNew = "1000000000-0-nodeA"

        XCTAssertEqual(HlcComparator.compare(tNew, tOld), .orderedDescending)
        XCTAssertEqual(HlcComparator.compare(tOld, tNew), .orderedAscending)
    }

    func testNodeIdTieBreaker() {
        let tA = "1000-0-nodeA"
        let tB = "1000-0-nodeB"

        XCTAssertEqual(HlcComparator.compare(tB, tA), .orderedDescending)
        XCTAssertEqual(HlcComparator.compare(tA, tA), .orderedSame)
    }

    func testExtractNodeId() {
        XCTAssertEqual(HlcComparator.extractNodeId(from: "1700000000000-0-nodeA"), "nodeA")
        XCTAssertEqual(HlcComparator.extractNodeId(from: "1700000000000-42-posko-merapi-1"), "posko-merapi-1")
        XCTAssertNil(HlcComparator.extractNodeId(from: "malformed"))
    }
}
