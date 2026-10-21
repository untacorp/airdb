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

package io.airdb.adapter

import java.util.Arrays

/**
 * Granular unit of mutation tracked across peer nodes.
 *
 * @property id Unique identifier of the entity or row (e.g. UUID, primary key).
 * @property collection Name of the target table, collection, or entity type.
 * @property type Type of mutation: INSERT, UPDATE, or DELETE.
 * @property payload Serialized representation of the entity (JSON, Protobuf, or raw bytes). Empty for DELETE.
 * @property hlcTimestamp Hybrid Logical Clock timestamp string representing causality (e.g. "1710928300000-0-nodeA").
 * @property authorPublicKey Optional 32-byte Ed25519 public key of the node that authored this change.
 * @property signature Optional 64-byte Ed25519 digital signature verifying the change's provenance.
 */
data class ChangeRecord(
    val id: String,
    val collection: String,
    val type: MutationType,
    val payload: ByteArray,
    val hlcTimestamp: String,
    val authorPublicKey: ByteArray? = null,
    val signature: ByteArray? = null
) {
    val isSigned: Boolean get() = authorPublicKey != null && signature != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChangeRecord

        if (id != other.id) return false
        if (collection != other.collection) return false
        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false
        if (hlcTimestamp != other.hlcTimestamp) return false
        if (!Arrays.equals(authorPublicKey, other.authorPublicKey)) return false
        if (!Arrays.equals(signature, other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + collection.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hlcTimestamp.hashCode()
        result = 31 * result + (authorPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ChangeRecord(id='$id', collection='$collection', type=$type, payloadSize=${payload.size}, hlc='$hlcTimestamp', isSigned=$isSigned)"
    }
}
