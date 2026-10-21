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

/// HMAC-based Extract-and-Expand Key Derivation Function (HKDF) using SHA-256 via Apple CryptoKit.
public enum HkdfSha256 {

    /// Derives a cryptographic key from user/cluster secret material per RFC 5869.
    ///
    /// - Parameters:
    ///   - secret: Initial secret material (e.g. passphrase, shared secret).
    ///   - salt: Optional salt value (random 16-32 bytes recommended).
    ///   - info: Context and application specific information string.
    ///   - outputByteCount: Desired output key length in bytes (default 32 for ChaCha20-Poly1305).
    /// - Returns: Derived key data.
    public static func deriveKey(
        secret: Data,
        salt: Data? = nil,
        info: Data? = "airdb-mesh-key-v1".data(using: .utf8),
        outputByteCount: Int = 32
    ) -> Data {
        let inputKey = SymmetricKey(data: secret)
        let derivedKey: SymmetricKey
        if let salt = salt, !salt.isEmpty {
            derivedKey = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: inputKey,
                salt: salt,
                info: info ?? Data(),
                outputByteCount: outputByteCount
            )
        } else {
            derivedKey = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: inputKey,
                info: info ?? Data(),
                outputByteCount: outputByteCount
            )
        }
        return derivedKey.withUnsafeBytes { Data($0) }
    }
}
