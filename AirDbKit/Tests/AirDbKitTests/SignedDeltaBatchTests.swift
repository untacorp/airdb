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

final class SignedDeltaBatchTests: XCTestCase {

    func testUnsignedBatchSerialization() {
        let record = ChangeRecord(
            id: "item-101",
            collection: "supplies",
            type: .insert,
            payload: Data("{\"item\":\"bandage\",\"qty\":50}".utf8),
            hlcTimestamp: "1710928300000-0-medicNode"
        )

        let serialized = DeltaBatch.serialize([record])
        let deserialized = DeltaBatch.deserialize(serialized)

        XCTAssertEqual(deserialized.count, 1)
        let decoded = deserialized[0]
        XCTAssertEqual(decoded.id, record.id)
        XCTAssertEqual(decoded.collection, record.collection)
        XCTAssertEqual(decoded.type, record.type)
        XCTAssertEqual(decoded.hlcTimestamp, record.hlcTimestamp)
        XCTAssertEqual(decoded.payload, record.payload)
        XCTAssertFalse(decoded.isSigned)
    }

    func testSignedBatchSerializationAndVerification() throws {
        let keyPair = Ed25519Signer.generateKeyPair()

        let unsignedRecord = ChangeRecord(
            id: "patient-777",
            collection: "triage",
            type: .update,
            payload: Data("{\"status\":\"RED\",\"pulse\":120}".utf8),
            hlcTimestamp: "1710928300100-0-doctorA"
        )
        let signedRecord = try unsignedRecord.signed(with: keyPair)
        XCTAssertTrue(signedRecord.isSigned)
        XCTAssertTrue(signedRecord.verifySignature())

        let serialized = DeltaBatch.serialize([signedRecord])
        let deserialized = DeltaBatch.deserialize(serialized)

        XCTAssertEqual(deserialized.count, 1)
        let decoded = deserialized[0]
        XCTAssertTrue(decoded.isSigned)
        XCTAssertEqual(decoded.id, signedRecord.id)
        XCTAssertEqual(decoded.collection, signedRecord.collection)
        XCTAssertEqual(decoded.type, signedRecord.type)
        XCTAssertEqual(decoded.hlcTimestamp, signedRecord.hlcTimestamp)
        XCTAssertEqual(decoded.payload, signedRecord.payload)
        XCTAssertEqual(decoded.authorPublicKey, keyPair.publicKeyRaw)
        XCTAssertTrue(decoded.verifySignature())
    }

    func testTamperingInvalidatesSignature() throws {
        let keyPair = Ed25519Signer.generateKeyPair()

        let record = try ChangeRecord(
            id: "patient-888",
            collection: "triage",
            type: .update,
            payload: Data("{\"status\":\"YELLOW\"}".utf8),
            hlcTimestamp: "1710928300200-0-nurseB"
        ).signed(with: keyPair)

        XCTAssertTrue(record.verifySignature())

        // 1. Tampering with payload
        let forgedPayload = ChangeRecord(
            id: record.id,
            collection: record.collection,
            type: record.type,
            payload: Data("{\"status\":\"BLACK\"}".utf8),
            hlcTimestamp: record.hlcTimestamp,
            authorPublicKey: record.authorPublicKey,
            signature: record.signature
        )
        XCTAssertFalse(forgedPayload.verifySignature())

        // 2. Tampering with HLC timestamp
        let forgedHlc = ChangeRecord(
            id: record.id,
            collection: record.collection,
            type: record.type,
            payload: record.payload,
            hlcTimestamp: "1710928300201-0-nurseB",
            authorPublicKey: record.authorPublicKey,
            signature: record.signature
        )
        XCTAssertFalse(forgedHlc.verifySignature())
    }

    func testMixedSignedAndUnsignedBatch() throws {
        let keyPair = Ed25519Signer.generateKeyPair()

        let signed = try ChangeRecord(
            id: "sig-1",
            collection: "data",
            type: .insert,
            payload: Data("signed data".utf8),
            hlcTimestamp: "100-0-node1"
        ).signed(with: keyPair)

        let unsigned = ChangeRecord(
            id: "unsig-2",
            collection: "data",
            type: .update,
            payload: Data("unsigned data".utf8),
            hlcTimestamp: "101-0-node2"
        )

        let batch = [signed, unsigned]
        let serialized = DeltaBatch.serialize(batch)
        let deserialized = DeltaBatch.deserialize(serialized)

        XCTAssertEqual(deserialized.count, 2)
        XCTAssertTrue(deserialized[0].isSigned)
        XCTAssertTrue(deserialized[0].verifySignature())

        XCTAssertFalse(deserialized[1].isSigned)
        XCTAssertFalse(deserialized[1].verifySignature())
    }
}
