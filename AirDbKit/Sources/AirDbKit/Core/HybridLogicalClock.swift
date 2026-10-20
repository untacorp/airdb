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

/// Monotonically increasing Hybrid Logical Clock (HLC) timestamp.
///
/// Combines physical epoch milliseconds, an incrementing logical counter, and a node identifier.
/// Guarantees strict causality and total ordering across peers without NTP synchronization.
public struct HlcTimestamp: Comparable, Sendable, CustomStringConvertible, Hashable {
    public let physicalTime: Int64
    public let logicalCounter: Int
    public let nodeId: String

    public init(physicalTime: Int64, logicalCounter: Int, nodeId: String) {
        self.physicalTime = physicalTime
        self.logicalCounter = logicalCounter
        self.nodeId = nodeId
    }

    public static func < (lhs: HlcTimestamp, rhs: HlcTimestamp) -> Bool {
        if lhs.physicalTime != rhs.physicalTime {
            return lhs.physicalTime < rhs.physicalTime
        }
        if lhs.logicalCounter != rhs.logicalCounter {
            return lhs.logicalCounter < rhs.logicalCounter
        }
        return lhs.nodeId < rhs.nodeId
    }

    public var description: String {
        "\(physicalTime)-\(logicalCounter)-\(nodeId)"
    }

    public static func parse(_ string: String) -> HlcTimestamp? {
        let parts = string.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count >= 3,
              let physical = Int64(parts[0]),
              let logical = Int(parts[1]) else {
            return nil
        }
        let node = parts[2...].joined(separator: "-")
        return HlcTimestamp(physicalTime: physical, logicalCounter: logical, nodeId: node)
    }

    public static let zero = HlcTimestamp(physicalTime: 0, logicalCounter: 0, nodeId: "")
}

/// Thread-safe Hybrid Logical Clock generator.
public final class HybridLogicalClock: @unchecked Sendable {
    public let nodeId: String
    private let timeProvider: @Sendable () -> Int64
    private let lock = NSLock()
    private var latest: HlcTimestamp

    public init(nodeId: String, timeProvider: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.nodeId = nodeId
        self.timeProvider = timeProvider
        self.latest = HlcTimestamp(physicalTime: 0, logicalCounter: 0, nodeId: nodeId)
    }

    /// Generates a new monotonically increasing timestamp for a local mutation.
    public func now() -> HlcTimestamp {
        lock.lock()
        defer { lock.unlock() }

        let pt = timeProvider()
        let nextPt = max(latest.physicalTime, pt)
        let nextLogical = (nextPt == latest.physicalTime) ? latest.logicalCounter + 1 : 0

        latest = HlcTimestamp(physicalTime: nextPt, logicalCounter: nextLogical, nodeId: nodeId)
        return latest
    }

    /// Advances the local clock upon observing an incoming remote HLC timestamp.
    public func update(remote: HlcTimestamp) -> HlcTimestamp {
        lock.lock()
        defer { lock.unlock() }

        let pt = timeProvider()
        let nextPt = max(max(latest.physicalTime, remote.physicalTime), pt)

        let nextLogical: Int
        if nextPt == latest.physicalTime && nextPt == remote.physicalTime {
            nextLogical = max(latest.logicalCounter, remote.logicalCounter) + 1
        } else if nextPt == latest.physicalTime {
            nextLogical = latest.logicalCounter + 1
        } else if nextPt == remote.physicalTime {
            nextLogical = remote.logicalCounter + 1
        } else {
            nextLogical = 0
        }

        latest = HlcTimestamp(physicalTime: nextPt, logicalCounter: nextLogical, nodeId: nodeId)
        return latest
    }

    public func current() -> HlcTimestamp {
        lock.lock()
        defer { lock.unlock() }
        return latest
    }
}
