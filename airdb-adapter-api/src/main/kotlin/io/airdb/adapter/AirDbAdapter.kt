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
 * Pluggable database seam interface.
 *
 * Any application-level database (Room, SQLite, Realm, ObjectBox, or in-memory stores)
 * implements this interface so AirDB can read and apply mutations without knowing the internal
 * schema of tables.
 */
interface AirDbAdapter {

    /**
     * Retrieves mutations applied locally that have an HLC timestamp strictly greater than
     * [sinceHlcTimestamp], up to [limit] records.
     *
     * @param sinceHlcTimestamp Lower HLC boundary. If null, returns changes from the beginning.
     * @param limit Maximum number of records to return.
     * @return List of change records ordered monotonically by HLC timestamp ascending.
     */
    suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int = 100): List<ChangeRecord>

    /**
     * Applies a batch of incoming mutations to the local database.
     *
     * Implementations MUST be idempotent: if a mutation with an older or equal HLC timestamp
     * has already been applied for the same entity ID, it should be ignored or resolved via
     * Last-Write-Wins (LWW).
     *
     * @param changes List of mutations received from remote peers.
     */
    suspend fun applyChanges(changes: List<ChangeRecord>)

    /**
     * Returns the highest HLC timestamp currently stored in the local database, or null if empty.
     */
    suspend fun getLatestHlc(): String?
}
