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

final class BinaryCodecTests: XCTestCase {

    func testEncodeDecodeBroadcastRoundtrip() {
        let payload = Data("Hello AirDB P2P BLE World!".utf8)
        let packet = AirDbPacket(
            version: 1,
            type: .handshake,
            flags: 0,
            timestamp: 1776543210000,
            senderId: 0x1234567890ABCDEF,
            recipientId: nil,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet)
        let decoded = BinaryCodec.decode(encoded)

        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.version, 1)
        XCTAssertEqual(decoded?.type, .handshake)
        XCTAssertEqual(decoded?.senderId, 0x1234567890ABCDEF)
        XCTAssertNil(decoded?.recipientId)
        XCTAssertEqual(decoded?.payload, payload)
        XCTAssertEqual(decoded?.timestamp, 1776543210000)
    }

    func testEncodeDecodeUnicastWithRecipient() {
        let payload = Data([1, 2, 3, 4, 5])
        let packet = AirDbPacket(
            version: 1,
            type: .deltaBatch,
            flags: 0,
            timestamp: 1776543210000,
            senderId: 111,
            recipientId: 222,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet)
        let decoded = BinaryCodec.decode(encoded)

        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.senderId, 111)
        XCTAssertEqual(decoded?.recipientId, 222)
        XCTAssertEqual(decoded?.payload, payload)
    }

    func testInvalidMagicBytesRejected() {
        var data = Data([0xFF, 0xFF, 1, 1, 0])
        data.append(Data(repeating: 0, count: 20))
        let decoded = BinaryCodec.decode(data)
        XCTAssertNil(decoded)
    }

    func testTruncatedHeaderRejected() {
        let data = Data([0x41, 0x44, 1, 1])
        let decoded = BinaryCodec.decode(data)
        XCTAssertNil(decoded)
    }

    func testLargePayloadCompressionRoundtrip() {
        // Repeated data compresses well
        let repeatedString = String(repeating: "AirDB-Decentralized-P2P-Database-Engine-", count: 20)
        let payload = Data(repeatedString.utf8)
        XCTAssertGreaterThan(payload.count, 256)

        let packet = AirDbPacket(
            version: 1,
            type: .deltaBatch,
            senderId: 999,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet, autoCompressThreshold: 256)
        // Verify compressed wire size is smaller than raw payload
        XCTAssertLessThan(encoded.count, payload.count)

        let decoded = BinaryCodec.decode(encoded)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.payload, payload)
    }
}
