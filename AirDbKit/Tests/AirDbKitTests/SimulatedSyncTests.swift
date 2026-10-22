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

final class SimulatedSyncTests: XCTestCase {

    func testBidirectionalSyncConvergence() async throws {
        let network = SimulatedNetwork()

        let adapterA = MemoryDbAdapter()
        let transportA = SimulatedTransport(nodeId: "node-a", network: network)
        let engineA = AirDbEngine(nodeName: "node-a", adapter: adapterA, transport: transportA)

        let adapterB = MemoryDbAdapter()
        let transportB = SimulatedTransport(nodeId: "node-b", network: network)
        let engineB = AirDbEngine(nodeName: "node-b", adapter: adapterB, transport: transportB)

        // Save records locally on Node A prior to connection
        _ = try await engineA.saveRecord(
            collection: "patients",
            id: "p-001",
            type: .insert,
            payload: Data("{\"name\": \"Agus\", \"triage\": \"RED\"}".utf8)
        )

        _ = try await engineA.saveRecord(
            collection: "patients",
            id: "p-002",
            type: .insert,
            payload: Data("{\"name\": \"Budi\", \"triage\": \"YELLOW\"}".utf8)
        )

        // Save distinct record on Node B
        _ = try await engineB.saveRecord(
            collection: "supplies",
            id: "s-100",
            type: .insert,
            payload: Data("{\"item\": \"Saline IV 500ml\", \"qty\": 120}".utf8)
        )

        // Start engines -> Triggers discovery, link-up, handshake, reconciliation, and delta streaming
        engineA.start()
        engineB.start()

        // Wait for asynchronous replication across virtual radio network
        let deadline = Date().addingTimeInterval(2.0)
        var converged = false

        while Date() < deadline {
            let patientAOnB = await adapterB.getRecord(collection: "patients", id: "p-001")
            let supplyBOnA = await adapterA.getRecord(collection: "supplies", id: "s-100")

            if patientAOnB != nil && supplyBOnA != nil {
                converged = true
                break
            }
            try await Task.sleep(nanoseconds: 50_000_000) // 50ms
        }

        XCTAssertTrue(converged, "Engines failed to converge within 2.0 seconds")

        // Verify data integrity
        let recP1 = await adapterB.getRecord(collection: "patients", id: "p-001")
        XCTAssertEqual(recP1?.collection, "patients")
        XCTAssertEqual(recP1?.id, "p-001")

        let recS100 = await adapterA.getRecord(collection: "supplies", id: "s-100")
        XCTAssertEqual(recS100?.collection, "supplies")
        XCTAssertEqual(recS100?.id, "s-100")

        engineA.stop()
        engineB.stop()
    }

    func testEncryptedMeshReplicationConvergence() async throws {
        let network = SimulatedNetwork()
        let clusterKey = Data(repeating: 0xEE, count: 32)

        let adapterA = MemoryDbAdapter()
        let transportA = SimulatedTransport(nodeId: "node-enc-a", network: network)
        let engineA = AirDbEngine(nodeName: "node-enc-a", adapter: adapterA, transport: transportA, clusterKey: clusterKey)

        let adapterB = MemoryDbAdapter()
        let transportB = SimulatedTransport(nodeId: "node-enc-b", network: network)
        let engineB = AirDbEngine(nodeName: "node-enc-b", adapter: adapterB, transport: transportB, clusterKey: clusterKey)

        _ = try await engineA.saveRecord(
            collection: "patients",
            id: "p-enc-001",
            type: .insert,
            payload: Data("{\"name\": \"Secret Survivor\"}".utf8)
        )

        engineA.start()
        engineB.start()

        let deadline = Date().addingTimeInterval(2.0)
        var converged = false

        while Date() < deadline {
            let record = await adapterB.getRecord(collection: "patients", id: "p-enc-001")
            if record != nil {
                converged = true
                break
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }

        XCTAssertTrue(converged, "Encrypted record should replicate across clusterKey-authenticated nodes")

        engineA.stop()
        engineB.stop()
    }

    func testSyncSessionQueueProcessingAndStop() async throws {
        let network = SimulatedNetwork()
        let adapter = MemoryDbAdapter()
        let transport = SimulatedTransport(nodeId: "node-test", network: network)
        let clock = HybridLogicalClock(nodeId: "node-test")
        let vc = VectorClock()

        let session = SyncSession(
            peerId: "remote-peer",
            linkId: "link-1",
            localNodeId: 101,
            localNodeName: "node-test",
            adapter: adapter,
            clock: clock,
            vectorClock: vc,
            transport: transport
        ) { _, _ in }

        // Enqueue handshake packets in FIFO order
        let handshakePacket = AirDbPacket(
            type: .handshake,
            senderId: 202,
            payload: Data("remote-peer".utf8)
        )
        session.enqueue(packet: handshakePacket)

        // Give the sequential queue processor time to execute
        try await Task.sleep(nanoseconds: 20_000_000)

        // Stopping the session cleanly finishes stream and cancels background task
        session.stop()
    }
}
