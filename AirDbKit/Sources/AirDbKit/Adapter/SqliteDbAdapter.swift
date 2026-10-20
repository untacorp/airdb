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

import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Errors thrown by SQLite storage engine operations.
public enum SqliteError: Error, LocalizedError {
    case connectionFailed(String)
    case databaseClosed
    case prepareFailed(String)
    case stepFailed(String)
    case queryFailed(String)

    public var errorDescription: String? {
        switch self {
        case .connectionFailed(let msg): return "SQLite connection error: \(msg)"
        case .databaseClosed: return "SQLite database is closed"
        case .prepareFailed(let msg): return "SQLite statement preparation error: \(msg)"
        case .stepFailed(let msg): return "SQLite statement step error: \(msg)"
        case .queryFailed(let msg): return "SQLite query error: \(msg)"
        }
    }
}

/// Production-ready ACID SQLite database adapter for Apple platforms (iOS, macOS, watchOS, tvOS).
///
/// Provides crash-resilient disk persistence with Write-Ahead Logging (WAL), atomic batch transactions,
/// Last-Write-Wins (LWW) conflict resolution, and tombstone compaction without any third-party dependencies.
public actor SqliteDbAdapter: AirDbAdapter, AdaptiveStorageContract {
    private var db: OpaquePointer?

    /// Creates an isolated in-memory SQLite database instance (ideal for unit testing).
    public static func inMemory() throws -> SqliteDbAdapter {
        try SqliteDbAdapter(path: ":memory:")
    }

    /// Opens or creates a disk-backed SQLite database at the specified file path.
    public static func open(path: String) throws -> SqliteDbAdapter {
        try SqliteDbAdapter(path: path)
    }

    /// Opens or creates a disk-backed SQLite database at the specified URL.
    public static func open(url: URL) throws -> SqliteDbAdapter {
        try SqliteDbAdapter(path: url.path)
    }

    /// Initializes a SQLite connection at the specified file path or `:memory:`.
    public init(path: String) throws {
        if path != ":memory:" && !path.contains("mode=memory") {
            let dir = (path as NSString).deletingLastPathComponent
            if !dir.isEmpty && dir != "." {
                try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            }
        }

        var dbPointer: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let status = sqlite3_open_v2(path, &dbPointer, flags, nil)
        guard status == SQLITE_OK, let validDb = dbPointer else {
            let errMsg = dbPointer.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "Failed to open SQLite database at \(path)"
            if let dbPointer = dbPointer {
                sqlite3_close(dbPointer)
            }
            throw SqliteError.connectionFailed(errMsg)
        }

        self.db = validDb
        try Self.initializeDatabase(db: validDb, isMemory: path == ":memory:" || path.contains("mode=memory"))
    }

    private static func initializeDatabase(db: OpaquePointer, isMemory: Bool) throws {
        if !isMemory {
            try executeSimple(db: db, sql: "PRAGMA journal_mode = WAL;")
        }
        try executeSimple(db: db, sql: "PRAGMA synchronous = NORMAL;")
        try executeSimple(db: db, sql: "PRAGMA foreign_keys = ON;")

        let createEntities = """
        CREATE TABLE IF NOT EXISTS airdb_entities (
            collection TEXT NOT NULL,
            id TEXT NOT NULL,
            type INTEGER NOT NULL,
            payload BLOB NOT NULL,
            hlc_timestamp TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (collection, id)
        );
        """
        try executeSimple(db: db, sql: createEntities)

        let createEntitiesIdx = """
        CREATE INDEX IF NOT EXISTS idx_entities_hlc 
        ON airdb_entities (hlc_timestamp);
        """
        try executeSimple(db: db, sql: createEntitiesIdx)

        let createChangelog = """
        CREATE TABLE IF NOT EXISTS airdb_changelog (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            collection TEXT NOT NULL,
            id TEXT NOT NULL,
            type INTEGER NOT NULL,
            payload BLOB NOT NULL,
            hlc_timestamp TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        """
        try executeSimple(db: db, sql: createChangelog)

        let createChangelogIdxHlc = """
        CREATE INDEX IF NOT EXISTS idx_changelog_hlc 
        ON airdb_changelog (hlc_timestamp);
        """
        try executeSimple(db: db, sql: createChangelogIdxHlc)

        let createChangelogIdxColId = """
        CREATE INDEX IF NOT EXISTS idx_changelog_collection_id 
        ON airdb_changelog (collection, id);
        """
        try executeSimple(db: db, sql: createChangelogIdxColId)
    }

    private func executeSimple(_ sql: String) throws {
        guard let db = db else { throw SqliteError.databaseClosed }
        try Self.executeSimple(db: db, sql: sql)
    }

    private static func executeSimple(db: OpaquePointer, sql: String) throws {
        var errMsg: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &errMsg) != SQLITE_OK {
            let msg = errMsg.flatMap { String(cString: $0) } ?? "Unknown SQLite error"
            sqlite3_free(errMsg)
            throw SqliteError.queryFailed(msg)
        }
    }

    // MARK: - AdaptiveStorageContract Implementation

    public func readEntity(collection: String, id: String) throws -> StoredEntity? {
        guard let db = db else { throw SqliteError.databaseClosed }
        let sql = "SELECT type, payload, hlc_timestamp, updated_at FROM airdb_entities WHERE collection = ? AND id = ?"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        sqlite3_bind_text(stmt, 1, collection, -1, sqliteTransient)
        sqlite3_bind_text(stmt, 2, id, -1, sqliteTransient)

        if sqlite3_step(stmt) == SQLITE_ROW {
            let rawType = UInt8(sqlite3_column_int(stmt, 0))
            let type = MutationType(rawValue: rawType) ?? .insert

            let payloadData: Data
            if let blobPointer = sqlite3_column_blob(stmt, 1) {
                let bytes = sqlite3_column_bytes(stmt, 1)
                payloadData = Data(bytes: blobPointer, count: Int(bytes))
            } else {
                payloadData = Data()
            }

            let hlcTimestamp = sqlite3_column_text(stmt, 2).flatMap { String(cString: $0) } ?? ""
            let updatedAt = sqlite3_column_int64(stmt, 3)

            return StoredEntity(
                collection: collection,
                id: id,
                type: type,
                payload: payloadData,
                hlcTimestamp: hlcTimestamp,
                updatedAt: updatedAt
            )
        }
        return nil
    }

    public func writeEntity(_ entity: StoredEntity) throws {
        guard let db = db else { throw SqliteError.databaseClosed }
        let sql = """
        INSERT OR REPLACE INTO airdb_entities 
        (collection, id, type, payload, hlc_timestamp, updated_at) 
        VALUES (?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        sqlite3_bind_text(stmt, 1, entity.collection, -1, sqliteTransient)
        sqlite3_bind_text(stmt, 2, entity.id, -1, sqliteTransient)
        sqlite3_bind_int(stmt, 3, Int32(entity.type.rawValue))
        if entity.payload.isEmpty {
            sqlite3_bind_zeroblob(stmt, 4, 0)
        } else {
            entity.payload.withUnsafeBytes { rawBuffer in
                sqlite3_bind_blob(stmt, 4, rawBuffer.baseAddress, Int32(entity.payload.count), sqliteTransient)
            }
        }
        sqlite3_bind_text(stmt, 5, entity.hlcTimestamp, -1, sqliteTransient)
        sqlite3_bind_int64(stmt, 6, entity.updatedAt)

        if sqlite3_step(stmt) != SQLITE_DONE {
            throw SqliteError.stepFailed(String(cString: sqlite3_errmsg(db)))
        }
    }

    public func readChangelogSince(sinceHlcTimestamp: String?, limit: Int = 100) throws -> [ChangeRecord] {
        guard let db = db else { throw SqliteError.databaseClosed }
        let sql = "SELECT collection, id, type, payload, hlc_timestamp FROM airdb_changelog ORDER BY seq ASC"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        var records: [ChangeRecord] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            // Strict left-to-right column extraction (0, 1, 2, 3, 4)
            let collection = sqlite3_column_text(stmt, 0).flatMap { String(cString: $0) } ?? ""
            let id = sqlite3_column_text(stmt, 1).flatMap { String(cString: $0) } ?? ""
            let rawType = UInt8(sqlite3_column_int(stmt, 2))
            let type = MutationType(rawValue: rawType) ?? .insert

            let payloadData: Data
            if let blobPointer = sqlite3_column_blob(stmt, 3) {
                let bytes = sqlite3_column_bytes(stmt, 3)
                payloadData = Data(bytes: blobPointer, count: Int(bytes))
            } else {
                payloadData = Data()
            }

            let hlc = sqlite3_column_text(stmt, 4).flatMap { String(cString: $0) } ?? ""

            if let since = sinceHlcTimestamp {
                if HlcComparator.compare(hlc, since) != .orderedDescending {
                    continue
                }
            }

            records.append(
                ChangeRecord(
                    id: id,
                    collection: collection,
                    type: type,
                    payload: payloadData,
                    hlcTimestamp: hlc
                )
            )

            if records.count >= limit {
                break
            }
        }
        return records
    }

    public func appendChangelog(_ record: ChangeRecord) throws {
        guard let db = db else { throw SqliteError.databaseClosed }
        let sql = """
        INSERT INTO airdb_changelog 
        (collection, id, type, payload, hlc_timestamp, created_at) 
        VALUES (?, ?, ?, ?, ?, ?)
        """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        sqlite3_bind_text(stmt, 1, record.collection, -1, sqliteTransient)
        sqlite3_bind_text(stmt, 2, record.id, -1, sqliteTransient)
        sqlite3_bind_int(stmt, 3, Int32(record.type.rawValue))
        if record.payload.isEmpty {
            sqlite3_bind_zeroblob(stmt, 4, 0)
        } else {
            record.payload.withUnsafeBytes { rawBuffer in
                sqlite3_bind_blob(stmt, 4, rawBuffer.baseAddress, Int32(record.payload.count), sqliteTransient)
            }
        }
        sqlite3_bind_text(stmt, 5, record.hlcTimestamp, -1, sqliteTransient)
        sqlite3_bind_int64(stmt, 6, Int64(Date().timeIntervalSince1970 * 1000))

        if sqlite3_step(stmt) != SQLITE_DONE {
            throw SqliteError.stepFailed(String(cString: sqlite3_errmsg(db)))
        }
    }

    public func compact(pruneHlcThreshold: String) throws {
        guard let db = db else { throw SqliteError.databaseClosed }

        // Select soft-deleted tombstones to evaluate against canonical HlcComparator total order
        let selectSql = "SELECT collection, id, hlc_timestamp FROM airdb_entities WHERE type = ?"
        var selectStmt: OpaquePointer?

        if sqlite3_prepare_v2(db, selectSql, -1, &selectStmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        sqlite3_bind_int(selectStmt, 1, Int32(MutationType.delete.rawValue))

        var toDelete: [(collection: String, id: String)] = []
        while sqlite3_step(selectStmt) == SQLITE_ROW {
            // Strict left-to-right extraction (0, 1, 2) to maintain SQLite row buffer integrity
            let collection = sqlite3_column_text(selectStmt, 0).flatMap { String(cString: $0) } ?? ""
            let id = sqlite3_column_text(selectStmt, 1).flatMap { String(cString: $0) } ?? ""
            let hlc = sqlite3_column_text(selectStmt, 2).flatMap { String(cString: $0) } ?? ""

            if HlcComparator.compare(hlc, pruneHlcThreshold) == .orderedAscending {
                toDelete.append((collection, id))
            }
        }
        // Explicitly finalize SELECT cursor before executing mutations to release table lock
        sqlite3_finalize(selectStmt)

        // Physically delete tombstones past the threshold by primary key within an atomic transaction
        if !toDelete.isEmpty {
            try executeSimple("BEGIN IMMEDIATE TRANSACTION;")
            do {
                let deleteSql = "DELETE FROM airdb_entities WHERE collection = ? AND id = ?"
                var deleteStmt: OpaquePointer?
                defer { sqlite3_finalize(deleteStmt) }

                if sqlite3_prepare_v2(db, deleteSql, -1, &deleteStmt, nil) != SQLITE_OK {
                    throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
                }

                for item in toDelete {
                    sqlite3_reset(deleteStmt)
                    sqlite3_clear_bindings(deleteStmt)
                    sqlite3_bind_text(deleteStmt, 1, item.collection, -1, sqliteTransient)
                    sqlite3_bind_text(deleteStmt, 2, item.id, -1, sqliteTransient)
                    if sqlite3_step(deleteStmt) != SQLITE_DONE {
                        throw SqliteError.stepFailed(String(cString: sqlite3_errmsg(db)))
                    }
                }
                try executeSimple("COMMIT TRANSACTION;")
            } catch {
                try? executeSimple("ROLLBACK TRANSACTION;")
                throw error
            }
        }
    }

    // MARK: - AirDbAdapter Implementation

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int = 100) throws -> [ChangeRecord] {
        try readChangelogSince(sinceHlcTimestamp: sinceHlcTimestamp, limit: limit)
    }

    public func getMissingChanges(peerClock: [String: String], limit: Int = 500) throws -> [ChangeRecord] {
        let candidates = try readChangelogSince(sinceHlcTimestamp: nil, limit: limit * 2)
        return candidates.filter { record in
            let origin = HlcComparator.extractNodeId(from: record.hlcTimestamp) ?? ""
            guard let peerKnown = peerClock[origin] else { return true }
            return HlcComparator.compare(record.hlcTimestamp, peerKnown) == .orderedDescending
        }.prefix(limit).map { $0 }
    }

    public func applyChanges(_ changes: [ChangeRecord]) throws {
        guard !changes.isEmpty else { return }
        guard let db = db else { throw SqliteError.databaseClosed }

        try executeSimple("BEGIN IMMEDIATE TRANSACTION;")
        do {
            let selectSql = "SELECT hlc_timestamp FROM airdb_entities WHERE collection = ? AND id = ?"
            let upsertSql = """
            INSERT OR REPLACE INTO airdb_entities 
            (collection, id, type, payload, hlc_timestamp, updated_at) 
            VALUES (?, ?, ?, ?, ?, ?)
            """
            let changelogSql = """
            INSERT INTO airdb_changelog 
            (collection, id, type, payload, hlc_timestamp, created_at) 
            VALUES (?, ?, ?, ?, ?, ?)
            """

            var selectStmt: OpaquePointer?
            var upsertStmt: OpaquePointer?
            var changelogStmt: OpaquePointer?

            defer {
                sqlite3_finalize(selectStmt)
                sqlite3_finalize(upsertStmt)
                sqlite3_finalize(changelogStmt)
            }

            if sqlite3_prepare_v2(db, selectSql, -1, &selectStmt, nil) != SQLITE_OK {
                throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
            }
            if sqlite3_prepare_v2(db, upsertSql, -1, &upsertStmt, nil) != SQLITE_OK {
                throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
            }
            if sqlite3_prepare_v2(db, changelogSql, -1, &changelogStmt, nil) != SQLITE_OK {
                throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
            }

            let now = Int64(Date().timeIntervalSince1970 * 1000)

            for change in changes {
                sqlite3_reset(selectStmt)
                sqlite3_clear_bindings(selectStmt)
                sqlite3_bind_text(selectStmt, 1, change.collection, -1, sqliteTransient)
                sqlite3_bind_text(selectStmt, 2, change.id, -1, sqliteTransient)

                var existingHlc: String?
                if sqlite3_step(selectStmt) == SQLITE_ROW {
                    existingHlc = sqlite3_column_text(selectStmt, 0).flatMap { String(cString: $0) }
                }

                // Last-Write-Wins (LWW) check: ignore strictly older or identical mutations
                if let existing = existingHlc {
                    if HlcComparator.compare(change.hlcTimestamp, existing) != .orderedDescending {
                        continue
                    }
                }

                let typeInt = Int32(change.type.rawValue)

                // 1. Write entity state
                sqlite3_reset(upsertStmt)
                sqlite3_clear_bindings(upsertStmt)
                sqlite3_bind_text(upsertStmt, 1, change.collection, -1, sqliteTransient)
                sqlite3_bind_text(upsertStmt, 2, change.id, -1, sqliteTransient)
                sqlite3_bind_int(upsertStmt, 3, typeInt)
                if change.payload.isEmpty {
                    sqlite3_bind_zeroblob(upsertStmt, 4, 0)
                } else {
                    change.payload.withUnsafeBytes { rawBuffer in
                        sqlite3_bind_blob(upsertStmt, 4, rawBuffer.baseAddress, Int32(change.payload.count), sqliteTransient)
                    }
                }
                sqlite3_bind_text(upsertStmt, 5, change.hlcTimestamp, -1, sqliteTransient)
                sqlite3_bind_int64(upsertStmt, 6, now)

                if sqlite3_step(upsertStmt) != SQLITE_DONE {
                    throw SqliteError.stepFailed(String(cString: sqlite3_errmsg(db)))
                }

                // 2. Append to changelog
                sqlite3_reset(changelogStmt)
                sqlite3_clear_bindings(changelogStmt)
                sqlite3_bind_text(changelogStmt, 1, change.collection, -1, sqliteTransient)
                sqlite3_bind_text(changelogStmt, 2, change.id, -1, sqliteTransient)
                sqlite3_bind_int(changelogStmt, 3, typeInt)
                if change.payload.isEmpty {
                    sqlite3_bind_zeroblob(changelogStmt, 4, 0)
                } else {
                    change.payload.withUnsafeBytes { rawBuffer in
                        sqlite3_bind_blob(changelogStmt, 4, rawBuffer.baseAddress, Int32(change.payload.count), sqliteTransient)
                    }
                }
                sqlite3_bind_text(changelogStmt, 5, change.hlcTimestamp, -1, sqliteTransient)
                sqlite3_bind_int64(changelogStmt, 6, now)

                if sqlite3_step(changelogStmt) != SQLITE_DONE {
                    throw SqliteError.stepFailed(String(cString: sqlite3_errmsg(db)))
                }
            }

            try executeSimple("COMMIT TRANSACTION;")
        } catch {
            try? executeSimple("ROLLBACK TRANSACTION;")
            throw error
        }
    }

    public func getLatestHlc() throws -> String? {
        guard let db = db else { throw SqliteError.databaseClosed }
        let sql = "SELECT hlc_timestamp FROM airdb_changelog ORDER BY seq DESC LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        if sqlite3_step(stmt) == SQLITE_ROW {
            return sqlite3_column_text(stmt, 0).flatMap { String(cString: $0) }
        }
        return nil
    }

    // MARK: - Query & Convenience API

    /// Reads an active entity directly from SQLite, returning nil if missing or soft-deleted.
    public func getActiveRecord(collection: String, id: String) throws -> ChangeRecord? {
        guard let entity = try readEntity(collection: collection, id: id) else { return nil }
        if entity.type == .delete { return nil }
        return ChangeRecord(
            id: entity.id,
            collection: entity.collection,
            type: entity.type,
            payload: entity.payload,
            hlcTimestamp: entity.hlcTimestamp
        )
    }

    /// Retrieves all active (non-deleted) records for the specified collection.
    public func getActiveRecords(collection: String) throws -> [String: ChangeRecord] {
        guard let db = db else { throw SqliteError.databaseClosed }
        let sql = "SELECT id, type, payload, hlc_timestamp FROM airdb_entities WHERE collection = ? AND type != 2"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SqliteError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }

        sqlite3_bind_text(stmt, 1, collection, -1, sqliteTransient)

        var map: [String: ChangeRecord] = [:]
        while sqlite3_step(stmt) == SQLITE_ROW {
            let id = sqlite3_column_text(stmt, 0).flatMap { String(cString: $0) } ?? ""
            let rawType = UInt8(sqlite3_column_int(stmt, 1))
            let type = MutationType(rawValue: rawType) ?? .insert

            let payloadData: Data
            if let blobPointer = sqlite3_column_blob(stmt, 2) {
                let bytes = sqlite3_column_bytes(stmt, 2)
                payloadData = Data(bytes: blobPointer, count: Int(bytes))
            } else {
                payloadData = Data()
            }

            let hlc = sqlite3_column_text(stmt, 3).flatMap { String(cString: $0) } ?? ""

            map[id] = ChangeRecord(
                id: id,
                collection: collection,
                type: type,
                payload: payloadData,
                hlcTimestamp: hlc
            )
        }
        return map
    }

    /// Checks if a record exists in SQLite and is marked as deleted (tombstone).
    public func isRecordDeleted(collection: String, id: String) throws -> Bool {
        guard let entity = try readEntity(collection: collection, id: id) else { return false }
        return entity.type == .delete
    }

    /// Closes the underlying SQLite connection.
    public func close() {
        if let db = db {
            sqlite3_close_v2(db)
            self.db = nil
        }
    }
}
