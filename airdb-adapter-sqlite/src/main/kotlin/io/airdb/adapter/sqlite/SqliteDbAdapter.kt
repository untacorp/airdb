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

package io.airdb.adapter.sqlite

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
import java.sql.Connection
import java.sql.DriverManager

/**
 * Production-ready ACID SQLite database adapter for AirDB.
 *
 * Provides crash-resilient disk storage with Write-Ahead Logging (WAL), atomic batch transactions,
 * Last-Write-Wins (LWW) conflict resolution, and tombstone compaction.
 *
 * @param jdbcUrl The SQLite JDBC connection string (e.g. "jdbc:sqlite:/path/to/db.sqlite" or "jdbc:sqlite::memory:").
 */
class SqliteDbAdapter(
    val jdbcUrl: String
) : AbstractAirDbAdapter(), Closeable {

    companion object {
        /**
         * Creates an isolated in-memory SQLite database instance (useful for unit testing).
         */
        fun inMemory(): SqliteDbAdapter {
            return SqliteDbAdapter("jdbc:sqlite::memory:")
        }

        /**
         * Opens or creates a disk-backed SQLite database at the specified file path.
         */
        fun open(file: File): SqliteDbAdapter {
            file.parentFile?.mkdirs()
            return SqliteDbAdapter("jdbc:sqlite:${file.absolutePath}")
        }
    }

    private val connection: Connection = DriverManager.getConnection(jdbcUrl)
    private val mutex = Mutex()

    init {
        initializeDatabase()
    }

    private fun initializeDatabase() {
        connection.createStatement().use { stmt ->
            // Enable WAL mode and foreign key constraints for safety and speed
            if (!jdbcUrl.contains(":memory:")) {
                stmt.execute("PRAGMA journal_mode = WAL;")
            }
            stmt.execute("PRAGMA synchronous = NORMAL;")
            stmt.execute("PRAGMA foreign_keys = ON;")

            // Table 1: Live entity states with primary key on (collection, id)
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS airdb_entities (
                    collection TEXT NOT NULL,
                    id TEXT NOT NULL,
                    type INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    hlc_timestamp TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (collection, id)
                );
                """.trimIndent()
            )

            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_entities_hlc 
                ON airdb_entities (hlc_timestamp);
                """.trimIndent()
            )

            // Table 2: Append-only changelog sequence for delta synchronization
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS airdb_changelog (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    collection TEXT NOT NULL,
                    id TEXT NOT NULL,
                    type INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    hlc_timestamp TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
                """.trimIndent()
            )

            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_changelog_hlc 
                ON airdb_changelog (hlc_timestamp);
                """.trimIndent()
            )

            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_changelog_collection_id 
                ON airdb_changelog (collection, id);
                """.trimIndent()
            )
        }
    }

    override suspend fun readEntity(collection: String, id: String): StoredEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sql = "SELECT type, payload, hlc_timestamp, updated_at FROM airdb_entities WHERE collection = ? AND id = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, collection)
                stmt.setString(2, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val typeOrdinal = rs.getInt("type")
                        val type = MutationType.entries.getOrElse(typeOrdinal) { MutationType.INSERT }
                        val payload = rs.getBytes("payload") ?: ByteArray(0)
                        val hlcTimestamp = rs.getString("hlc_timestamp")
                        val updatedAt = rs.getLong("updated_at")

                        StoredEntity(
                            collection = collection,
                            id = id,
                            type = type,
                            payload = payload,
                            hlcTimestamp = hlcTimestamp,
                            updatedAt = updatedAt
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    override suspend fun writeEntity(entity: StoredEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sql = """
                INSERT OR REPLACE INTO airdb_entities 
                (collection, id, type, payload, hlc_timestamp, updated_at) 
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entity.collection)
                stmt.setString(2, entity.id)
                stmt.setInt(3, entity.type.ordinal)
                stmt.setBytes(4, entity.payload)
                stmt.setString(5, entity.hlcTimestamp)
                stmt.setLong(6, entity.updatedAt)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun readChangelogSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val changes = mutableListOf<ChangeRecord>()
            val sql = "SELECT collection, id, type, payload, hlc_timestamp FROM airdb_changelog ORDER BY seq ASC"
            connection.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val hlc = rs.getString("hlc_timestamp")
                        if (sinceHlcTimestamp == null || HlcComparator.compare(hlc, sinceHlcTimestamp) > 0) {
                            val collection = rs.getString("collection")
                            val id = rs.getString("id")
                            val typeOrdinal = rs.getInt("type")
                            val type = MutationType.entries.getOrElse(typeOrdinal) { MutationType.INSERT }
                            val payload = rs.getBytes("payload") ?: ByteArray(0)

                            changes.add(
                                ChangeRecord(
                                    id = id,
                                    collection = collection,
                                    type = type,
                                    payload = payload,
                                    hlcTimestamp = hlc
                                )
                            )
                            if (changes.size >= limit) break
                        }
                    }
                }
            }
            changes
        }
    }

    override suspend fun appendChangelog(record: ChangeRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sql = """
                INSERT INTO airdb_changelog 
                (collection, id, type, payload, hlc_timestamp, created_at) 
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, record.collection)
                stmt.setString(2, record.id)
                stmt.setInt(3, record.type.ordinal)
                stmt.setBytes(4, record.payload)
                stmt.setString(5, record.hlcTimestamp)
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prevAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val selectSql = "SELECT hlc_timestamp FROM airdb_entities WHERE collection = ? AND id = ?"
                val upsertSql = """
                    INSERT OR REPLACE INTO airdb_entities 
                    (collection, id, type, payload, hlc_timestamp, updated_at) 
                    VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
                val changelogSql = """
                    INSERT INTO airdb_changelog 
                    (collection, id, type, payload, hlc_timestamp, created_at) 
                    VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()

                connection.prepareStatement(selectSql).use { selectStmt ->
                    connection.prepareStatement(upsertSql).use { upsertStmt ->
                        connection.prepareStatement(changelogSql).use { changelogStmt ->
                            for (change in changes) {
                                selectStmt.setString(1, change.collection)
                                selectStmt.setString(2, change.id)
                                val rs = selectStmt.executeQuery()
                                val existingHlc = if (rs.next()) rs.getString("hlc_timestamp") else null
                                rs.close()

                                // LWW check based on canonical HLC total order
                                if (existingHlc == null || HlcComparator.compare(change.hlcTimestamp, existingHlc) > 0) {
                                    val now = System.currentTimeMillis()

                                    upsertStmt.setString(1, change.collection)
                                    upsertStmt.setString(2, change.id)
                                    upsertStmt.setInt(3, change.type.ordinal)
                                    upsertStmt.setBytes(4, change.payload)
                                    upsertStmt.setString(5, change.hlcTimestamp)
                                    upsertStmt.setLong(6, now)
                                    upsertStmt.executeUpdate()

                                    changelogStmt.setString(1, change.collection)
                                    changelogStmt.setString(2, change.id)
                                    changelogStmt.setInt(3, change.type.ordinal)
                                    changelogStmt.setBytes(4, change.payload)
                                    changelogStmt.setString(5, change.hlcTimestamp)
                                    changelogStmt.setLong(6, now)
                                    changelogStmt.executeUpdate()
                                }
                            }
                        }
                    }
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = prevAutoCommit
            }
        }
    }

    override suspend fun compact(pruneHlcThreshold: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val toDelete = mutableListOf<Pair<String, String>>()
            val selectSql = "SELECT collection, id, hlc_timestamp FROM airdb_entities WHERE type = ?"
            connection.prepareStatement(selectSql).use { selectStmt ->
                selectStmt.setInt(1, MutationType.DELETE.ordinal)
                selectStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val hlc = rs.getString("hlc_timestamp")
                        if (HlcComparator.compare(hlc, pruneHlcThreshold) < 0) {
                            toDelete.add(rs.getString("collection") to rs.getString("id"))
                        }
                    }
                }
            }

            if (toDelete.isNotEmpty()) {
                val deleteSql = "DELETE FROM airdb_entities WHERE collection = ? AND id = ?"
                connection.prepareStatement(deleteSql).use { deleteStmt ->
                    for ((collection, id) in toDelete) {
                        deleteStmt.setString(1, collection)
                        deleteStmt.setString(2, id)
                        deleteStmt.executeUpdate()
                    }
                }
            }

            val toDeleteChangelog = mutableListOf<String>()
            val selectChangelogSql = "SELECT hlc_timestamp FROM airdb_changelog WHERE type = ?"
            connection.prepareStatement(selectChangelogSql).use { selectStmt ->
                selectStmt.setInt(1, MutationType.DELETE.ordinal)
                selectStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val hlc = rs.getString("hlc_timestamp")
                        if (HlcComparator.compare(hlc, pruneHlcThreshold) < 0) {
                            toDeleteChangelog.add(hlc)
                        }
                    }
                }
            }

            if (toDeleteChangelog.isNotEmpty()) {
                val deleteChangelogSql = "DELETE FROM airdb_changelog WHERE type = ? AND hlc_timestamp = ?"
                connection.prepareStatement(deleteChangelogSql).use { deleteStmt ->
                    for (hlc in toDeleteChangelog) {
                        deleteStmt.setInt(1, MutationType.DELETE.ordinal)
                        deleteStmt.setString(2, hlc)
                        deleteStmt.executeUpdate()
                    }
                }
            }
        }
        Unit
    }

    /**
     * Reads an active entity directly from SQLite storage, returning null if missing or soft-deleted.
     */
    suspend fun getActiveRecord(collection: String, id: String): ChangeRecord? {
        val entity = readEntity(collection, id) ?: return null
        if (entity.type == MutationType.DELETE) return null
        return ChangeRecord(
            id = entity.id,
            collection = entity.collection,
            type = entity.type,
            payload = entity.payload,
            hlcTimestamp = entity.hlcTimestamp
        )
    }

    /**
     * Retrieves all active (non-deleted) records for the specified collection.
     */
    suspend fun getActiveRecords(collection: String): Map<String, ChangeRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val map = mutableMapOf<String, ChangeRecord>()
            val sql = "SELECT id, type, payload, hlc_timestamp FROM airdb_entities WHERE collection = ? AND type != ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, collection)
                stmt.setInt(2, MutationType.DELETE.ordinal)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val typeOrdinal = rs.getInt("type")
                        val type = MutationType.entries.getOrElse(typeOrdinal) { MutationType.INSERT }
                        val payload = rs.getBytes("payload") ?: ByteArray(0)
                        val hlc = rs.getString("hlc_timestamp")
                        map[id] = ChangeRecord(id, collection, type, payload, hlc)
                    }
                }
            }
            map
        }
    }

    /**
     * Checks if a record exists in SQLite and is marked as deleted (tombstone).
     */
    suspend fun isRecordDeleted(collection: String, id: String): Boolean {
        val entity = readEntity(collection, id) ?: return false
        return entity.type == MutationType.DELETE
    }

    override fun close() {
        if (!connection.isClosed) {
            connection.close()
        }
    }
}
