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

/// 13-byte header preceding each fragment chunk.
public struct FragmentHeader: Sendable {
    public static let headerSize = 13

    public let fragmentId: Int64
    public let index: Int
    public let total: Int
    public let originalTypeCode: UInt8

    public init(fragmentId: Int64, index: Int, total: Int, originalTypeCode: UInt8) {
        self.fragmentId = fragmentId
        self.index = index
        self.total = total
        self.originalTypeCode = originalTypeCode
    }

    public static func encode(header: FragmentHeader, chunk: Data) -> Data {
        var data = Data()
        var fId = UInt64(bitPattern: header.fragmentId).bigEndian
        var idx = UInt16(header.index).bigEndian
        var tot = UInt16(header.total).bigEndian

        data.append(contentsOf: withUnsafeBytes(of: &fId) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: &idx) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: &tot) { Array($0) })
        data.append(header.originalTypeCode)
        data.append(chunk)
        return data
    }

    public static func decode(_ data: Data) -> (FragmentHeader, Data)? {
        guard data.count >= headerSize else { return nil }

        var offset = 0
        let fIdRaw = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self).bigEndian }
        let fId = Int64(bitPattern: fIdRaw)
        offset += 8

        let idx = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
        offset += 2

        let tot = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
        offset += 2

        let origType = data[offset]
        offset += 1

        let chunk = data.subdata(in: offset..<data.count)
        return (FragmentHeader(fragmentId: fId, index: idx, total: tot, originalTypeCode: origType), chunk)
    }
}
