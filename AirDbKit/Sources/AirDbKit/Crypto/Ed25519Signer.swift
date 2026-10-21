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

import Foundation
import CryptoKit

/// Raw 32-byte Ed25519 key pair container.
public struct Ed25519KeyPair: Sendable, Hashable {
    public let privateKeyRaw: Data
    public let publicKeyRaw: Data

    public init(privateKeyRaw: Data, publicKeyRaw: Data) {
        precondition(privateKeyRaw.count == 32, "Private key must be 32 bytes")
        precondition(publicKeyRaw.count == 32, "Public key must be 32 bytes")
        self.privateKeyRaw = privateKeyRaw
        self.publicKeyRaw = publicKeyRaw
    }
}

/// Ed25519 asymmetric digital signature utility per RFC 8032 using Apple CryptoKit.
public enum Ed25519Signer {

    public static let privateKeySize = 32
    public static let publicKeySize = 32
    public static let signatureSize = 64

    /// Generates a fresh random Ed25519 key pair.
    public static func generateKeyPair() -> Ed25519KeyPair {
        let privateKey = Curve25519.Signing.PrivateKey()
        return Ed25519KeyPair(
            privateKeyRaw: privateKey.rawRepresentation,
            publicKeyRaw: privateKey.publicKey.rawRepresentation
        )
    }

    /// Signs a message with a 32-byte Ed25519 private key.
    ///
    /// - Parameters:
    ///   - message: Canonical message bytes to sign.
    ///   - privateKeyRaw: 32-byte raw private key.
    /// - Returns: 64-byte Ed25519 signature.
    public static func sign(message: Data, privateKeyRaw: Data) throws -> Data {
        guard privateKeyRaw.count == privateKeySize else {
            throw AirDbCryptoError.invalidPrivateKeyLength(expected: privateKeySize, actual: privateKeyRaw.count)
        }

        do {
            let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyRaw)
            return try privateKey.signature(for: message)
        } catch {
            throw AirDbCryptoError.operationFailed("Failed to sign message: \(error.localizedDescription)")
        }
    }

    /// Verifies an Ed25519 signature against the message and author's 32-byte raw public key.
    ///
    /// - Parameters:
    ///   - message: Canonical message bytes that were signed.
    ///   - signature: 64-byte signature to verify.
    ///   - publicKeyRaw: 32-byte author public key.
    /// - Returns: true if signature is valid; false otherwise.
    public static func verify(message: Data, signature: Data, publicKeyRaw: Data) -> Bool {
        guard signature.count == signatureSize, publicKeyRaw.count == publicKeySize else {
            return false
        }

        do {
            let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: publicKeyRaw)
            return publicKey.isValidSignature(signature, for: message)
        } catch {
            return false
        }
    }

    /// Creates a canonical byte representation of a database change for signing and verification.
    public static func canonicalChangeDigest(
        id: String,
        collection: String,
        typeOrdinal: UInt8,
        hlcTimestamp: String,
        payload: Data
    ) -> Data {
        var data = Data()
        data.append(contentsOf: "airdb:v1:change\n".utf8)
        data.append(contentsOf: id.utf8)
        data.append(0x00)
        data.append(contentsOf: collection.utf8)
        data.append(0x00)
        data.append(typeOrdinal)
        data.append(0x00)
        data.append(contentsOf: hlcTimestamp.utf8)
        data.append(0x00)
        data.append(payload)
        return data
    }
}
