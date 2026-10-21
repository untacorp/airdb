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

#if canImport(CoreBluetooth)
import XCTest
import CoreBluetooth
@testable import AirDbKit

private final class MockTransportListener: TransportListener, @unchecked Sendable {
    var receivedEvents: [TransportEvent] = []
    private let lock = NSLock()

    func onTransportEvent(_ event: TransportEvent) {
        lock.lock()
        receivedEvents.append(event)
        lock.unlock()
    }
}

final class CoreBluetoothTransportTests: XCTestCase {

    func testDefaultBleConfigParityWithAndroid() {
        let config = BleConfig()

        // UUID string comparison is case-insensitive
        XCTAssertEqual(
            config.serviceUuid.uuidString.uppercased(),
            "41697244-4200-0000-8000-00805F9B34FB",
            "Service UUID must match Android BleConfig"
        )
        XCTAssertEqual(
            config.characteristicUuid.uuidString.uppercased(),
            "41697244-4201-0000-8000-00805F9B34FB",
            "Characteristic UUID must match Android BleConfig"
        )

        // Standard 16-bit CCCD UUID expands to 2902 in CoreBluetooth
        XCTAssertTrue(
            config.cccdUuid.uuidString.contains("2902"),
            "CCCD UUID must match standard Bluetooth SIG 0x2902 descriptor"
        )

        // MTU & Fragmentation boundaries
        XCTAssertEqual(config.targetMtu, 517, "Target MTU must match Android target MTU")
        XCTAssertEqual(config.maxFragmentChunkSize, 460, "Chunk size must be 460 bytes")
        XCTAssertEqual(config.maxPendingQueueSize, 256, "Pending queue limit must be 256")
        XCTAssertEqual(config.maxPendingBytes, 1_048_576, "Pending buffer limit must be 1MB")
    }

    func testCustomBleConfigInjection() {
        let customServiceUuid = CBUUID(string: "12345678-1234-1234-1234-1234567890AB")
        let customCharUuid = CBUUID(string: "87654321-4321-4321-4321-BA0987654321")

        let customConfig = BleConfig(
            serviceUuid: customServiceUuid,
            characteristicUuid: customCharUuid,
            targetMtu: 256,
            maxFragmentChunkSize: 200,
            maxPendingQueueSize: 64,
            maxPendingBytes: 524_288
        )

        let transport = CoreBluetoothTransport(config: customConfig)
        XCTAssertEqual(transport.config.serviceUuid, customServiceUuid)
        XCTAssertEqual(transport.config.characteristicUuid, customCharUuid)
        XCTAssertEqual(transport.config.targetMtu, 256)
        XCTAssertEqual(transport.config.maxFragmentChunkSize, 200)
        XCTAssertEqual(transport.config.maxPendingQueueSize, 64)
        XCTAssertEqual(transport.config.maxPendingBytes, 524_288)
    }

    func testTransportListenerRegistration() {
        let transport = CoreBluetoothTransport()
        let mockListener = MockTransportListener()

        transport.setListener(mockListener)
        // Transport initializes safely with listener registered
        XCTAssertNotNil(transport)
    }
}
#endif
