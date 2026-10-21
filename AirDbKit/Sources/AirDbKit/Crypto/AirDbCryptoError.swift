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

/// Errors thrown by AirDbKit cryptographic operations.
public enum AirDbCryptoError: Error, LocalizedError, Sendable {
    case invalidKeyLength(expected: Int, actual: Int)
    case invalidNonceLength(expected: Int, actual: Int)
    case payloadTooShort(actual: Int, minimum: Int)
    case authenticationFailed
    case invalidSignatureLength(expected: Int, actual: Int)
    case invalidPublicKeyLength(expected: Int, actual: Int)
    case invalidPrivateKeyLength(expected: Int, actual: Int)
    case operationFailed(String)

    public var errorDescription: String? {
        switch self {
        case .invalidKeyLength(let expected, let actual):
            return "Invalid key length: expected \(expected) bytes, got \(actual)"
        case .invalidNonceLength(let expected, let actual):
            return "Invalid nonce length: expected \(expected) bytes, got \(actual)"
        case .payloadTooShort(let actual, let minimum):
            return "Payload too short: got \(actual) bytes, minimum is \(minimum)"
        case .authenticationFailed:
            return "Authentication tag verification failed. Data corrupted or tampered."
        case .invalidSignatureLength(let expected, let actual):
            return "Invalid signature length: expected \(expected) bytes, got \(actual)"
        case .invalidPublicKeyLength(let expected, let actual):
            return "Invalid public key length: expected \(expected) bytes, got \(actual)"
        case .invalidPrivateKeyLength(let expected, let actual):
            return "Invalid private key length: expected \(expected) bytes, got \(actual)"
        case .operationFailed(let reason):
            return "Cryptographic operation failed: \(reason)"
        }
    }
}
