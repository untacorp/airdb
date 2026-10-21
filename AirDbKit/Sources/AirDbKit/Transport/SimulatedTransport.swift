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
    private var isRunning: Bool = false

    public init(nodeId: String, network: SimulatedNetwork, maxChunkSize: Int = 460) {
        self.nodeId = nodeId
        self.network = network
        self.maxChunkSize = maxChunkSize
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
        let running = lock.withScopedLock { isRunning }
        guard running else { return false }

        let fragments = PacketFragmenter.fragment(packet: packet, maxChunkSize: maxChunkSize)
        for fragment in fragments {
            network.dispatch(senderNodeId: nodeId, packet: fragment, targetPeerId: targetPeerId)
        }
        return true
    }

    public func setListener(_ listener: TransportListener) {
        lock.withScopedLock {
            self.listener = listener
        }
    }

    internal func deliver(senderNodeId: String, rawPacket: AirDbPacket) {
        let (currentListener, running) = lock.withScopedLock {
            (self.listener, self.isRunning)
        }

        guard running, let listener = currentListener else { return }

        let packet: AirDbPacket
        if rawPacket.type == .fragment {
            guard let reassembled = assemblyBuffer.ingest(packet: rawPacket) else {
                return
            }
            packet = reassembled
        } else {
            packet = rawPacket
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
