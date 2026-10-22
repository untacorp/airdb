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

#if canImport(CoreBluetooth)
@preconcurrency import CoreBluetooth
import Foundation

/// Configuration parameters for the AirDB Bluetooth Low Energy transport layer.
public struct BleConfig: @unchecked Sendable {
    public let serviceUuid: CBUUID
    public let characteristicUuid: CBUUID
    public let cccdUuid: CBUUID
    public let targetMtu: Int
    public let maxFragmentChunkSize: Int
    public let maxPendingQueueSize: Int
    public let maxPendingBytes: Int
    public let sendRetryDelayMs: UInt64
    public let maxCallbackRetries: Int

    public init(
        serviceUuid: CBUUID = CBUUID(string: "41697244-4200-0000-8000-00805F9B34FB"),
        characteristicUuid: CBUUID = CBUUID(string: "41697244-4201-0000-8000-00805F9B34FB"),
        cccdUuid: CBUUID = CBUUID(string: "00002902-0000-1000-8000-00805F9B34FB"),
        targetMtu: Int = 517,
        maxFragmentChunkSize: Int = 460,
        maxPendingQueueSize: Int = 256,
        maxPendingBytes: Int = 1_048_576,
        sendRetryDelayMs: UInt64 = 15,
        maxCallbackRetries: Int = 3
    ) {
        self.serviceUuid = serviceUuid
        self.characteristicUuid = characteristicUuid
        self.cccdUuid = cccdUuid
        self.targetMtu = targetMtu
        self.maxFragmentChunkSize = maxFragmentChunkSize
        self.maxPendingQueueSize = maxPendingQueueSize
        self.maxPendingBytes = maxPendingBytes
        self.sendRetryDelayMs = sendRetryDelayMs
        self.maxCallbackRetries = maxCallbackRetries
    }
}

/// Production Bluetooth Low Energy transport running simultaneously as Central and Peripheral.
///
/// Features:
/// - Bidirectional single-characteristic GATT topology (WriteWithoutResponse + Notify)
/// - Backpressure drain queue handling `peripheralIsReady(toSendWriteWithoutResponse:)` and `peripheralManagerIsReady(toUpdateSubscribers:)`
/// - Automatic fragmentation and reassembly via ``PacketFragmenter`` and ``AssemblyBuffer``
public final class CoreBluetoothTransport: NSObject, AirDbTransport, @unchecked Sendable {
    public let config: BleConfig
    private let queue = DispatchQueue(label: "io.airdb.transport.ble", qos: .userInitiated)

    private weak var listener: TransportListener?
    private var isRunning: Bool = false

    // Central role
    private var centralManager: CBCentralManager?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    private var activePeripherals: [UUID: (peripheral: CBPeripheral, characteristic: CBCharacteristic)] = [:]
    private var pendingCentralWrites: [UUID: [Data]] = [:]

    // Peripheral role
    private var peripheralManager: CBPeripheralManager?
    private var localCharacteristic: CBMutableCharacteristic?
    private var subscribedCentrals: [UUID: CBCentral] = [:]
    private var pendingSubscriberNotifications: [Data] = []

    // Reassembly buffer per link
    private var assemblyBuffers: [String: AssemblyBuffer] = [:]
    private let lock = NSLock()

    public init(config: BleConfig = BleConfig()) {
        self.config = config
        super.init()
    }

    public func setListener(_ listener: TransportListener) {
        lock.lock()
        self.listener = listener
        lock.unlock()
    }

    public func start() {
        queue.async {
            guard !self.isRunning else { return }
            self.isRunning = true

            self.centralManager = CBCentralManager(
                delegate: self,
                queue: self.queue,
                options: [CBCentralManagerOptionShowPowerAlertKey: true]
            )

            self.peripheralManager = CBPeripheralManager(
                delegate: self,
                queue: self.queue,
                options: [CBPeripheralManagerOptionShowPowerAlertKey: true]
            )
        }
    }

