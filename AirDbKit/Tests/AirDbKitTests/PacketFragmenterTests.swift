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

final class PacketFragmenterTests: XCTestCase {

    func testSmallPacketNotFragmented() {
        let packet = AirDbPacket(
            version: 1,
            type: .handshake,
            senderId: 42,
            payload: Data("small".utf8)
        )

        let fragments = PacketFragmenter.fragment(packet: packet, maxChunkSize: 460)
        XCTAssertEqual(fragments.count, 1)
        XCTAssertEqual(fragments[0].type, .handshake)
    }

    func testLargePacketFragmented() {
        let largePayload = Data(repeating: 0xAB, count: 1200)
        let packet = AirDbPacket(
            version: 1,
            type: .deltaBatch,
            senderId: 100,
            payload: largePayload
        )

        let maxChunkSize = 300
        let fragments = PacketFragmenter.fragment(packet: packet, maxChunkSize: maxChunkSize)

        XCTAssertGreaterThan(fragments.count, 1)
        for fragment in fragments {
            XCTAssertEqual(fragment.type, .fragment)
            XCTAssertEqual(fragment.senderId, 100)
        }
    }
}
