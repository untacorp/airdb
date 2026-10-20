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

final class AssemblyBufferTests: XCTestCase {

    func testFragmentationAndReassemblyRoundtrip() {
        let originalPayload = Data((0..<2000).map { UInt8($0 % 256) })
        let originalPacket = AirDbPacket(
            version: 1,
            type: .deltaBatch,
            senderId: 777,
            recipientId: 888,
            payload: originalPayload
        )

        let fragments = PacketFragmenter.fragment(packet: originalPacket, maxChunkSize: 100)
        XCTAssertGreaterThan(fragments.count, 1)

        let buffer = AssemblyBuffer()
        var assembledPacket: AirDbPacket?

        // Ingest fragments in random/reverse order to test robustness
        for fragment in fragments.reversed() {
            if let result = buffer.ingest(fragment) {
                assembledPacket = result
            }
        }

        XCTAssertNotNil(assembledPacket)
        XCTAssertEqual(assembledPacket?.type, .deltaBatch)
        XCTAssertEqual(assembledPacket?.senderId, 777)
        XCTAssertEqual(assembledPacket?.recipientId, 888)
        XCTAssertEqual(assembledPacket?.payload, originalPayload)
    }

    func testCorruptedFragmentPayloadRejected() {
        let buffer = AssemblyBuffer()
        let corruptedPacket = AirDbPacket(
            type: .fragment,
            senderId: 1,
            payload: Data([0x01, 0x02]) // Truncated fragment header (< 13 bytes)
        )

        let result = buffer.ingest(corruptedPacket)
        XCTAssertNil(result)
    }
}