    public func stop() {
        queue.async {
            guard self.isRunning else { return }
            self.isRunning = false

            if let central = self.centralManager, central.isScanning {
                central.stopScan()
            }
            for (_, entry) in self.activePeripherals {
                self.centralManager?.cancelPeripheralConnection(entry.peripheral)
            }
            self.activePeripherals.removeAll()
            self.discoveredPeripherals.removeAll()
            self.pendingCentralWrites.removeAll()

            if let peripheral = self.peripheralManager, peripheral.isAdvertising {
                peripheral.stopAdvertising()
            }
            self.subscribedCentrals.removeAll()
            self.pendingSubscriberNotifications.removeAll()

            self.centralManager = nil
            self.peripheralManager = nil

            self.lock.lock()
            self.assemblyBuffers.removeAll()
            self.lock.unlock()
        }
    }

    public func send(packet: AirDbPacket, targetPeerId: String?) async -> Bool {
        return await withCheckedContinuation { continuation in
            queue.async {
                guard self.isRunning else {
                    continuation.resume(returning: false)
                    return
                }

                let fragments = PacketFragmenter.fragment(
                    packet: packet,
                    maxChunkSize: self.config.maxFragmentChunkSize
                )

                var sentAny = false

                for fragment in fragments {
                    let encoded = BinaryCodec.encode(fragment)

                    // 1. Send to matching connected Peripherals (as Central)
                    for (uuid, entry) in self.activePeripherals {
                        let peerId = uuid.uuidString
                        if targetPeerId == nil || targetPeerId == peerId {
                            self.enqueueCentralWrite(data: encoded, peripheral: entry.peripheral, characteristic: entry.characteristic)
                            sentAny = true
                        }
                    }

                    // 2. Send to matching subscribed Centrals (as Peripheral)
                    if !self.subscribedCentrals.isEmpty {
                        if targetPeerId == nil {
                            self.enqueuePeripheralNotification(data: encoded)
                            sentAny = true
                        } else if let uuid = UUID(uuidString: targetPeerId!), self.subscribedCentrals[uuid] != nil {
                            self.enqueuePeripheralNotification(data: encoded)
                            sentAny = true
                        }
                    }
                }

                continuation.resume(returning: sentAny)
            }
        }
    }

    // MARK: - Central Transmission & Backpressure

    private func enqueueCentralWrite(data: Data, peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        let uuid = peripheral.identifier
        if peripheral.canSendWriteWithoutResponse && (pendingCentralWrites[uuid]?.isEmpty ?? true) {
            peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
        } else {
            var queue = pendingCentralWrites[uuid] ?? []
            if queue.count < config.maxPendingQueueSize {
                queue.append(data)
                pendingCentralWrites[uuid] = queue
            }
        }
    }

    private func drainCentralWrites(for peripheral: CBPeripheral) {
        let uuid = peripheral.identifier
        guard var queue = pendingCentralWrites[uuid], let entry = activePeripherals[uuid] else { return }

        while !queue.isEmpty && peripheral.canSendWriteWithoutResponse {
            let nextData = queue.removeFirst()
            peripheral.writeValue(nextData, for: entry.characteristic, type: .withoutResponse)
        }

        pendingCentralWrites[uuid] = queue
    }

    // MARK: - Peripheral Transmission & Backpressure

    private func enqueuePeripheralNotification(data: Data) {
        guard let pManager = peripheralManager, let char = localCharacteristic else { return }

        if pendingSubscriberNotifications.isEmpty {
            let success = pManager.updateValue(data, for: char, onSubscribedCentrals: nil)
            if !success {
                pendingSubscriberNotifications.append(data)
            }
        } else {
            if pendingSubscriberNotifications.count < config.maxPendingQueueSize {
                pendingSubscriberNotifications.append(data)
            }
        }
    }

    private func drainPeripheralNotifications() {
        guard let pManager = peripheralManager, let char = localCharacteristic else { return }

        while !pendingSubscriberNotifications.isEmpty {
            let nextData = pendingSubscriberNotifications[0]
            let success = pManager.updateValue(nextData, for: char, onSubscribedCentrals: nil)
            if success {
                pendingSubscriberNotifications.removeFirst()
            } else {
                break
            }
        }
    }

    // MARK: - Ingestion & Reassembly

