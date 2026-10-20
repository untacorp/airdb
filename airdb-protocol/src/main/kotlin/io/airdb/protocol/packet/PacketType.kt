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

package io.airdb.protocol.packet

/**
 * Message types recognized by the AirDB wire protocol.
 */
enum class PacketType(val code: Byte) {
    /** Initial discovery handshake initiating session. */
    HANDSHAKE(0x01),

    /** Handshake acknowledgment acknowledging protocol version and capabilities. */
    HANDSHAKE_ACK(0x02),

    /** Reconciliation request carrying local vector clock / state summary. */
    RECONCILE_REQ(0x03),

    /** Reconciliation response determining delta range. */
    RECONCILE_RESP(0x04),

    /** Batch of database mutations. */
    DELTA_BATCH(0x05),

    /** Acknowledgment of applied delta batch. */
    DELTA_ACK(0x06),

    /** Fragment of an oversized packet. */
    FRAGMENT(0x0F);

    companion object {
        private val map = entries.associateBy(PacketType::code)

        fun fromCode(code: Byte): PacketType? = map[code]
    }
}
