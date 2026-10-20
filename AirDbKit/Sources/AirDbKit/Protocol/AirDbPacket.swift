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

/// AirDB wire protocol packet types.
public enum PacketType: UInt8, Sendable {
    case handshake = 0x01
    case handshakeAck = 0x02
    case reconcileReq = 0x03
    case reconcileResp = 0x04
    case deltaBatch = 0x05
    case deltaAck = 0x06
    case fragment = 0x0F

    public static func fromCode(_ code: UInt8) -> PacketType? {
        PacketType(rawValue: code)
    }
}

/// Bitmask flags for AirDB packets.
public struct PacketFlags {
    public static let compressed: UInt8 = 0x01
    public static let hasRecipient: UInt8 = 0x02
}

/// Fixed framing envelope for AirDB packets.
public struct AirDbPacket: Sendable {
    public let version: UInt8
    public let type: PacketType
    public let flags: UInt8
    public let timestamp: Int64
    public let senderId: Int64
    public let recipientId: Int64?
    public let payload: Data

    public init(
        version: UInt8 = 1,
        type: PacketType,
        flags: UInt8 = 0,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        senderId: Int64,
        recipientId: Int64? = nil,
        payload: Data
    ) {
        self.version = version
        self.type = type
        self.flags = flags
        self.timestamp = timestamp
        self.senderId = senderId
        self.recipientId = recipientId
        self.payload = payload
    }

    public var isCompressed: Bool {
        (flags & PacketFlags.compressed) != 0
    }

    public var hasRecipient: Bool {
        recipientId != nil
    }
}
