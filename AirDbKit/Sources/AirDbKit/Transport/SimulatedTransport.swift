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

/// Shared in-memory virtual radio mesh environment connecting multiple ``SimulatedTransport`` nodes.
public final class SimulatedNetwork: @unchecked Sendable {
    private let lock = NSLock()
    private var nodes: [String: SimulatedTransport] = [:]

    public init() {}

    public func register(nodeId: String, transport: SimulatedTransport) {
        lock.lock()
        nodes[nodeId] = transport
        let existingPeers = nodes.filter { $0.key != nodeId }
        lock.unlock()

        for (existingId, existingTransport) in existingPeers {
            Task {
                existingTransport.notifyPeerConnected(peerId: nodeId)
                transport.notifyPeerConnected(peerId: existingId)
            }
        }
    }

    public func unregister(nodeId: String) {
        lock.lock()
        nodes.removeValue(forKey: nodeId)
        let remainingPeers = Array(nodes.values)
        lock.unlock()

        for peer in remainingPeers {
            Task {
                peer.notifyPeerDisconnected(peerId: nodeId)
            }
        }
    }

    public func dispatch(senderNodeId: String, packet: AirDbPacket, targetPeerId: String?) {
        lock.lock()
        let recipients: [SimulatedTransport]
        if let targetPeerId = targetPeerId {
            recipients = nodes[targetPeerId].map { [$0] } ?? []
        } else {
            recipients = nodes.filter { $0.key != senderNodeId }.map(\.value)
        }
        lock.unlock()

        for recipient in recipients {
            Task {
                recipient.deliver(senderNodeId: senderNodeId, rawPacket: packet)
            }
        }
    }
}

/// Sans-I/O simulated peer-to-peer transport implementing ``AirDbTransport``.
/// Accurately models MTU-based fragmentation, packet reassembly, and link events.
public final class SimulatedTransport: AirDbTransport, @unchecked Sendable {
    public let nodeId: String
    private let network: SimulatedNetwork
    private let maxChunkSize: Int
    private let lock = NSLock()

    private weak var listener: TransportListener?
    private let assemblyBuffer = AssemblyBuffer()
    private var clusterKey: Data?
    private var isRunning: Bool = false

    /// Initializes a simulated in-memory BLE transport node.
    ///
    /// - Parameters:
    ///   - nodeId: Unique identifier for this local peer node.
    ///   - network: Shared simulated network mesh coordinating peer delivery.
    ///   - maxChunkSize: Maximum chunk size in bytes for packet fragmentation.
    ///   - clusterKey: Optional 256-bit symmetric encryption key for ChaCha20-Poly1305 AEAD framing.
    public init(nodeId: String, network: SimulatedNetwork, maxChunkSize: Int = 460, clusterKey: Data? = nil) {
        self.nodeId = nodeId
        self.network = network
        self.maxChunkSize = maxChunkSize
        self.clusterKey = clusterKey
    }

    /// Sets or updates the cluster encryption key for wire framing on this simulated transport.
    ///
    /// - Parameter key: 256-bit symmetric cluster key, or nil for unencrypted packets.
    public func setClusterKey(_ key: Data?) {
        lock.withScopedLock {
            self.clusterKey = key
        }
    }

    public func start() {
        lock.withScopedLock {
            isRunning = true
        }
        network.register(nodeId: nodeId, transport: self)
    }

    public func stop() {
        lock.withScopedLock {
            isRunning = false
        }
        network.unregister(nodeId: nodeId)
    }

    public func send(packet: AirDbPacket, targetPeerId: String?) async -> Bool {
        let (running, key) = lock.withScopedLock { (isRunning, clusterKey) }
        guard running else { return false }

        let fragments = PacketFragmenter.fragment(packet: packet, clusterKey: key, maxChunkSize: maxChunkSize)
        for fragment in fragments {
            let wirePacket: AirDbPacket
            if let key = key, fragment.type != .fragment {
                let encoded = BinaryCodec.encode(fragment, clusterKey: key)
                let headerSize = BinaryCodec.minHeaderSize + (fragment.recipientId != nil ? BinaryCodec.recipientIdSize : 0)
                let ciphertext = encoded.subdata(in: headerSize..<encoded.count)
                wirePacket = AirDbPacket(
                    version: fragment.version,
                    type: fragment.type,
                    flags: fragment.flags | PacketFlags.encrypted,
                    timestamp: fragment.timestamp,
                    senderId: fragment.senderId,
                    recipientId: fragment.recipientId,
                    payload: ciphertext
                )
            } else {
                wirePacket = fragment
            }
            network.dispatch(senderNodeId: nodeId, packet: wirePacket, targetPeerId: targetPeerId)
        }
        return true
    }

    public func setListener(_ listener: TransportListener) {
        lock.withScopedLock {
            self.listener = listener
        }
    }

    internal func deliver(senderNodeId: String, rawPacket: AirDbPacket) {
        let (currentListener, running, key) = lock.withScopedLock {
            (self.listener, self.isRunning, self.clusterKey)
        }

        guard running, let listener = currentListener else { return }

        let packet: AirDbPacket
        if rawPacket.type == .fragment {
            guard let reassembled = assemblyBuffer.ingest(rawPacket, clusterKey: key) else {
                return
            }
            packet = reassembled
        } else if rawPacket.isEncrypted {
            guard let key = key else { return }
            var fullData = Data()
            fullData.append(BinaryCodec.magicByte0)
            fullData.append(BinaryCodec.magicByte1)
            fullData.append(rawPacket.version)
            fullData.append(rawPacket.type.rawValue)
            fullData.append(rawPacket.flags)
            var ts = rawPacket.timestamp.bigEndian
            fullData.append(Data(bytes: &ts, count: 8))
            var pLen = UInt16(rawPacket.payload.count).bigEndian
            fullData.append(Data(bytes: &pLen, count: 2))
            var sId = rawPacket.senderId.bigEndian
            fullData.append(Data(bytes: &sId, count: 8))
            if let rId = rawPacket.recipientId {
                var rIdBe = rId.bigEndian
                fullData.append(Data(bytes: &rIdBe, count: 8))
            }
            fullData.append(rawPacket.payload)
            guard let decrypted = BinaryCodec.decode(fullData, clusterKey: key) else {
                return
            }
            packet = decrypted
        } else {
            packet = rawPacket
        }

        if key != nil && !packet.isEncrypted {
            return
        }

        listener.onTransportEvent(
            .packetReceived(
                packet: packet,
                peerId: senderNodeId,
                linkId: "sim-link-\(senderNodeId)"
            )
        )
    }

    internal func notifyPeerConnected(peerId: String) {
        let (currentListener, running) = lock.withScopedLock {
            (self.listener, self.isRunning)
        }

        guard running, let listener = currentListener else { return }
        listener.onTransportEvent(.peerDiscovered(peerId: peerId, rssi: -45))
        listener.onTransportEvent(.linkConnected(peerId: peerId, linkId: "sim-link-\(peerId)"))
    }

    internal func notifyPeerDisconnected(peerId: String) {
        let (currentListener, running) = lock.withScopedLock {
            (self.listener, self.isRunning)
        }

        guard running, let listener = currentListener else { return }
        listener.onTransportEvent(.linkDisconnected(peerId: peerId, linkId: "sim-link-\(peerId)"))
    }
}
