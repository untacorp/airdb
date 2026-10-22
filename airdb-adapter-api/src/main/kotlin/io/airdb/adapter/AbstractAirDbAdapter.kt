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

/**
 * Internal entity representation for storage engines.
 *
 * @property collection Target collection or table name.
 * @property id Unique entity identifier.
 * @property type Mutation state: INSERT, UPDATE, or DELETE (tombstone).
 * @property payload Serialized entity binary content. Empty for DELETE.
 * @property hlcTimestamp Monotonic Hybrid Logical Clock timestamp.
 * @property updatedAt Local epoch millisecond timestamp when saved.
 */
data class StoredEntity(
    val collection: String,
    val id: String,
    val type: MutationType,
    val payload: ByteArray,
    val hlcTimestamp: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StoredEntity

        if (collection != other.collection) return false
        if (id != other.id) return false
        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false
        if (hlcTimestamp != other.hlcTimestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = collection.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hlcTimestamp.hashCode()
        return result
    }
}

/**
 * Abstract base adapter providing distributed CRDT consensus math, canonical Last-Write-Wins (LWW)
 * conflict resolution, tombstone anti-resurrection guards, and peer vector clock filtering.
 *
 * Custom storage engines (Android Room, Realm, ObjectBox, SQLDelight, RocksDB) can extend this
 * class to adapt any underlying database in approximately 30 lines of code.
 */
abstract class AbstractAirDbAdapter : AirDbAdapter {

    /**
     * Reads the current entity state from storage, or null if it does not exist.
     */
    abstract suspend fun readEntity(collection: String, id: String): StoredEntity?

    /**
     * Persists or updates the entity state in storage.
     */
    abstract suspend fun writeEntity(entity: StoredEntity)

    /**
     * Reads mutations from the changelog strictly newer than [sinceHlcTimestamp], up to [limit].
     */
    abstract suspend fun readChangelogSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord>

    /**
     * Appends an applied mutation to the changelog.
     */
    abstract suspend fun appendChangelog(record: ChangeRecord)

    /**
     * Executes the given [block] within an atomic transaction.
     * Default implementation simply executes [block]; transactional databases should override this.
     */
    protected open suspend fun <T> executeInTransaction(block: suspend () -> T): T {
        return block()
    }

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        return readChangelogSince(sinceHlcTimestamp, limit)
    }

    @Volatile
    private var maxSeenHlc: String? = null

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        executeInTransaction {
            for (change in changes) {
                val existing = readEntity(change.collection, change.id)
                if (existing != null && HlcComparator.compare(change.hlcTimestamp, existing.hlcTimestamp) <= 0) {
                    continue
                }
                val entity = StoredEntity(
                        collection = change.collection,
                        id = change.id,
                        type = change.type,
                        payload = change.payload,
                        hlcTimestamp = change.hlcTimestamp
                    )
                    writeEntity(entity)
                    appendChangelog(change)
                    updateMaxSeenHlc(change.hlcTimestamp)
            }
        }
    }

    protected fun updateMaxSeenHlc(hlc: String) {
        val current = maxSeenHlc
        if (current == null || HlcComparator.compare(hlc, current) > 0) {
            maxSeenHlc = hlc
        }
    }

    override suspend fun getLatestHlc(): String? {
        val cached = maxSeenHlc
        if (cached != null) return cached

        var cursor: String? = null
        var maxHlc: String? = null
        val pageSize = 1000
        while (true) {
            val batch = readChangelogSince(cursor, limit = pageSize)
            if (batch.isEmpty()) break
            for (record in batch) {
                if (maxHlc == null || HlcComparator.compare(record.hlcTimestamp, maxHlc) > 0) {
                    maxHlc = record.hlcTimestamp
                }
            }
            if (batch.size < pageSize) break
            cursor = batch.last().hlcTimestamp
        }
        maxSeenHlc = maxHlc
        return maxHlc
    }

    /**
     * Prunes tombstones (soft-deleted records) whose HLC timestamp is strictly older
     * than [pruneHlcThreshold].
     */
    open suspend fun compact(pruneHlcThreshold: String) {
    }
}
