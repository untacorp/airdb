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

final class CryptoTests: XCTestCase {

    func testChaChaPolyEncryptionRoundtrip() throws {
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let plaintext = Data("AirDB Confidential Disaster Casualty Record #402".utf8)
        let aad = Data("header-metadata-aad".utf8)

        let encrypted = try ChaChaPolyCipher.encrypt(plaintext: plaintext, key: key, aad: aad)
        XCTAssertEqual(encrypted.count, 12 + plaintext.count + 16)

        let decrypted = try ChaChaPolyCipher.decrypt(encryptedPayload: encrypted, key: key, aad: aad)
        XCTAssertEqual(decrypted, plaintext)
    }

    func testChaChaPolyTamperedCiphertextFails() throws {
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let plaintext = Data("Patient triage status: YELLOW".utf8)

        let encrypted = try ChaChaPolyCipher.encrypt(plaintext: plaintext, key: key)

        var tampered = encrypted
        let targetIndex = 12 + (plaintext.count / 2)
        tampered[targetIndex] ^= 0x01

        XCTAssertThrowsError(try ChaChaPolyCipher.decrypt(encryptedPayload: tampered, key: key)) { error in
            guard case AirDbCryptoError.authenticationFailed = error else {
                XCTFail("Expected authenticationFailed error but got \(error)")
                return
            }
        }
    }

    func testChaChaPolyTamperedAadFails() throws {
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let plaintext = Data("Confidential telemetry".utf8)
        let aad = Data("sender-100".utf8)
        let alteredAad = Data("sender-999".utf8)

        let encrypted = try ChaChaPolyCipher.encrypt(plaintext: plaintext, key: key, aad: aad)

        XCTAssertThrowsError(try ChaChaPolyCipher.decrypt(encryptedPayload: encrypted, key: key, aad: alteredAad)) { error in
            guard case AirDbCryptoError.authenticationFailed = error else {
                XCTFail("Expected authenticationFailed error but got \(error)")
                return
            }
        }
    }

    func testEd25519KeyGenAndSignVerify() throws {
        let keyPair = Ed25519Signer.generateKeyPair()
        XCTAssertEqual(keyPair.privateKeyRaw.count, 32)
        XCTAssertEqual(keyPair.publicKeyRaw.count, 32)

        let message = Data("airdb:record:evacuee-101:zone=ALPHA".utf8)
        let signature = try Ed25519Signer.sign(message: message, privateKeyRaw: keyPair.privateKeyRaw)
        XCTAssertEqual(signature.count, 64)

        let isValid = Ed25519Signer.verify(message: message, signature: signature, publicKeyRaw: keyPair.publicKeyRaw)
        XCTAssertTrue(isValid)

        // Tampered message
        let tamperedMessage = Data("airdb:record:evacuee-101:zone=BRAVO".utf8)
        let isTamperedValid = Ed25519Signer.verify(message: tamperedMessage, signature: signature, publicKeyRaw: keyPair.publicKeyRaw)
        XCTAssertFalse(isTamperedValid)
    }

    func testEd25519CanonicalDigest() throws {
        let keyPair = Ed25519Signer.generateKeyPair()
        let payload = Data("{\"vitals\":{\"bp\":\"120/80\"}}".utf8)

        let digest = Ed25519Signer.canonicalChangeDigest(
            id: "pt-404",
            collection: "patients",
            typeOrdinal: 1,
            hlcTimestamp: "1710928300000-0-medic",
            payload: payload
        )

        let sig = try Ed25519Signer.sign(message: digest, privateKeyRaw: keyPair.privateKeyRaw)
        XCTAssertTrue(Ed25519Signer.verify(message: digest, signature: sig, publicKeyRaw: keyPair.publicKeyRaw))

        // Any modification to HLC invalidates signature
        let alteredDigest = Ed25519Signer.canonicalChangeDigest(
            id: "pt-404",
            collection: "patients",
            typeOrdinal: 1,
            hlcTimestamp: "1710928300001-0-medic",
            payload: payload
        )
        XCTAssertFalse(Ed25519Signer.verify(message: alteredDigest, signature: sig, publicKeyRaw: keyPair.publicKeyRaw))
    }

    func testHkdfSha256Derivation() {
        let secret = Data("cluster-passphrase-alpha".utf8)
        let salt = Data("disaster-salt-2026".utf8)

        let key1 = HkdfSha256.deriveKey(secret: secret, salt: salt)
        let key2 = HkdfSha256.deriveKey(secret: secret, salt: salt)

        XCTAssertEqual(key1.count, 32)
        XCTAssertEqual(key1, key2)

        let key3 = HkdfSha256.deriveKey(secret: secret, salt: Data("different-salt".utf8))
        XCTAssertNotEqual(key1, key3)
    }
}
