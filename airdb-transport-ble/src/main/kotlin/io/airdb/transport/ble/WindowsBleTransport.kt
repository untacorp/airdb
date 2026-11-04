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

import io.airdb.protocol.packet.AirDbPacket
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth Low Energy transport stub for Windows desktop platforms (WinRT GATT provider).
 *
 * Physical WinRT BLE communication is currently under development. This class serves
 * as an architectural interface stub and should not be used for production sync.
 *
 * @param nodeName Originating node identifier broadcast in advertisements.
 * @param config BLE protocol parameters, target MTU, and timeouts.
 */
class WindowsBleTransport(
    val nodeName: String,
    val config: BleConfig = BleConfig()
) : AirDbTransport {

    var logger: ((String) -> Unit)? = null
    private val isRunning = AtomicBoolean(false)
    private var listener: TransportListener? = null

    override fun setClusterKey(key: ByteArray?) {}

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        val message = "Windows BLE transport initialized for node '$nodeName' (Stub mode)"
        println("[AirDB-Windows] $message")
        logger?.invoke(message)
    }

    override fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        val message = "Windows BLE transport stopped"
        println("[AirDB-Windows] $message")
        logger?.invoke(message)
    }

    override suspend fun send(packet: AirDbPacket, targetPeerId: String?): Boolean {
        return false
    }
}
