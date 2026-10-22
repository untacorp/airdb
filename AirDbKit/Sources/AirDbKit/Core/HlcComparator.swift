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

/// Canonical total ordering comparator for Hybrid Logical Clock (HLC) timestamp strings.
///
/// Compares timestamps according to the strict mathematical HLC total order:
/// 1. Physical time (Int64, epoch milliseconds)
/// 2. Logical counter (Int, within identical millisecond)
/// 3. Node ID (String, deterministic lexicographical tie-breaker)
public enum HlcComparator: Sendable {

    /// Compares two HLC timestamp strings.
    ///
    /// - Returns: `.orderedAscending` if `a < b`, `.orderedDescending` if `a > b`, `.orderedSame` if identical.
    public static func compare(_ a: String?, _ b: String?) -> ComparisonResult {
        if a == b { return .orderedSame }
        guard let a = a else { return .orderedAscending }
        guard let b = b else { return .orderedDescending }

        let parsedA = parseHlcComponents(a)
        let parsedB = parseHlcComponents(b)

        if let (ptA, lcA, nodeA) = parsedA, let (ptB, lcB, nodeB) = parsedB {
            if ptA != ptB {
                return ptA < ptB ? .orderedAscending : .orderedDescending
            }
            if lcA != lcB {
                return lcA < lcB ? .orderedAscending : .orderedDescending
            }
            if nodeA != nodeB {
                return nodeA < nodeB ? .orderedAscending : .orderedDescending
            }
            return .orderedSame
        }

        if parsedA != nil { return .orderedDescending }
        if parsedB != nil { return .orderedAscending }
        return a.compare(b)
    }

    /// Extracts the originating node identifier from an HLC timestamp string.
    public static func extractNodeId(from hlcTimestamp: String) -> String? {
        guard let firstDash = hlcTimestamp.firstIndex(of: "-") else { return nil }
        let afterFirst = hlcTimestamp.index(after: firstDash)
        guard let secondDash = hlcTimestamp[afterFirst...].firstIndex(of: "-") else { return nil }
        let afterSecond = hlcTimestamp.index(after: secondDash)
        guard afterSecond < hlcTimestamp.endIndex else { return nil }
        return String(hlcTimestamp[afterSecond...])
    }

    /// Decomposes a canonical HLC timestamp string into its constituent components:
    /// physical time (epoch milliseconds), logical counter, and originating node identifier.
    ///
    /// - Parameter str: Canonical HLC string in `<physicalTime>-<logicalCounter>-<nodeId>` format.
    /// - Returns: A tuple of `(pt, lc, node)` if the string is well-formed, or `nil` if malformed.
    public static func parseHlcComponents(_ str: String) -> (pt: Int64, lc: Int, node: String)? {
        guard let firstDash = str.firstIndex(of: "-") else { return nil }
        let ptSub = str[..<firstDash]
        guard let pt = Int64(ptSub) else { return nil }

        let afterFirst = str.index(after: firstDash)
        guard let secondDash = str[afterFirst...].firstIndex(of: "-") else { return nil }
        let lcSub = str[afterFirst..<secondDash]
        guard let lc = Int(lcSub) else { return nil }

        let afterSecond = str.index(after: secondDash)
        guard afterSecond <= str.endIndex else { return nil }
        let node = String(str[afterSecond...])
        return (pt, lc, node)
    }
}
