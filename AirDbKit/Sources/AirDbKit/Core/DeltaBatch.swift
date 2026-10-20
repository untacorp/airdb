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

/// Binary serialization utility for batches of ChangeRecord mutations.
public enum DeltaBatch {

    public static func serialize(_ changes: [ChangeRecord]) -> Data {
        var data = Data()
        var count = UInt16(changes.count).bigEndian
        data.append(contentsOf: withUnsafeBytes(of: &count) { Array($0) })

        for change in changes {
            let idData = Data(change.id.utf8)
            let collData = Data(change.collection.utf8)
            let hlcData = Data(change.hlcTimestamp.utf8)

            var idLen = UInt16(idData.count).bigEndian
            var collLen = UInt16(collData.count).bigEndian
            var hlcLen = UInt16(hlcData.count).bigEndian
            var payloadLen = Int32(change.payload.count).bigEndian

            data.append(contentsOf: withUnsafeBytes(of: &idLen) { Array($0) })
            data.append(idData)

            data.append(contentsOf: withUnsafeBytes(of: &collLen) { Array($0) })
            data.append(collData)

            data.append(change.type.rawValue)

            data.append(contentsOf: withUnsafeBytes(of: &hlcLen) { Array($0) })
            data.append(hlcData)

            data.append(contentsOf: withUnsafeBytes(of: &payloadLen) { Array($0) })
            data.append(change.payload)
        }

        return data
    }

    public static func deserialize(_ data: Data) -> [ChangeRecord] {
        guard data.count >= 2 else { return [] }

        var offset = 0
        let count = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
        offset += 2

        var changes: [ChangeRecord] = []
        changes.reserveCapacity(count)

        for _ in 0..<count {
            guard offset + 2 <= data.count else { break }
            let idLen = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
            offset += 2

            guard offset + idLen <= data.count else { break }
            guard let id = String(data: data.subdata(in: offset..<(offset + idLen)), encoding: .utf8) else { break }
            offset += idLen

            guard offset + 2 <= data.count else { break }
            let collLen = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
            offset += 2

            guard offset + collLen <= data.count else { break }
            guard let coll = String(data: data.subdata(in: offset..<(offset + collLen)), encoding: .utf8) else { break }
            offset += collLen

            guard offset + 1 <= data.count else { break }
            let typeRaw = data[offset]
            let type = MutationType(rawValue: typeRaw) ?? .update
            offset += 1

            guard offset + 2 <= data.count else { break }
            let hlcLen = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
            offset += 2

            guard offset + hlcLen <= data.count else { break }
            guard let hlc = String(data: data.subdata(in: offset..<(offset + hlcLen)), encoding: .utf8) else { break }
            offset += hlcLen

            guard offset + 4 <= data.count else { break }
            let payloadLen = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: Int32.self).bigEndian })
            offset += 4

            guard payloadLen >= 0, offset + payloadLen <= data.count else { break }
            let payload = data.subdata(in: offset..<(offset + payloadLen))
            offset += payloadLen

            changes.append(
                ChangeRecord(
                    id: id,
                    collection: coll,
                    type: type,
                    payload: payload,
                    hlcTimestamp: hlc
                )
            )
        }

        return changes
    }
}
