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

package io.airdb.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import io.airdb.protocol.codec.BinaryCodec
import io.airdb.protocol.fragment.AssemblyBuffer
import io.airdb.protocol.fragment.PacketFragmenter
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import io.airdb.transport.ble.queue.GattOperationQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production Bluetooth Low Energy transport for Android.
 *
 * Runs concurrently as both a GATT Central (Scanner/Client) and GATT Peripheral (Advertiser/Server).
 * Serializes all physical writes and notifications through [GattOperationQueue] to prevent
 * Android BlueDroid/Fluoride stack race conditions.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
    val config: BleConfig = BleConfig(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : AirDbTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val gattQueue = GattOperationQueue(
        scope = scope,
        maxQueueSize = config.maxPendingQueueSize,
        maxBytesPerLink = config.maxPendingBytes,
        retryDelayMs = config.sendRetryDelayMs,
        maxRetries = config.maxCallbackRetries
    )

    private var listener: TransportListener? = null
    private val isRunning = AtomicBoolean(false)

    // Central (Client) role state
    private var scanner: BluetoothLeScanner? = null
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val negotiatedMtus = ConcurrentHashMap<String, Int>()

    // Peripheral (Server) role state
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()

    // Fragment assembly buffer per link
    private val assemblyBuffers = ConcurrentHashMap<String, AssemblyBuffer>()

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun start() {
        if (!AndroidBlePermissions.hasPermissions(context)) {
            System.err.println("AirDB: Cannot start AndroidBleTransport - missing required Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            System.err.println("AirDB: Cannot start AndroidBleTransport - Bluetooth adapter is disabled or unavailable")
            return
        }
        if (!isRunning.compareAndSet(false, true)) return

        startGattServer()
        startAdvertising()
        startScanning()
    }

    override fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        stopScanning()
        stopAdvertising()
        stopGattServer()

        for ((linkId, gatt) in activeGattClients) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Exception) {}
            gattQueue.onLinkDisconnected(linkId)
        }
        activeGattClients.clear()
        clientCharacteristics.clear()
        negotiatedMtus.clear()
        subscribedCentrals.clear()
        assemblyBuffers.clear()
        scope.cancel()
    }

    override suspend fun send(packet: AirDbPacket, targetPeerId: String?): Boolean {
        if (!isRunning.get()) return false

        val fragments = PacketFragmenter.fragment(packet, maxChunkSize = config.maxFragmentChunkSize)
        var anyEnqueued = false

        for (fragment in fragments) {
            val encoded = BinaryCodec.encode(fragment)

            // 1. Transmit via active GATT Client connections (to discovered Peripherals)
            for ((address, gatt) in activeGattClients) {
                if (targetPeerId == null || targetPeerId == address) {
                    val char = clientCharacteristics[address]
                    if (char != null) {
                        val linkId = "client-$address"
                        val accepted = gattQueue.enqueue(linkId, encoded, isNotification = false) {
                            writeCharacteristicCompat(gatt, char, encoded)
                        }
                        if (accepted) anyEnqueued = true
                    }
                }
            }

            // 2. Transmit via active GATT Server notifications (to subscribed Centrals)
            val server = gattServer
            val char = serverCharacteristic
            if (server != null && char != null) {
                for ((address, central) in subscribedCentrals) {
                    if (targetPeerId == null || targetPeerId == address) {
                        val linkId = "server-$address"
                        val accepted = gattQueue.enqueue(linkId, encoded, isNotification = true) {
                            notifyCharacteristicCompat(server, central, char, encoded)
                        }
                        if (accepted) anyEnqueued = true
                    }
                }
            }
        }

        return anyEnqueued
    }

    // MARK: - Central (Client) Scanning & Connection

    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(config.serviceUuid))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(config.powerMode.scanMode)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address

            if (activeGattClients.containsKey(address) || subscribedCentrals.containsKey(address)) {
                return // Already connected or subscribed
            }

            listener?.onTransportEvent(TransportEvent.PeerDiscovered(address, result.rssi))

            // Connect GATT Client
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattClientCallback)
            }
            activeGattClients[address] = gatt
        }

        override fun onScanFailed(errorCode: Int) {
            System.err.println("AirDB: Bluetooth LE scan failed with error code: $errorCode")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val linkId = "client-$address"

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Request maximum MTU
                gatt.requestMtu(config.targetMtu)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                activeGattClients.remove(address)
                clientCharacteristics.remove(address)
                negotiatedMtus.remove(address)
                assemblyBuffers.remove(linkId)
                gattQueue.onLinkDisconnected(linkId)

                try {
                    gatt.close()
                } catch (_: Exception) {}

                listener?.onTransportEvent(TransportEvent.LinkDisconnected(address, linkId))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtus[gatt.device.address] = mtu
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val address = gatt.device.address
            val linkId = "client-$address"
            val service = gatt.getService(config.serviceUuid) ?: return
            val characteristic = service.getCharacteristic(config.characteristicUuid) ?: return

            clientCharacteristics[address] = characteristic
            gatt.setCharacteristicNotification(characteristic, true)

            // Subscribe to remote notifications via CCCD
            val cccd = characteristic.getDescriptor(config.cccdUuid)
            if (cccd != null) {
                gattQueue.enqueue(linkId, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, isNotification = false) {
                    writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            } else {
                listener?.onTransportEvent(TransportEvent.LinkConnected(address, linkId))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val address = gatt.device.address
            val linkId = "client-$address"
            gattQueue.onOperationComplete(linkId, status == BluetoothGatt.GATT_SUCCESS)

            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == config.cccdUuid) {
                listener?.onTransportEvent(TransportEvent.LinkConnected(address, linkId))
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val linkId = "client-${gatt.device.address}"
            gattQueue.onOperationComplete(linkId, status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            handleInboundBytes(data, peerId = gatt.device.address, linkId = "client-${gatt.device.address}")
        }
    }

    // MARK: - Peripheral (Server) Hosting & Advertising

    private fun startGattServer() {
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return

        val service = BluetoothGattService(config.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            config.characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val cccd = BluetoothGattDescriptor(
            config.cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)

        gattServer?.addService(service)
        serverCharacteristic = characteristic
    }

    private fun stopGattServer() {
        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null
        serverCharacteristic = null
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(config.powerMode.advertiseMode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(config.serviceUuid))
            .setIncludeDeviceName(false) // Keep within legacy 31-byte advertising boundary
            .setIncludeTxPowerLevel(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            System.err.println("AirDB: Bluetooth LE advertising failed with error code: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            val linkId = "server-$address"

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(address)
                assemblyBuffers.remove(linkId)
                gattQueue.onLinkDisconnected(linkId)
                listener?.onTransportEvent(TransportEvent.LinkDisconnected(address, linkId))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == config.cccdUuid) {
                val address = device.address
                val linkId = "server-$address"
                subscribedCentrals[address] = device

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }

                listener?.onTransportEvent(TransportEvent.LinkConnected(address, linkId))
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            if (value != null && characteristic.uuid == config.characteristicUuid) {
                val address = device.address
                val linkId = "server-$address"
                handleInboundBytes(value, peerId = address, linkId = linkId)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val linkId = "server-${device.address}"
            gattQueue.onOperationComplete(linkId, status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // MARK: - Inbound Ingestion & Reassembly

    private fun handleInboundBytes(data: ByteArray, peerId: String, linkId: String) {
        val rawPacket = BinaryCodec.decode(data) ?: return

        val packetToDeliver = if (rawPacket.type == PacketType.FRAGMENT) {
            val buffer = assemblyBuffers.computeIfAbsent(linkId) { AssemblyBuffer() }
            buffer.ingest(rawPacket) ?: return
        } else {
            rawPacket
        }

        listener?.onTransportEvent(
            TransportEvent.PacketReceived(
                packet = packetToDeliver,
                peerId = peerId,
                linkId = linkId
            )
        )
    }

    // MARK: - API Compatibility Helpers

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun notifyCharacteristicCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, char, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, char, false)
        }
    }
}
