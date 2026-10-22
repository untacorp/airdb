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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BleConfigTest {

    @Test
    fun testDefaultBleConfigHasNullClusterKey() {
        val config = BleConfig()
        assertNull(config.clusterKey)
    }

    @Test
    fun testBleConfigWithClusterKeyEquality() {
        val key1 = ByteArray(32) { 0x42 }
        val key2 = ByteArray(32) { 0x42 }
        val key3 = ByteArray(32) { 0x99.toByte() }

        val config1 = BleConfig(clusterKey = key1)
        val config2 = BleConfig(clusterKey = key2)
        val config3 = BleConfig(clusterKey = key3)

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
        assertNotEquals(config1, config3)
        assertNotEquals(config1, BleConfig())
    }
}
