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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe reference in-memory database adapter for testing and simulation.
 */
class MemoryDbAdapter : AirDbAdapter {

    // Storage for live entity states: collection -> (id -> ChangeRecord)
    private val tables = ConcurrentHashMap<String, ConcurrentHashMap<String, ChangeRecord>>()

    // Append-only changelog sorted by insertion / HLC
    private val changelog = CopyOnWriteArrayList<ChangeRecord>()

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        if (sinceHlcTimestamp == null) {
            return changelog.take(limit)
        }
        return changelog
            .filter { HlcComparator.compare(it.hlcTimestamp, sinceHlcTimestamp) > 0 }
            .take(limit)
    }

    override suspend fun getMissingChanges(peerClock: Map<String, String>, limit: Int): List<ChangeRecord> {
        return changelog.filter { record ->
            val origin = HlcComparator.extractNodeId(record.hlcTimestamp) ?: ""
            val peerKnown = peerClock[origin]
            peerKnown == null || HlcComparator.compare(record.hlcTimestamp, peerKnown) > 0
        }.take(limit)
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        for (change in changes) {
            val table = tables.getOrPut(change.collection) { ConcurrentHashMap() }
            val existing = table[change.id]

            // Last-Write-Wins (LWW) check based on canonical HLC total ordering
            if (existing == null || HlcComparator.compare(change.hlcTimestamp, existing.hlcTimestamp) > 0) {
                table[change.id] = change
                changelog.add(change)
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return changelog.maxWithOrNull(Comparator.comparing({ it.hlcTimestamp }, HlcComparator))?.hlcTimestamp
    }

    /**
     * Helper to read a record directly from in-memory storage.
     */
    fun getRecord(collection: String, id: String): ChangeRecord? {
        return tables[collection]?.get(id)
    }

    /**
     * Helper to get all current records (including tombstones) in a collection.
     */
    fun getAllRecords(collection: String): Map<String, ChangeRecord> {
        return tables[collection]?.toMap() ?: emptyMap()
    }

    /**
     * Helper to get all active (non-deleted) records, filtering out tombstone records.
     */
    fun getActiveRecords(collection: String): Map<String, ChangeRecord> {
        return tables[collection]?.filterValues { it.type != MutationType.DELETE } ?: emptyMap()
    }

    /**
     * Checks if a record exists and is marked as deleted (tombstone).
     */
    fun isRecordDeleted(collection: String, id: String): Boolean {
        return tables[collection]?.get(id)?.type == MutationType.DELETE
    }
}
