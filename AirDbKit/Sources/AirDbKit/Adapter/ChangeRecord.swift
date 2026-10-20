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

/// Type of database mutation.
public enum MutationType: UInt8, Sendable, Codable {
    case insert = 0
    case update = 1
    case delete = 2
}

/// Unit of database mutation replicated between nodes.
public struct ChangeRecord: Sendable, Hashable, CustomStringConvertible {
    public let id: String
    public let collection: String
    public let type: MutationType
    public let payload: Data
    public let hlcTimestamp: String

    public init(id: String, collection: String, type: MutationType, payload: Data, hlcTimestamp: String) {
        self.id = id
        self.collection = collection
        self.type = type
        self.payload = payload
        self.hlcTimestamp = hlcTimestamp
    }

    public var description: String {
        "ChangeRecord(id: '\(id)', collection: '\(collection)', type: \(type), payloadBytes: \(payload.count), hlc: '\(hlcTimestamp)')"
    }
}