    private func handleInboundData(_ data: Data, peerId: String, linkId: String) {
        guard let rawPacket = BinaryCodec.decode(data) else { return }

        let packetToDeliver: AirDbPacket
        if rawPacket.type == .fragment {
            lock.lock()
            let buffer = assemblyBuffers[linkId] ?? AssemblyBuffer()
            assemblyBuffers[linkId] = buffer
            lock.unlock()

            guard let assembled = buffer.ingest(rawPacket) else {
                return
            }
            packetToDeliver = assembled
        } else {
            packetToDeliver = rawPacket
        }

        lock.lock()
        let currentListener = self.listener
        lock.unlock()

        currentListener?.onTransportEvent(
            .packetReceived(packet: packetToDeliver, peerId: peerId, linkId: linkId)
        )
    }
}

// MARK: - CBCentralManagerDelegate & CBPeripheralDelegate

extension CoreBluetoothTransport: CBCentralManagerDelegate, CBPeripheralDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && isRunning {
            central.scanForPeripherals(
                withServices: [config.serviceUuid],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        discoveredPeripherals[peripheral.identifier] = peripheral
        lock.lock()
        listener?.onTransportEvent(.peerDiscovered(peerId: peripheral.identifier.uuidString, rssi: RSSI.intValue))
        lock.unlock()

        central.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([config.serviceUuid])
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let peerId = peripheral.identifier.uuidString
        let linkId = "central-\(peerId)"
        activePeripherals.removeValue(forKey: peripheral.identifier)
        pendingCentralWrites.removeValue(forKey: peripheral.identifier)

        lock.lock()
        assemblyBuffers.removeValue(forKey: linkId)
        listener?.onTransportEvent(.linkDisconnected(peerId: peerId, linkId: linkId))
        lock.unlock()

        if isRunning && central.state == .poweredOn {
            central.scanForPeripherals(
                withServices: [config.serviceUuid],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == config.serviceUuid {
            peripheral.discoverCharacteristics([config.characteristicUuid], for: service)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for char in characteristics where char.uuid == config.characteristicUuid {
            activePeripherals[peripheral.identifier] = (peripheral, char)
            peripheral.setNotifyValue(true, for: char)

            let peerId = peripheral.identifier.uuidString
            let linkId = "central-\(peerId)"
            lock.lock()
            listener?.onTransportEvent(.linkConnected(peerId: peerId, linkId: linkId))
            lock.unlock()
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        let peerId = peripheral.identifier.uuidString
        let linkId = "central-\(peerId)"
        handleInboundData(data, peerId: peerId, linkId: linkId)
    }

    public func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        drainCentralWrites(for: peripheral)
    }
}

// MARK: - CBPeripheralManagerDelegate

extension CoreBluetoothTransport: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn && isRunning {
            let char = CBMutableCharacteristic(
                type: config.characteristicUuid,
                properties: [.writeWithoutResponse, .notify],
                value: nil,
                permissions: [.writeable]
            )
            self.localCharacteristic = char

            let service = CBMutableService(type: config.serviceUuid, primary: true)
            service.characteristics = [char]

            peripheral.add(service)

            peripheral.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [config.serviceUuid],
                CBAdvertisementDataLocalNameKey: "AirDB"
            ])
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentrals[central.identifier] = central
        let peerId = central.identifier.uuidString
        let linkId = "peripheral-\(peerId)"

        lock.lock()
        listener?.onTransportEvent(.linkConnected(peerId: peerId, linkId: linkId))
        lock.unlock()
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeValue(forKey: central.identifier)
        let peerId = central.identifier.uuidString
        let linkId = "peripheral-\(peerId)"

        lock.lock()
        assemblyBuffers.removeValue(forKey: linkId)
        listener?.onTransportEvent(.linkDisconnected(peerId: peerId, linkId: linkId))
        lock.unlock()
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let firstRequest = requests.first else { return }

        for request in requests {
            guard request.characteristic.uuid == config.characteristicUuid, let data = request.value else {
                continue
            }
            let peerId = request.central.identifier.uuidString
            let linkId = "peripheral-\(peerId)"
            handleInboundData(data, peerId: peerId, linkId: linkId)
        }

        peripheral.respond(to: firstRequest, withResult: .success)
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainPeripheralNotifications()
    }
}
#endif
