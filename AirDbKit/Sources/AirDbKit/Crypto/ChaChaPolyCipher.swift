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

/// ChaCha20-Poly1305 Authenticated Encryption with Associated Data (AEAD) using Apple CryptoKit.
///
/// Output format conforms to RFC 8439:
/// [Nonce (12 bytes)] + [Ciphertext (N bytes)] + [Poly1305 Tag (16 bytes)]
public enum ChaChaPolyCipher {

    public static let nonceSize = 12
    public static let tagSize = 16
    public static let keySize = 32

    /// Encrypts plaintext using ChaCha20-Poly1305 AEAD.
    ///
    /// - Parameters:
    ///   - plaintext: Data to encrypt.
    ///   - key: 256-bit (32-byte) symmetric encryption key.
    ///   - aad: Optional Authenticated Additional Data (e.g. packet header).
    ///   - nonce: Optional 12-byte initialization vector. If nil, a random nonce is generated.
    /// - Returns: Combined Data containing [Nonce (12B)] + [Ciphertext] + [Tag (16B)].
    public static func encrypt(
        plaintext: Data,
        key: Data,
        aad: Data? = nil,
        nonce: Data? = nil
    ) throws -> Data {
        guard key.count == keySize else {
            throw AirDbCryptoError.invalidKeyLength(expected: keySize, actual: key.count)
        }

        let symmetricKey = SymmetricKey(data: key)
        let chachaNonce: ChaChaPoly.Nonce
        if let nonce = nonce {
            guard nonce.count == nonceSize else {
                throw AirDbCryptoError.invalidNonceLength(expected: nonceSize, actual: nonce.count)
            }
            chachaNonce = try ChaChaPoly.Nonce(data: nonce)
        } else {
            chachaNonce = ChaChaPoly.Nonce()
        }

        do {
            let sealedBox: ChaChaPoly.SealedBox
            if let aad = aad, !aad.isEmpty {
                sealedBox = try ChaChaPoly.seal(plaintext, using: symmetricKey, nonce: chachaNonce, authenticating: aad)
            } else {
                sealedBox = try ChaChaPoly.seal(plaintext, using: symmetricKey, nonce: chachaNonce)
            }
            return sealedBox.combined
        } catch {
            throw AirDbCryptoError.operationFailed("ChaChaPoly encryption failed: \(error.localizedDescription)")
        }
    }

    /// Decrypts and authenticates a ChaCha20-Poly1305 payload.
    ///
    /// - Parameters:
    ///   - encryptedPayload: Data containing [Nonce (12B)] + [Ciphertext] + [Tag (16B)].
    ///   - key: 256-bit (32-byte) symmetric key.
    ///   - aad: Optional Authenticated Additional Data.
    /// - Returns: Decrypted plaintext Data.
    public static func decrypt(
        encryptedPayload: Data,
        key: Data,
        aad: Data? = nil
    ) throws -> Data {
        guard key.count == keySize else {
            throw AirDbCryptoError.invalidKeyLength(expected: keySize, actual: key.count)
        }
        guard encryptedPayload.count >= nonceSize + tagSize else {
            throw AirDbCryptoError.payloadTooShort(actual: encryptedPayload.count, minimum: nonceSize + tagSize)
        }

        let symmetricKey = SymmetricKey(data: key)

        do {
            let box = try ChaChaPoly.SealedBox(combined: encryptedPayload)
            if let aad = aad, !aad.isEmpty {
                return try ChaChaPoly.open(box, using: symmetricKey, authenticating: aad)
            } else {
                return try ChaChaPoly.open(box, using: symmetricKey)
            }
        } catch {
            throw AirDbCryptoError.authenticationFailed
        }
    }
}
