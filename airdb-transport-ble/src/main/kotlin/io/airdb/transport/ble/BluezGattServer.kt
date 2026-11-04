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

import org.bluez.GattManager1
import org.bluez.GattService1
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clean BlueZ D-Bus GATT characteristic definition omitting non-exportable FileDescriptor types.
 */
@DBusInterfaceName("org.bluez.GattCharacteristic1")
interface BluezGattCharacteristic1 : DBusInterface, Properties {
    fun ReadValue(options: Map<String, Variant<*>>): ByteArray
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
    fun Confirm()
}

internal class BluezGattServer(
    private val conn: DBusConnection,
    private val adapterPath: String,
    private val serviceUuid: String,
    private val characteristicUuid: String,
    private val onCharacteristicWrite: (data: ByteArray, devicePath: String?) -> Unit,
    private val onNotifyStateChanged: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    private val appPath = "/io/airdb"
    private val servicePath = "/io/airdb/service0"
    private val charPath = "/io/airdb/service0/char0"

    private val isRegistered = AtomicBoolean(false)
    val isNotifying = AtomicBoolean(false)

    private var gattService: AirDbGattService? = null
    private var gattChar: AirDbGattCharacteristic? = null
    private var objectManager: AirDbObjectManager? = null

    fun start() {
        if (!isRegistered.compareAndSet(false, true)) return

        try {
            val service = AirDbGattService(servicePath, serviceUuid)
            val characteristic = AirDbGattCharacteristic(
                charPath = charPath,
                servicePath = servicePath,
                charUuid = characteristicUuid,
                onWrite = onCharacteristicWrite,
                onNotifyStateChanged = { notifying ->
                    isNotifying.set(notifying)
                    log("client notify: $notifying")
                    onNotifyStateChanged(notifying)
                }
            )
            val objManager = AirDbObjectManager(
                appPath = appPath,
                servicePath = servicePath,
                charPath = charPath,
                service = service,
                characteristic = characteristic
            )

            this.gattService = service
            this.gattChar = characteristic
            this.objectManager = objManager

            val gattManager = conn.getRemoteObject(
                "org.bluez",
                adapterPath,
                GattManager1::class.java
            )

            // Preemptively clear any stale GATT application leftover from previously killed process
            runCatching {
                gattManager.UnregisterApplication(DBusPath(appPath))
                conn.unExportObject(charPath)
                conn.unExportObject(servicePath)
                conn.unExportObject(appPath)
            }

            conn.exportObject(appPath, objManager)
            conn.exportObject(servicePath, service)
            conn.exportObject(charPath, characteristic)

            gattManager.RegisterApplication(DBusPath(appPath), emptyMap())
            log("gatt app registered at $appPath")
        } catch (e: Exception) {
            isRegistered.set(false)
            conn.runCatching { unExportObject(charPath) }
            conn.runCatching { unExportObject(servicePath) }
            conn.runCatching { unExportObject(appPath) }
            throw e
        }
    }

    fun notify(data: ByteArray): Boolean {
        if (!isRegistered.get() || !isNotifying.get()) return false

        return try {
            gattChar?.currentValue = data
            val signal = Properties.PropertiesChanged(
                charPath,
                "org.bluez.GattCharacteristic1",
                mapOf("Value" to Variant(data)),
                emptyList()
            )
            conn.sendMessage(signal)
            true
        } catch (e: Exception) {
            log("notify error: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!isRegistered.compareAndSet(true, false)) return

        isNotifying.set(false)
        runCatching {
            val gattManager = conn.getRemoteObject(
                "org.bluez",
                adapterPath,
                GattManager1::class.java
            )
            gattManager.UnregisterApplication(DBusPath(appPath))
        }

        conn.runCatching { unExportObject(charPath) }
        conn.runCatching { unExportObject(servicePath) }
        conn.runCatching { unExportObject(appPath) }
        log("gatt app unregistered")
    }

    private class AirDbGattService(
        val servicePath: String,
        val serviceUuid: String
    ) : GattService1 {

        private val properties: Map<String, Variant<*>> = mapOf(
            "UUID" to Variant(serviceUuid),
            "Primary" to Variant(true),
            "Characteristics" to Variant(arrayOf(DBusPath("/io/airdb/service0/char0")))
        )

        override fun isRemote(): Boolean = false
        override fun getObjectPath(): String = servicePath

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            properties[propertyName]?.value as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties
    }

    private class AirDbGattCharacteristic(
        val charPath: String,
        val servicePath: String,
        val charUuid: String,
        val onWrite: (data: ByteArray, devicePath: String?) -> Unit,
        val onNotifyStateChanged: (Boolean) -> Unit
    ) : BluezGattCharacteristic1 {

        @Volatile
        var currentValue: ByteArray = byteArrayOf()

        private val properties: Map<String, Variant<*>> = mapOf(
            "UUID" to Variant(charUuid),
            "Service" to Variant(DBusPath(servicePath)),
            "Flags" to Variant(arrayOf("read", "write", "write-without-response", "notify")),
            "Value" to Variant(currentValue)
        )

        override fun isRemote(): Boolean = false
        override fun getObjectPath(): String = charPath

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
            if (propertyName == "Value") {
                return currentValue as A
            }
            return properties[propertyName]?.value as A
        }

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
            val map = properties.toMutableMap()
            map["Value"] = Variant(currentValue)
            return map
        }

        override fun ReadValue(options: Map<String, Variant<*>>): ByteArray = currentValue

        override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
            currentValue = value
            val devObj = options["device"]?.value
            val devPath = (devObj as? DBusPath)?.path ?: devObj?.toString()
            onWrite(value, devPath)
        }

        override fun StartNotify() {
            onNotifyStateChanged(true)
        }

        override fun StopNotify() {
            onNotifyStateChanged(false)
        }

        override fun Confirm() {}
    }

    private class AirDbObjectManager(
        val appPath: String,
        val servicePath: String,
        val charPath: String,
        val service: AirDbGattService,
        val characteristic: AirDbGattCharacteristic
    ) : ObjectManager {

        override fun isRemote(): Boolean = false
        override fun getObjectPath(): String = appPath

        override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> {
            return mapOf(
                DBusPath(servicePath) to mapOf(
                    "org.bluez.GattService1" to service.GetAll("org.bluez.GattService1")
                ),
                DBusPath(charPath) to mapOf(
                    "org.bluez.GattCharacteristic1" to characteristic.GetAll("org.bluez.GattCharacteristic1")
                )
            )
        }
    }
}
