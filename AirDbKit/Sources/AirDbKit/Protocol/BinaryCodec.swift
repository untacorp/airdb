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
import Compression

/// Binary wire format encoder and decoder for AirDB packets matching PROTOCOL_SPEC.md.
public enum BinaryCodec {
    public static let magicByte0: UInt8 = 0x41 // 'A'
    public static let magicByte1: UInt8 = 0x44 // 'D'
    public static let minHeaderSize = 23
    public static let recipientIdSize = 8
    public static let maxPayloadSize = 65535

    /// Serializes an AirDbPacket into on-wire binary data.
    ///
    /// - Parameters:
    ///   - packet: Packet to serialize.
    ///   - clusterKey: Optional 256-bit AEAD key for encrypting with ChaCha20-Poly1305.
    ///   - autoCompressThreshold: Threshold to trigger Deflate compression.
    public static func encode(
        _ packet: AirDbPacket,
        clusterKey: Data? = nil,
        autoCompressThreshold: Int = 256
    ) -> Data {
        var flags = packet.flags
        var payloadData = packet.payload

        if payloadData.count > autoCompressThreshold && (flags & PacketFlags.compressed) == 0 {
            if let compressed = compress(payloadData), compressed.count < payloadData.count {
                payloadData = compressed
                flags |= PacketFlags.compressed
            }
        }

        if packet.recipientId != nil {
            flags |= PacketFlags.hasRecipient
        }

        if let clusterKey = clusterKey {
            flags |= PacketFlags.encrypted
            let encryptedLength = payloadData.count + ChaChaPolyCipher.nonceSize + ChaChaPolyCipher.tagSize

            var header = Data()
            header.append(magicByte0)
            header.append(magicByte1)
            header.append(packet.version)
            header.append(packet.type.rawValue)
            header.append(flags)

            var ts = UInt64(bitPattern: packet.timestamp).bigEndian
            header.append(contentsOf: withUnsafeBytes(of: &ts) { Array($0) })

            var len = UInt16(encryptedLength).bigEndian
            header.append(contentsOf: withUnsafeBytes(of: &len) { Array($0) })

            var sender = UInt64(bitPattern: packet.senderId).bigEndian
            header.append(contentsOf: withUnsafeBytes(of: &sender) { Array($0) })

            if let recipient = packet.recipientId {
                var rec = UInt64(bitPattern: recipient).bigEndian
                header.append(contentsOf: withUnsafeBytes(of: &rec) { Array($0) })
            }

            guard let encryptedPayload = try? ChaChaPolyCipher.encrypt(
                plaintext: payloadData,
                key: clusterKey,
                aad: header
            ) else {
                return Data()
            }

            var total = header
            total.append(encryptedPayload)
            return total
        } else {
            var data = Data()
            data.append(magicByte0)
            data.append(magicByte1)
            data.append(packet.version)
            data.append(packet.type.rawValue)
            data.append(flags)

            var ts = UInt64(bitPattern: packet.timestamp).bigEndian
            data.append(contentsOf: withUnsafeBytes(of: &ts) { Array($0) })

            var len = UInt16(payloadData.count).bigEndian
            data.append(contentsOf: withUnsafeBytes(of: &len) { Array($0) })

            var sender = UInt64(bitPattern: packet.senderId).bigEndian
            data.append(contentsOf: withUnsafeBytes(of: &sender) { Array($0) })

            if let recipient = packet.recipientId {
                var rec = UInt64(bitPattern: recipient).bigEndian
                data.append(contentsOf: withUnsafeBytes(of: &rec) { Array($0) })
            }

            data.append(payloadData)
            return data
        }
    }

    /// Deserializes wire data into an AirDbPacket, returning nil if data is malformed or corrupt.
    ///
    /// - Parameters:
    ///   - data: Serialized binary packet data.
    ///   - clusterKey: Optional 256-bit AEAD key for decrypting encrypted packets.
    public static func decode(_ data: Data, clusterKey: Data? = nil) -> AirDbPacket? {
        guard data.count >= minHeaderSize else { return nil }

        var offset = 0
        let m0 = data[offset]
        let m1 = data[offset + 1]
        guard m0 == magicByte0, m1 == magicByte1 else { return nil }
        offset += 2

        let version = data[offset]
        offset += 1

        guard let type = PacketType.fromCode(data[offset]) else { return nil }
        offset += 1

        let flags = data[offset]
        offset += 1

        let timestampRaw = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self).bigEndian }
        let timestamp = Int64(bitPattern: timestampRaw)
        offset += 8

        let payloadLength = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self).bigEndian })
        offset += 2

        let senderIdRaw = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self).bigEndian }
        let senderId = Int64(bitPattern: senderIdRaw)
        offset += 8

        let hasRecipient = (flags & PacketFlags.hasRecipient) != 0
        var recipientId: Int64? = nil
        if hasRecipient {
            guard offset + recipientIdSize <= data.count else { return nil }
            let recRaw = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self).bigEndian }
            recipientId = Int64(bitPattern: recRaw)
            offset += recipientIdSize
        }

        let headerSize = offset
        guard offset + payloadLength <= data.count else { return nil }
        var payload = data.subdata(in: offset..<(offset + payloadLength))

        let isEncrypted = (flags & PacketFlags.encrypted) != 0
        if isEncrypted {
            guard let clusterKey = clusterKey else { return nil }
            let aad = data.subdata(in: 0..<headerSize)
            guard let decrypted = try? ChaChaPolyCipher.decrypt(
                encryptedPayload: payload,
                key: clusterKey,
                aad: aad
            ) else {
                return nil
            }
            payload = decrypted
        }

        let isCompressed = (flags & PacketFlags.compressed) != 0
        if isCompressed {
            guard let decompressed = decompress(payload) else { return nil }
            payload = decompressed
        }

        return AirDbPacket(
            version: version,
            type: type,
            flags: flags,
            timestamp: timestamp,
            senderId: senderId,
            recipientId: recipientId,
            payload: payload
        )
    }

    // MARK: - Compression

    public static func compress(_ source: Data) -> Data? {
        guard !source.isEmpty else { return source }
        let destinationBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: source.count)
        defer { destinationBuffer.deallocate() }

        let compressedSize = source.withUnsafeBytes { (sourceBuffer: UnsafeRawBufferPointer) -> Int in
            guard let baseAddress = sourceBuffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            return compression_encode_buffer(
                destinationBuffer,
                source.count,
                baseAddress,
                source.count,
                nil,
                COMPRESSION_ZLIB
            )
        }

        guard compressedSize > 0 else { return nil }
        return Data(bytes: destinationBuffer, count: compressedSize)
    }

    public static func decompress(_ source: Data) -> Data? {
        guard !source.isEmpty else { return source }
        // Allocate generous buffer for decompressed data (up to 64KB per spec)
        let maxBufferSize = maxPayloadSize
        let destinationBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: maxBufferSize)
        defer { destinationBuffer.deallocate() }

        let decompressedSize = source.withUnsafeBytes { (sourceBuffer: UnsafeRawBufferPointer) -> Int in
            guard let baseAddress = sourceBuffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            return compression_decode_buffer(
                destinationBuffer,
                maxBufferSize,
                baseAddress,
                source.count,
                nil,
                COMPRESSION_ZLIB
            )
        }

        guard decompressedSize > 0 else { return nil }
        return Data(bytes: destinationBuffer, count: decompressedSize)
    }
}
