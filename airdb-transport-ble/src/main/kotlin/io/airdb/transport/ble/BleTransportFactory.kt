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

import io.airdb.transport.AirDbTransport

/**
 * Universal runtime factory resolving platform-specific Bluetooth Low Energy drivers.
 */
object BleTransportFactory {

    /**
     * Instantiates the appropriate [AirDbTransport] implementation for the current host environment.
     *
     * @param nodeName Local node identifier for discovery and session handshakes.
     * @param config Bluetooth Low Energy radio configuration parameters.
     * @param androidContext Android application or activity context when running on Android; null on desktop.
     * @return Platform-specific [AirDbTransport] implementation.
     * @throws IllegalArgumentException If running on Android and [androidContext] is not provided.
     * @throws UnsupportedOperationException If the host platform is unsupported.
     */
    fun create(
        nodeName: String,
        config: BleConfig = BleConfig(),
        androidContext: Any? = null
    ): AirDbTransport {
        val isAndroidRuntime = try {
            Class.forName("android.os.Build")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        if (isAndroidRuntime) {
            requireNotNull(androidContext) {
                "androidContext (android.content.Context) must be provided when running on the Android platform"
            }
            val transportClass = Class.forName("io.airdb.transport.ble.AndroidBleTransport")
            val contextClass = Class.forName("android.content.Context")
            val constructor = transportClass.constructors.first { ctor ->
                ctor.parameterTypes.isNotEmpty() && contextClass.isAssignableFrom(ctor.parameterTypes[0])
            }
            return constructor.newInstance(androidContext, config) as AirDbTransport
        }

        val os = (System.getProperty("os.name") ?: "").lowercase()
        return when {
            os.contains("linux") -> LinuxBleTransport(nodeName, config)
            os.contains("win") -> throw UnsupportedOperationException(
                "Windows WinRT BLE transport is currently under active development and not yet available for production use"
            )
            else -> throw UnsupportedOperationException("Host operating system '$os' is not supported for physical BLE")
        }
    }
}
