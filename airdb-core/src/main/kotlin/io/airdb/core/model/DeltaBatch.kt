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

package io.airdb.core.model

import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary serialization utility for batches of [ChangeRecord] objects.
 * Supports both unsigned and Ed25519 digitally signed records.
 */
object DeltaBatch {

    private const val FLAG_RECORD_SIGNED = 0x80

    fun serialize(changes: List<ChangeRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        val countBuffer = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        countBuffer.putShort(changes.size.toShort())
        out.write(countBuffer.array())

        for (change in changes) {
            val idBytes = change.id.toByteArray(Charsets.UTF_8)
            val collBytes = change.collection.toByteArray(Charsets.UTF_8)
            val hlcBytes = change.hlcTimestamp.toByteArray(Charsets.UTF_8)

            val isSigned = change.isSigned &&
                    change.authorPublicKey?.size == 32 &&
                    change.signature?.size == 64

            val typeByte = if (isSigned) {
                ((change.type.ordinal and 0x7F) or FLAG_RECORD_SIGNED).toByte()
            } else {
                (change.type.ordinal and 0x7F).toByte()
            }

            // Header for single change:
            // IdLen(2) + Id + CollLen(2) + Coll + Type(1) + HlcLen(2) + Hlc + PayloadLen(4) + Payload
            val buf = ByteBuffer.allocate(2 + idBytes.size + 2 + collBytes.size + 1 + 2 + hlcBytes.size + 4)
                .order(ByteOrder.BIG_ENDIAN)

            buf.putShort(idBytes.size.toShort())
            buf.put(idBytes)
            buf.putShort(collBytes.size.toShort())
            buf.put(collBytes)
            buf.put(typeByte)
            buf.putShort(hlcBytes.size.toShort())
            buf.put(hlcBytes)
            buf.putInt(change.payload.size)

            out.write(buf.array())
            out.write(change.payload)

            if (isSigned) {
                out.write(change.authorPublicKey!!)
                out.write(change.signature!!)
            }
        }

        return out.toByteArray()
    }

    fun deserialize(bytes: ByteArray): List<ChangeRecord> {
        if (bytes.size < 2) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort().toInt() and 0xFFFF
        val changes = ArrayList<ChangeRecord>(count)

        for (i in 0 until count) {
            if (buffer.remaining() < 2) break
            val idLen = buffer.getShort().toInt() and 0xFFFF
            if (buffer.remaining() < idLen) break
            val idBytes = ByteArray(idLen)
            buffer.get(idBytes)
            val id = String(idBytes, Charsets.UTF_8)

            if (buffer.remaining() < 2) break
            val collLen = buffer.getShort().toInt() and 0xFFFF
            if (buffer.remaining() < collLen) break
            val collBytes = ByteArray(collLen)
            buffer.get(collBytes)
            val collection = String(collBytes, Charsets.UTF_8)

            if (buffer.remaining() < 1) break
            val rawType = buffer.get().toInt() and 0xFF
            val isSigned = (rawType and FLAG_RECORD_SIGNED) != 0
            val typeOrdinal = rawType and 0x7F
            val type = MutationType.entries.getOrElse(typeOrdinal) { MutationType.UPDATE }

            if (buffer.remaining() < 2) break
            val hlcLen = buffer.getShort().toInt() and 0xFFFF
            if (buffer.remaining() < hlcLen) break
            val hlcBytes = ByteArray(hlcLen)
            buffer.get(hlcBytes)
            val hlcTimestamp = String(hlcBytes, Charsets.UTF_8)

            if (buffer.remaining() < 4) break
            val payloadLen = buffer.getInt()
            if (payloadLen < 0 || buffer.remaining() < payloadLen) break
            val payload = ByteArray(payloadLen)
            buffer.get(payload)

            var authorPublicKey: ByteArray? = null
            var signature: ByteArray? = null

            if (isSigned) {
                if (buffer.remaining() < 32 + 64) break
                val pubKey = ByteArray(32)
                buffer.get(pubKey)
                val sig = ByteArray(64)
                buffer.get(sig)
                authorPublicKey = pubKey
                signature = sig
            }

            changes.add(
                ChangeRecord(
                    id = id,
                    collection = collection,
                    type = type,
                    payload = payload,
                    hlcTimestamp = hlcTimestamp,
                    authorPublicKey = authorPublicKey,
                    signature = signature
                )
            )
        }

        return changes
    }
}
