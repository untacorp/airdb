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

/// Multi-node causal state tracker mapping node IDs to their latest known HLC timestamps.
public final class VectorClock: @unchecked Sendable {
    private let lock = NSLock()
    private var clocks: [String: String]

    public init(initialState: [String: String] = [:]) {
        self.clocks = initialState
    }

    public func get(_ nodeId: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return clocks[nodeId]
    }

    public func update(nodeId: String, hlcTimestamp: String) {
        lock.lock()
        defer { lock.unlock() }
        if let current = clocks[nodeId] {
            if hlcTimestamp > current {
                clocks[nodeId] = hlcTimestamp
            }
        } else {
            clocks[nodeId] = hlcTimestamp
        }
    }

    public func merge(_ other: VectorClock) {
        let otherMap = other.asMap()
        lock.lock()
        defer { lock.unlock() }
        for (nodeId, ts) in otherMap {
            if let current = clocks[nodeId] {
                if ts > current {
                    clocks[nodeId] = ts
                }
            } else {
                clocks[nodeId] = ts
            }
        }
    }

    public func asMap() -> [String: String] {
        lock.lock()
        defer { lock.unlock() }
        return clocks
    }

    /// Serializes this vector clock into binary form matching AirDB wire specification.
    public func toBinary() -> Data {
        lock.lock()
        let entries = Array(clocks)
        lock.unlock()

        var data = Data()
        var count = UInt16(entries.count).bigEndian
        data.append(contentsOf: withUnsafeBytes(of: &count) { Array($0) })

        for (k, v) in entries {
            let kData = Data(k.utf8)
            let vData = Data(v.utf8)

            var kLen = UInt16(kData.count).bigEndian
            var vLen = UInt16(vData.count).bigEndian

            data.append(contentsOf: withUnsafeBytes(of: &kLen) { Array($0) })
            data.append(kData)
            data.append(contentsOf: withUnsafeBytes(of: &vLen) { Array($0) })
            data.append(vData)
        }
        return data
    }

    /// Deserializes a vector clock from binary wire representation.
    public static func fromBinary(_ data: Data) -> VectorClock {
        guard data.count >= 2 else { return VectorClock() }

        var offset = 0
        let count = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
        offset += 2

        var result: [String: String] = [:]

        for _ in 0..<count {
            guard offset + 2 <= data.count else { break }
            let kLen = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
            offset += 2

            guard offset + kLen <= data.count else { break }
            guard let key = String(data: data.subdata(in: offset..<(offset + kLen)), encoding: .utf8) else { break }
            offset += kLen

            guard offset + 2 <= data.count else { break }
            let vLen = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
            offset += 2

            guard offset + vLen <= data.count else { break }
            guard let val = String(data: data.subdata(in: offset..<(offset + vLen)), encoding: .utf8) else { break }
            offset += vLen

            result[key] = val
        }

        return VectorClock(initialState: result)
    }
}
