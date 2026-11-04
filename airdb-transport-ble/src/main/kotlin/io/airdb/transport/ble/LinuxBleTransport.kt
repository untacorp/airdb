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

import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothCommandStatus
import com.welie.blessed.BluetoothGattCharacteristic
import com.welie.blessed.BluetoothGattService
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.ScanResult
import io.airdb.protocol.codec.BinaryCodec
import io.airdb.protocol.fragment.AssemblyBuffer
import io.airdb.protocol.fragment.PacketFragmenter
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import io.airdb.transport.ble.queue.GattOperationQueue
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bluez.LEAdvertisement1
import org.bluez.LEAdvertisingManager1
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth Low Energy transport for Linux desktop environments communicating over BlueZ D-Bus.
 *
 * Operates simultaneously as a GATT Central (Scanner/Client via Blessed-BlueZ) and a GATT Peripheral
 * (Advertiser/Server via BlueZ D-Bus GattManager1 & LEAdvertisingManager1).
 * Serializes physical characteristic writes through [GattOperationQueue].
 *
 * @param nodeName Local node identifier for discovery and session handshakes.
 * @param config Bluetooth Low Energy radio configuration parameters.
 * @param parentScope Parent coroutine scope for async event and queue dispatching.
 */
class LinuxBleTransport(
    val nodeName: String,
    val config: BleConfig = BleConfig(),
    private val parentScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : AirDbTransport {

    var verbose: Boolean = false
    var logger: ((String) -> Unit)? = null

    private fun log(message: String) {
        if (verbose) {
            println("[ble] $message")
        }
        logger?.invoke(message)
    }

    private fun logError(message: String) {
        if (verbose) {
            System.err.println("[ble] error: $message")
        }
        logger?.invoke("error: $message")
    }

    private val isRunning = AtomicBoolean(false)
    private var listener: TransportListener? = null

    @Volatile
    private var clusterKey: ByteArray? = config.clusterKey

    override fun setClusterKey(key: ByteArray?) {
        this.clusterKey = key
    }

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    private var sessionJob: CompletableJob? = null
    private var sessionScope: CoroutineScope? = null
    private var gattQueue: GattOperationQueue? = null

    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()
    private val connectedPeripherals = ConcurrentHashMap<String, BluetoothPeripheral>()
    private val connectedServerCentrals = ConcurrentHashMap.newKeySet<String>()
    private val assemblyBuffers = ConcurrentHashMap<String, AssemblyBuffer>()

    private var centralManager: BluetoothCentralManager? = null
    private var dBusConn: DBusConnection? = null
    private var isAdvertisementRegistered = false
    private var gattServer: BluezGattServer? = null
    private var activeAdapterPath = "/org/bluez/hci0"
    private val advertisementPath = "/io/airdb/adv0"

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(
            peripheral: BluetoothPeripheral,
            services: List<BluetoothGattService>
        ) {
            val address = peripheral.address
            val service = peripheral.getService(config.serviceUuid)
            if (service == null) {
                logError("gatt service not found on $address")
                connectingAddresses.remove(address)
                centralManager?.cancelConnection(peripheral)
                return
            }

            val characteristic = service.getCharacteristic(config.characteristicUuid)
            if (characteristic == null) {
                logError("gatt characteristic not found on $address")
                connectingAddresses.remove(address)
                centralManager?.cancelConnection(peripheral)
                return
            }

            log("service resolved on $address, enable notify")
            val success = peripheral.setNotify(characteristic, true)
            if (!success) {
                logError("failed to initiate notify on $address")
                connectingAddresses.remove(address)
                centralManager?.cancelConnection(peripheral)
            }
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            val address = peripheral.address
            val linkId = "client-$address"
            val isSuccess = status == BluetoothCommandStatus.COMMAND_SUCCESS

            connectingAddresses.remove(address)
            if (isSuccess && characteristic.uuid == config.characteristicUuid) {
                connectedPeripherals[address] = peripheral
                log("link up: $address ($linkId)")
                listener?.onTransportEvent(TransportEvent.LinkConnected(address, linkId))
            } else {
                logError("enable notify failed on $address: $status")
                centralManager?.cancelConnection(peripheral)
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            if (characteristic.uuid != config.characteristicUuid) return
            handleInboundBytes(value, peerId = peripheral.address, linkId = "client-${peripheral.address}")
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            val linkId = "client-${peripheral.address}"
            val isSuccess = status == BluetoothCommandStatus.COMMAND_SUCCESS
            gattQueue?.onOperationComplete(linkId, isSuccess)
        }
    }

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            val address = peripheral.address
            val name = peripheral.name.ifEmpty { address }
            log("peer discovered: $address rssi=${scanResult.rssi}dBm")
            listener?.onTransportEvent(TransportEvent.PeerDiscovered(address, scanResult.rssi))

            if (connectedPeripherals.containsKey(address) || !connectingAddresses.add(address)) {
                return
            }

            log("connect: $address")
            centralManager?.connectPeripheral(peripheral, peripheralCallback)
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            log("connected: ${peripheral.address}")
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: BluetoothCommandStatus) {
            logError("connection to ${peripheral.address} failed: $status")
            connectingAddresses.remove(peripheral.address)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: BluetoothCommandStatus) {
            val address = peripheral.address
            val linkId = "client-$address"
            connectingAddresses.remove(address)
            connectedPeripherals.remove(address)
            assemblyBuffers.remove(linkId)
            gattQueue?.onLinkDisconnected(linkId)
            log("link down: $address")
            listener?.onTransportEvent(TransportEvent.LinkDisconnected(address, linkId))
        }

        override fun onPinRequest(peripheral: BluetoothPeripheral): String {
            return "000000"
        }
    }

    override fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        val job = SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job])
        val scope = CoroutineScope(parentScope.coroutineContext + job)
        sessionJob = job
        sessionScope = scope

        gattQueue = GattOperationQueue(
            scope = scope,
            maxQueueSize = config.maxPendingQueueSize,
            maxBytesPerLink = config.maxPendingBytes,
            retryDelayMs = config.sendRetryDelayMs,
            maxRetries = config.maxCallbackRetries
        )

        try {
            log("init node $nodeName")
            startGattServer()
            startAdvertising()

            centralManager = BluetoothCentralManager(centralCallback)
            centralManager?.scanForPeripheralsWithServices(arrayOf(config.serviceUuid))
            log("scan started for ${config.serviceUuid}")
        } catch (e: Exception) {
            logError("start failed: ${e.message}")
            stop()
            throw e
        }
    }

    override fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        runCatching {
            centralManager?.stopScan()
            connectedPeripherals.values.forEach { peripheral ->
                centralManager?.cancelConnection(peripheral)
            }
        }

        stopAdvertising()
        stopGattServer()

        runCatching {
            Thread.sleep(100)
            centralManager?.shutdown()
        }

        runCatching {
            dBusConn?.close()
        }
        dBusConn = null

        connectedPeripherals.clear()
        connectedServerCentrals.clear()
        connectingAddresses.clear()
        assemblyBuffers.clear()
        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null
        gattQueue = null
        log("transport stopped")
    }

    private fun resolveActiveAdapter(conn: DBusConnection) {
        val detected = runCatching {
            com.welie.blessed.bluez.DbusHelper.findBluezAdapters(conn).firstOrNull()?.dbusPath
        }.getOrNull()
        if (detected != null) {
            activeAdapterPath = detected
        }
    }

    private fun startGattServer() {
        val conn = dBusConn ?: runCatching {
            DBusConnectionBuilder.forSystemBus().build().also { dBusConn = it }
        }.getOrNull() ?: return

        resolveActiveAdapter(conn)

        try {
            val server = BluezGattServer(
                conn = conn,
                adapterPath = activeAdapterPath,
                serviceUuid = config.serviceUuid.toString(),
                characteristicUuid = config.characteristicUuid.toString(),
                onCharacteristicWrite = { data, devicePath ->
                    val peerAddress = parseMacFromDevicePath(devicePath)
                    val linkId = "server-$peerAddress"
                    if (connectedServerCentrals.add(peerAddress)) {
                        log("server link up: $peerAddress ($linkId)")
                        listener?.onTransportEvent(TransportEvent.LinkConnected(peerAddress, linkId))
                    }
                    handleInboundBytes(data, peerId = peerAddress, linkId = linkId)
                },
                onNotifyStateChanged = { notifying ->
                    if (!notifying) {
                        for (peerAddress in connectedServerCentrals) {
                            val linkId = "server-$peerAddress"
                            connectedServerCentrals.remove(peerAddress)
                            assemblyBuffers.remove(linkId)
                            gattQueue?.onLinkDisconnected(linkId)
                            log("server link down: $peerAddress ($linkId)")
                            listener?.onTransportEvent(TransportEvent.LinkDisconnected(peerAddress, linkId))
                        }
                    }
                },
                log = { msg -> log(msg) }
            )
            server.start()
            gattServer = server
            log("gatt server active on $activeAdapterPath")
        } catch (e: Exception) {
            log("gatt server unavailable (${e.message}), central-only")
        }
    }

    private fun stopGattServer() {
        runCatching { gattServer?.stop() }
        gattServer = null
    }

    private fun startAdvertising() {
        try {
            val conn = dBusConn ?: DBusConnectionBuilder.forSystemBus().build().also { dBusConn = it }
            resolveActiveAdapter(conn)

            val advManager = conn.getRemoteObject(
                "org.bluez",
                activeAdapterPath,
                LEAdvertisingManager1::class.java
            )

            val advertisement = BluezLeAdvertisement(
                serviceUuid = config.serviceUuid.toString(),
                localName = nodeName,
                advPath = advertisementPath
            )

            conn.exportObject(advertisementPath, advertisement)
            advManager.RegisterAdvertisement(DBusPath(advertisementPath), emptyMap())
            isAdvertisementRegistered = true
            log("adv active on $activeAdapterPath")
        } catch (e: Exception) {
            log("adv unavailable (${e.message}), central-only")
        }
    }

    private fun stopAdvertising() {
        if (!isAdvertisementRegistered) return
        val conn = dBusConn ?: return
        runCatching {
            val advManager = conn.getRemoteObject(
                "org.bluez",
                activeAdapterPath,
                LEAdvertisingManager1::class.java
            )
            advManager.UnregisterAdvertisement(DBusPath(advertisementPath))
            conn.unExportObject(advertisementPath)
        }
        isAdvertisementRegistered = false
    }

    override suspend fun send(packet: AirDbPacket, targetPeerId: String?): Boolean {
        if (!isRunning.get()) return false

        val queue = gattQueue ?: return false
        val key = clusterKey
        val fragments = PacketFragmenter.fragment(packet, clusterKey = key, maxChunkSize = config.maxFragmentChunkSize)
        var anyDispatched = false

        for (fragment in fragments) {
            val encoded = if (fragment.type == PacketType.FRAGMENT) {
                BinaryCodec.encode(fragment)
            } else {
                BinaryCodec.encode(fragment, clusterKey = key)
            }

            // 1. Dispatch to outbound Central client connections
            connectedPeripherals.forEach { (address, peripheral) ->
                if (targetPeerId == null || targetPeerId == address) {
                    val linkId = "client-$address"
                    val char = peripheral.getCharacteristic(config.serviceUuid, config.characteristicUuid)
                    val writeType = if (char != null && char.supportsWriteType(BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE)) {
                        BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
                    }
                    val enqueued = queue.enqueue(linkId, encoded, isNotification = false) {
                        peripheral.writeCharacteristic(
                            config.serviceUuid,
                            config.characteristicUuid,
                            encoded,
                            writeType
                        )
                    }
                    if (enqueued) anyDispatched = true
                }
            }

            // 2. Dispatch to subscribed Centrals via GATT Server notifications
            val server = gattServer
            if (server != null && server.isNotifying.get()) {
                if (targetPeerId == null || connectedServerCentrals.contains(targetPeerId)) {
                    if (server.notify(encoded)) {
                        anyDispatched = true
                    }
                }
            }
        }

        return anyDispatched
    }

    private fun handleInboundBytes(data: ByteArray, peerId: String, linkId: String) {
        val key = clusterKey
        val raw = BinaryCodec.decode(data, clusterKey = key) ?: return
        val packet = if (raw.type == PacketType.FRAGMENT) {
            assemblyBuffers.computeIfAbsent(linkId) { AssemblyBuffer() }.ingest(raw, clusterKey = key) ?: return
        } else {
            raw
        }

        if (key != null && !packet.isEncrypted) return

        log("rx ${packet.type} from $peerId")
        listener?.onTransportEvent(TransportEvent.PacketReceived(packet, peerId, linkId))
    }

    private fun parseMacFromDevicePath(devicePath: String?): String {
        if (devicePath == null) return "unknown-peer"
        val devIdx = devicePath.lastIndexOf("dev_")
        return if (devIdx != -1) {
            devicePath.substring(devIdx + 4).replace('_', ':')
        } else {
            devicePath
        }
    }

    private class BluezLeAdvertisement(
        val serviceUuid: String,
        val localName: String,
        val advPath: String
    ) : LEAdvertisement1 {

        private val properties: Map<String, Variant<*>> = mapOf(
            "Type" to Variant("peripheral"),
            "ServiceUUIDs" to Variant(arrayOf(serviceUuid)),
            "LocalName" to Variant(localName),
            "IncludeTxPower" to Variant(true)
        )

        override fun Release() {}

        override fun isRemote(): Boolean = false

        override fun getObjectPath(): String = advPath

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            properties[propertyName]?.value as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties
    }
}
