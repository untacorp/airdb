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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LinuxBleSmokeTest {

    @Test
    fun testBleTransportFactoryOnCurrentPlatform() {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        if (os.contains("linux")) {
            val transport = BleTransportFactory.create("linux-test-node")
            assertTrue(transport is LinuxBleTransport, "Expected LinuxBleTransport on Linux")
            assertEquals("linux-test-node", (transport as LinuxBleTransport).nodeName)
        }
    }

    @Test
    fun testBleTransportFactoryWindowsThrowsUnsupported() {
        val originalOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val exception = assertThrows(UnsupportedOperationException::class.java) {
                BleTransportFactory.create("win-node")
            }
            assertTrue(exception.message?.contains("Windows") == true)
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs)
            }
        }
    }

    @Test
    fun testBleTransportFactoryUnknownOsThrowsUnsupported() {
        val originalOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "FreeBSD")
            val exception = assertThrows(UnsupportedOperationException::class.java) {
                BleTransportFactory.create("freebsd-node")
            }
            assertTrue(exception.message?.contains("FreeBSD", ignoreCase = true) == true)
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs)
            }
        }
    }

    @Test
    fun testLinuxBleTransportConfiguration() {
        val customUuid = UUID.randomUUID()
        val config = BleConfig(serviceUuid = customUuid)
        val transport = LinuxBleTransport("linux-custom", config)

        assertEquals("linux-custom", transport.nodeName)
        assertEquals(customUuid, transport.config.serviceUuid)

        var logCaptured = ""
        transport.logger = { msg -> logCaptured = msg }
        transport.stop() // Safe to call even when not started
        assertEquals("", logCaptured)
    }

    @Test
    fun testWindowsBleTransportStub() {
        val transport = WindowsBleTransport("win-test")
        assertEquals("win-test", transport.nodeName)

        var logCaptured = ""
        transport.logger = { msg -> logCaptured = msg }

        transport.start()
        assertTrue(logCaptured.contains("Windows BLE transport initialized"))

        transport.stop()
        assertTrue(logCaptured.contains("Windows BLE transport stopped"))
    }
}
