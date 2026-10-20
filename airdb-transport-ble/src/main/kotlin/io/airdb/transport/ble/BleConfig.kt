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

import java.util.UUID

/**
 * Power and latency preference for Bluetooth Low Energy advertising and scanning.
 */
enum class BlePowerMode(val advertiseMode: Int, val scanMode: Int) {
    /** Balanced discovery rate with moderate radio battery consumption (Default). */
    BALANCED(advertiseMode = 1, scanMode = 1),

    /** High-frequency radio scanning and advertising for immediate discovery in emergency response. */
    LOW_LATENCY(advertiseMode = 2, scanMode = 2),

    /** Minimal battery consumption for background monitoring. */
    LOW_POWER(advertiseMode = 0, scanMode = 0)
}

/**
 * Configuration parameters for the AirDB Bluetooth Low Energy transport layer.
 */
data class BleConfig(
    /** Primary GATT Service UUID advertised and scanned. */
    val serviceUuid: UUID = UUID.fromString("41697244-4200-0000-8000-00805F9B34FB"),

    /** Single bidirectional GATT Characteristic UUID used for writes and notifications. */
    val characteristicUuid: UUID = UUID.fromString("41697244-4201-0000-8000-00805F9B34FB"),

    /** Standard Client Characteristic Configuration Descriptor (CCCD). */
    val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),

    /** Requested BLE Attribute MTU. 517 is maximum standard for BLE 4.2+. */
    val targetMtu: Int = 517,

    /** Maximum payload size per BLE fragment to stay within safe MTU boundaries. */
    val maxFragmentChunkSize: Int = 460,

    /** Maximum pending writes per peripheral link before backpressure trimming. */
    val maxPendingQueueSize: Int = 256,

    /** Maximum pending bytes per peripheral link before rejection. */
    val maxPendingBytes: Int = 1_048_576,

    /** Retry delay in milliseconds for transient GATT failures. */
    val sendRetryDelayMs: Long = 15L,

    /** Maximum retry attempts for a single in-flight GATT callback failure. */
    val maxCallbackRetries: Int = 3,

    /** Radio power and discovery latency profile. Defaults to BALANCED with LOW_LATENCY option. */
    val powerMode: BlePowerMode = BlePowerMode.BALANCED
)
