# GRDB.swift Integration (iOS & macOS)

[GRDB.swift](https://github.com/groue/GRDB.swift) is a popular SQLite toolkit for iOS and macOS. This guide shows how to implement `AirDbAdapter` with GRDB using `DatabaseQueue` or `DatabasePool`.

---

## 1. Schema Migration (`AirDbMigration`)

Define the changelog table in your GRDB `DatabaseMigrator`:

```swift
import GRDB

extension DatabaseMigrator {
    mutating func registerAirDbMigrations() {
        registerMigration("createAirDbChangelog") { db in
            try db.create(table: "_airdb_changes") { t in
                t.column("collection", .text).notNull()
                t.column("id", .text).notNull()
                t.column("mutation_type", .integer).notNull()
                t.column("payload", .blob)
                t.column("hlc_timestamp", .text).notNull()
                t.column("author_public_key", .blob)
                t.column("signature", .blob)
                t.column("created_at", .integer).notNull()
                
                t.primaryKey(["collection", "id"])
            }
            
            try db.create(
                index: "idx_airdb_changes_hlc",
                on: "_airdb_changes",
                columns: ["hlc_timestamp"]
            )
        }
    }
}
```

---

## 2. Implementing `AirDbAdapter`

```swift
import AirDbKit
import Foundation
import GRDB

public final class GrdbAirDbAdapter: AirDbAdapter, @unchecked Sendable {
    private let dbWriter: any DatabaseWriter

    public init(dbWriter: any DatabaseWriter) {
        self.dbWriter = dbWriter
    }

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord] {
        try await dbWriter.read { db in
            let sql: String
            let arguments: StatementArguments
            
            if let since = sinceHlcTimestamp {
                sql = "SELECT * FROM _airdb_changes WHERE hlc_timestamp > ? ORDER BY hlc_timestamp ASC LIMIT ?"
                arguments = [since, limit]
            } else {
                sql = "SELECT * FROM _airdb_changes ORDER BY hlc_timestamp ASC LIMIT ?"
                arguments = [limit]
            }
            
            let rows = try Row.fetchAll(db, sql: sql, arguments: arguments)
            return rows.compactMap { row -> ChangeRecord? in
                guard let id: String = row["id"],
                      let collection: String = row["collection"],
                      let typeInt: Int = row["mutation_type"],
                      let type = MutationType(rawValue: typeInt),
                      let hlc: String = row["hlc_timestamp"] else {
                    return nil
                }
                
                return ChangeRecord(
                    id: id,
                    collection: collection,
                    type: type,
                    payload: row["payload"] ?? Data(),
                    hlcTimestamp: hlc,
                    authorPublicKey: row["author_public_key"],
                    signature: row["signature"]
                )
            }
        }
    }

    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        try await dbWriter.write { db in
            for change in changes {
                // Apply LWW conflict resolution
                let existingHlc: String? = try String.fetchOne(
                    db,
                    sql: "SELECT hlc_timestamp FROM _airdb_changes WHERE collection = ? AND id = ?",
                    arguments: [change.collection, change.id]
                )

                if let existing = existingHlc, HlcComparator.compare(change.hlcTimestamp, existing) <= 0 {
                    continue
                }

                try db.execute(
                    sql: """
                    INSERT INTO _airdb_changes (collection, id, mutation_type, payload, hlc_timestamp, author_public_key, signature, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(collection, id) DO UPDATE SET
                        mutation_type = excluded.mutation_type,
                        payload = excluded.payload,
                        hlc_timestamp = excluded.hlc_timestamp,
                        author_public_key = excluded.author_public_key,
                        signature = excluded.signature,
                        created_at = excluded.created_at
                    """,
                    arguments: [
                        change.collection,
                        change.id,
                        change.type.rawValue,
                        change.payload,
                        change.hlcTimestamp,
                        change.authorPublicKey,
                        change.signature,
                        Int64(Date().timeIntervalSince1970 * 1000)
                    ]
                )
            }
        }
    }

    public func getLatestHlc() async throws -> String? {
        try await dbWriter.read { db in
            try String.fetchOne(db, sql: "SELECT MAX(hlc_timestamp) FROM _airdb_changes")
        }
    }
}
```
