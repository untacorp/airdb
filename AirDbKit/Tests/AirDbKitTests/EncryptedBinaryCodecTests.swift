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
import CryptoKit
@testable import AirDbKit

final class EncryptedBinaryCodecTests: XCTestCase {

    func testEncryptedPacketEncodeDecodeRoundtrip() {
        let clusterKey = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let payload = Data("Confidential disaster survivor vitals: priority RED".utf8)
        let packet = AirDbPacket(
            type: .deltaBatch,
            senderId: 12345,
            recipientId: 67890,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet, clusterKey: clusterKey)
        let decoded = BinaryCodec.decode(encoded, clusterKey: clusterKey)

        XCTAssertNotNil(decoded)
        XCTAssertTrue(decoded?.isEncrypted == true)
        XCTAssertEqual(decoded?.type, .deltaBatch)
        XCTAssertEqual(decoded?.senderId, 12345)
        XCTAssertEqual(decoded?.recipientId, 67890)
        XCTAssertEqual(decoded?.payload, payload)
    }

    func testEncryptedPacketFailsWithoutKeyOrWithWrongKey() {
        let clusterKey = Data(repeating: 0x42, count: 32)
        let wrongKey = Data(repeating: 0x99, count: 32)
        let payload = Data("Top secret medical inventory".utf8)
        let packet = AirDbPacket(
            type: .deltaBatch,
            senderId: 100,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet, clusterKey: clusterKey)

        // Without key must return nil
        XCTAssertNil(BinaryCodec.decode(encoded, clusterKey: nil))

        // With wrong key must return nil (Poly1305 MAC fails)
        XCTAssertNil(BinaryCodec.decode(encoded, clusterKey: wrongKey))
    }

    func testEncryptedPacketFailsOnTamperedCiphertext() {
        let clusterKey = Data(repeating: 0x55, count: 32)
        let payload = Data("Sensitive search and rescue grid coordinates".utf8)
        let packet = AirDbPacket(
            type: .deltaBatch,
            senderId: 888,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet, clusterKey: clusterKey)

        var tampered = encoded
        tampered[tampered.count - 5] ^= 0x01

        XCTAssertNil(BinaryCodec.decode(tampered, clusterKey: clusterKey))
    }

    func testEncryptedPacketFailsOnTamperedHeader() {
        let clusterKey = Data(repeating: 0x77, count: 32)
        let payload = Data("Authentic field triage order".utf8)
        let packet = AirDbPacket(
            type: .deltaBatch,
            senderId: 555,
            payload: payload
        )

        let encoded = BinaryCodec.encode(packet, clusterKey: clusterKey)

        // Tamper with the senderId inside the header (byte 16)
        var tampered = encoded
        tampered[16] ^= 0xFF

        // Decryption MUST fail because header was part of Authenticated Additional Data (AAD)
        XCTAssertNil(BinaryCodec.decode(tampered, clusterKey: clusterKey))
    }

    func testEncryptedPacketWithCompressionAndFragmentation() {
        let clusterKey = Data(repeating: 0x33, count: 32)
        let repetitiveString = String(repeating: "PATIENT_RECORD_TRIAGE_EMERGENCY_DATA_", count: 30)
        let payload = Data(repetitiveString.utf8)
        let packet = AirDbPacket(
            type: .deltaBatch,
            senderId: 999,
            payload: payload
        )

        let fragments = PacketFragmenter.fragment(packet: packet, clusterKey: clusterKey, maxChunkSize: 50)
        XCTAssertTrue(fragments.count > 1, "Should be fragmented across multiple packets")

        let assemblyBuffer = AssemblyBuffer()
        var assembled: AirDbPacket? = nil

        for fragment in fragments {
            if let res = assemblyBuffer.ingest(fragment, clusterKey: clusterKey) {
                assembled = res
            }
        }

        XCTAssertNotNil(assembled)
        XCTAssertTrue(assembled?.isEncrypted == true)
        XCTAssertTrue(assembled?.isCompressed == true)
        XCTAssertEqual(assembled?.payload, payload)
    }
}
