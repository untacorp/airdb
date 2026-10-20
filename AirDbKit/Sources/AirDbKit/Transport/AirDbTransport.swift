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

/// Events emitted by the transport layer up to the AirDB synchronization engine.
public enum TransportEvent: Sendable {
    /// A remote peer was discovered advertising in radio proximity.
    case peerDiscovered(peerId: String, rssi: Int)

    /// A physical link was established with a remote peer.
    case linkConnected(peerId: String, linkId: String)

    /// An existing physical link was terminated.
    case linkDisconnected(peerId: String, linkId: String)

    /// An inbound packet was successfully received and decoded.
    case packetReceived(packet: AirDbPacket, peerId: String, linkId: String)

    /// The transport buffer has space to accept more writes.
    case linkWritable(linkId: String)
}

/// Callback listener invoked by transport implementations upon receiving events.
public protocol TransportListener: AnyObject, Sendable {
    func onTransportEvent(_ event: TransportEvent)
}

/// Universal peer-to-peer transport abstraction contract for AirDB.
public protocol AirDbTransport: AnyObject, Sendable {
    /// Start advertising and scanning for peers.
    func start()

    /// Stop radio operations and close active links.
    func stop()

    /// Enqueue a packet for transmission.
    ///
    /// - Parameters:
    ///   - packet: The packet to send.
    ///   - targetPeerId: The destination peer ID, or nil to broadcast to all connected peers.
    /// - Returns: True if accepted into the transmit buffer; false if backpressure rejected it.
    func send(packet: AirDbPacket, targetPeerId: String?) async -> Bool

    /// Set event listener for inbound packets and link state updates.
    func setListener(_ listener: TransportListener)
}
