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

package io.airdb.adapter.aerolog

import io.aerolog.uniffi.FfiAeroLogDb
import io.aerolog.uniffi.FfiStream
import io.airdb.adapter.AbstractAirDbAdapter
import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.HlcComparator
import io.airdb.adapter.MutationType
import io.airdb.adapter.StoredEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * Production-ready high-performance AirDB adapter backed by AeroLog native Rust engine.
 *
 * Features Bitcask-inspired sequential append-only log segments, Zero-GC execution,
 * hardware CRC32 checksums, memory-bounded sparse indexing, and native Hybrid Logical Clock (HLC).
 *
 * @param dbPath Absolute file directory path where AeroLog segment files (.aerolog) will reside.
 * @param nodeId Unique originating node identifier string used for HLC causality tie-breaking.
 */
class AeroLogAirDbAdapter(
    val dbPath: String,
    val nodeId: String = "node-1"
) : AbstractAirDbAdapter(), AutoCloseable, Closeable {

    companion object {
        private const val CHANGELOG_STREAM = "__airdb_changelog__"

        /**
         * Opens or creates an AeroLog-backed AirDB database in the specified directory.
         */
        fun open(directory: File, nodeId: String = "node-1"): AeroLogAirDbAdapter {
            directory.mkdirs()
            return AeroLogAirDbAdapter(directory.absolutePath, nodeId)
        }

        private fun hashNodeId(nodeId: String): ULong {
            var hash = 0xcbf29ce484222325UL // FNV-1a 64-bit offset basis
            for (byte in nodeId.toByteArray(Charsets.UTF_8)) {
                hash = hash xor byte.toUByte().toULong()
                hash = hash * 0x100000001b3UL // FNV-1a 64-bit prime
            }
            return if (hash == 0UL) 1UL else hash
        }
    }

    private val db: FfiAeroLogDb
    private val changelogStream: FfiStream
    private val mutex = Mutex()

    init {
        val nodeHash = hashNodeId(nodeId)
        db = FfiAeroLogDb.open(dbPath, nodeHash)
        changelogStream = db.getStream(CHANGELOG_STREAM)
    }

    override suspend fun readEntity(collection: String, id: String): StoredEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = db.get(collection, id) ?: return@withContext null
            deserializeStoredEntity(collection, id, bytes)
        }
    }

    override suspend fun writeEntity(entity: StoredEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = serializeStoredEntity(entity)
            db.put(entity.collection, entity.id, bytes)
        }
        Unit
    }

    override suspend fun readChangelogSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val total = changelogStream.totalEntries()
            if (total == 0UL || limit <= 0) {
                return@withContext emptyList()
            }

            // Read the full stream chronologically so historical range queries
            // and pagination starting from null or older HLCs do not skip entries.
            val fetchCount = total.coerceAtMost(Int.MAX_VALUE.toULong()).toUInt()
            val entries = changelogStream.readRecent(fetchCount)
            val result = ArrayList<ChangeRecord>(limit.coerceAtMost(entries.size))

            for (entry in entries) {
                val change = deserializeChangeRecord(entry.payload)
                if (sinceHlcTimestamp == null || HlcComparator.compare(change.hlcTimestamp, sinceHlcTimestamp) > 0) {
                    result.add(change)
                    if (result.size >= limit) {
                        break
                    }
                }
            }

            result
        }
    }

    override suspend fun appendChangelog(record: ChangeRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = serializeChangeRecord(record)
            changelogStream.append(bytes)
        }
        Unit
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (change in changes) {
                val existingBytes = db.get(change.collection, change.id)
                val existing = if (existingBytes != null) {
                    deserializeStoredEntity(change.collection, change.id, existingBytes)
                } else null

                if (existing == null || HlcComparator.compare(change.hlcTimestamp, existing.hlcTimestamp) > 0) {
                    val entity = StoredEntity(
                        collection = change.collection,
                        id = change.id,
                        type = change.type,
                        payload = change.payload,
                        hlcTimestamp = change.hlcTimestamp
                    )
                    db.put(entity.collection, entity.id, serializeStoredEntity(entity))
                    changelogStream.append(serializeChangeRecord(change))
                }
            }
        }
    }

    /**
     * Reads a live active entity record, returning null if not found or marked deleted (tombstone).
     */
    suspend fun getActiveRecord(collection: String, id: String): StoredEntity? {
        val entity = readEntity(collection, id) ?: return null
        return if (entity.type == MutationType.DELETE) null else entity
    }

    /**
     * Scans and returns all live, non-deleted records in the given collection.
     */
    suspend fun getActiveRecords(collection: String): List<StoredEntity> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pairs = db.scanCollection(collection)
            pairs.mapNotNull { pair ->
                val entity = deserializeStoredEntity(collection, pair.key, pair.value)
                if (entity.type == MutationType.DELETE) null else entity
            }
        }
    }

    /**
     * Returns true if the entity has been explicitly tombstoned (soft-deleted).
     */
    suspend fun isRecordDeleted(collection: String, id: String): Boolean {
        val entity = readEntity(collection, id) ?: return false
        return entity.type == MutationType.DELETE
    }

    /**
     * Triggers background Bitcask compaction to reclaim dead records and consolidate segments.
     */
    override suspend fun compact(pruneHlcThreshold: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.compact()
        }
        Unit
    }

    /**
     * Flushes buffered writes to disk.
     */
    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.flush()
        }
    }

    override fun close() {
        try {
            changelogStream.close()
        } catch (_: Exception) {}
        try {
            db.close()
        } catch (_: Exception) {}
    }

    // Binary serialization helpers

    private fun serializeStoredEntity(entity: StoredEntity): ByteArray {
        val hlcBytes = entity.hlcTimestamp.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 8 + 2 + hlcBytes.size + 4 + entity.payload.size)
        buffer.put(entity.type.ordinal.toByte())
        buffer.putLong(entity.updatedAt)
        buffer.putShort(hlcBytes.size.toShort())
        buffer.put(hlcBytes)
        buffer.putInt(entity.payload.size)
        buffer.put(entity.payload)
        return buffer.array()
    }

    private fun deserializeStoredEntity(collection: String, id: String, bytes: ByteArray): StoredEntity {
        val buffer = ByteBuffer.wrap(bytes)
        val typeOrdinal = buffer.get().toInt()
        val type = MutationType.entries.getOrElse(typeOrdinal) { MutationType.INSERT }
        val updatedAt = buffer.getLong()
        val hlcLen = buffer.getShort().toInt()
        val hlcBytes = ByteArray(hlcLen)
        buffer.get(hlcBytes)
        val hlcTimestamp = String(hlcBytes, Charsets.UTF_8)
        val payloadLen = buffer.getInt()
        val payload = ByteArray(payloadLen)
        buffer.get(payload)
        return StoredEntity(collection, id, type, payload, hlcTimestamp, updatedAt)
    }

    private fun serializeChangeRecord(record: ChangeRecord): ByteArray {
        val idBytes = record.id.toByteArray(Charsets.UTF_8)
        val colBytes = record.collection.toByteArray(Charsets.UTF_8)
        val hlcBytes = record.hlcTimestamp.toByteArray(Charsets.UTF_8)
        val authKey = record.authorPublicKey
        val sig = record.signature

        val totalSize = 2 + idBytes.size +
                2 + colBytes.size +
                1 + // type
                2 + hlcBytes.size +
                4 + record.payload.size +
                2 + (authKey?.size ?: 0) +
                2 + (sig?.size ?: 0)

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putShort(idBytes.size.toShort())
        buffer.put(idBytes)
        buffer.putShort(colBytes.size.toShort())
        buffer.put(colBytes)
        buffer.put(record.type.ordinal.toByte())
        buffer.putShort(hlcBytes.size.toShort())
        buffer.put(hlcBytes)
        buffer.putInt(record.payload.size)
        buffer.put(record.payload)

        if (authKey != null) {
            buffer.putShort(authKey.size.toShort())
            buffer.put(authKey)
        } else {
            buffer.putShort(0.toShort())
        }

        if (sig != null) {
            buffer.putShort(sig.size.toShort())
            buffer.put(sig)
        } else {
            buffer.putShort(0.toShort())
        }

        return buffer.array()
    }

    private fun deserializeChangeRecord(bytes: ByteArray): ChangeRecord {
        val buffer = ByteBuffer.wrap(bytes)
        val idLen = buffer.getShort().toInt()
        val idBytes = ByteArray(idLen)
        buffer.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8)

        val colLen = buffer.getShort().toInt()
        val colBytes = ByteArray(colLen)
        buffer.get(colBytes)
        val collection = String(colBytes, Charsets.UTF_8)

        val typeOrdinal = buffer.get().toInt()
        val type = MutationType.entries.getOrElse(typeOrdinal) { MutationType.INSERT }

        val hlcLen = buffer.getShort().toInt()
        val hlcBytes = ByteArray(hlcLen)
        buffer.get(hlcBytes)
        val hlcTimestamp = String(hlcBytes, Charsets.UTF_8)

        val payloadLen = buffer.getInt()
        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        val authKeyLen = buffer.getShort().toInt()
        val authorPublicKey = if (authKeyLen > 0) {
            val keyBytes = ByteArray(authKeyLen)
            buffer.get(keyBytes)
            keyBytes
        } else null

        val sigLen = buffer.getShort().toInt()
        val signature = if (sigLen > 0) {
            val sigBytes = ByteArray(sigLen)
            buffer.get(sigBytes)
            sigBytes
        } else null

        return ChangeRecord(id, collection, type, payload, hlcTimestamp, authorPublicKey, signature)
    }
}
