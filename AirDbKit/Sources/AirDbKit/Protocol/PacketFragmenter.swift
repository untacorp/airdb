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

/// Partitions oversized packets into MTU-safe fragments.
public enum PacketFragmenter {
    public static let defaultMaxChunkSize = 460

    public static func fragment(
        packet: AirDbPacket,
        maxChunkSize: Int = defaultMaxChunkSize
    ) -> [AirDbPacket] {
        let encodedBytes = BinaryCodec.encode(packet)
        if encodedBytes.count <= maxChunkSize {
            return [packet]
        }

        let fragmentId = Int64.random(in: Int64.min...Int64.max)
        let total = (encodedBytes.count + maxChunkSize - 1) / maxChunkSize
        var fragments: [AirDbPacket] = []
        fragments.reserveCapacity(total)

        for i in 0..<total {
            let start = i * maxChunkSize
            let end = min(start + maxChunkSize, encodedBytes.count)
            let chunk = encodedBytes.subdata(in: start..<end)

            let header = FragmentHeader(
                fragmentId: fragmentId,
                index: i,
                total: total,
                originalTypeCode: packet.type.rawValue
            )

            let payload = FragmentHeader.encode(header: header, chunk: chunk)

            fragments.append(
                AirDbPacket(
                    version: packet.version,
                    type: .fragment,
                    flags: 0,
                    timestamp: packet.timestamp,
                    senderId: packet.senderId,
                    recipientId: packet.recipientId,
                    payload: payload
                )
            )
        }

        return fragments
    }
}
