# Cookbook: GRDB.swift Integration (iOS & macOS)

[GRDB.swift](https://github.com/groue/GRDB.swift) is a toolkit for SQLite databases in Swift applications. This guide shows how to implement `AirDbAdapter` with GRDB using `DatabaseQueue` or `DatabasePool`.

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
                      let rawType: Int = row["mutation_type"],
                      let type = MutationType(rawValue: rawType),
                      let hlc: String = row["hlc_timestamp"] else {
                    return nil
                }
                
                let payload: Data = row["payload"] ?? Data()
                let authorPublicKey: Data? = row["author_public_key"]
                let signature: Data? = row["signature"]
                
                return ChangeRecord(
                    id: id,
                    collection: collection,
                    type: type,
                    payload: payload,
                    hlcTimestamp: hlc,
                    authorPublicKey: authorPublicKey,
                    signature: signature
                )
            }
        }
    }

    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        try await dbWriter.write { db in
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            
            for change in changes {
                let currentHlc = try String.fetchOne(
                    db,
                    sql: "SELECT hlc_timestamp FROM _airdb_changes WHERE collection = ? AND id = ?",
                    arguments: [change.collection, change.id]
                )
                
                // Last-Write-Wins (LWW) check
                if currentHlc == nil || change.hlcTimestamp > currentHlc! {
                    try db.execute(
                        sql: """
                        INSERT OR REPLACE INTO _airdb_changes (
                            collection, id, mutation_type, payload, hlc_timestamp, author_public_key, signature, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        arguments: [
                            change.collection,
                            change.id,
                            change.type.rawValue,
                            change.payload,
                            change.hlcTimestamp,
                            change.authorPublicKey,
                            change.signature,
                            now
                        ]
                    )
                }
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

---

## 3. Engine Setup

```swift
import AirDbKit
import GRDB

let dbQueue = try DatabaseQueue(path: "/path/to/database.sqlite")
var migrator = DatabaseMigrator()
migrator.registerAirDbMigrations()
try migrator.migrate(dbQueue)

let adapter = GrdbAirDbAdapter(dbWriter: dbQueue)
let transport = CoreBluetoothTransport()

let engine = AirDbEngine(
    nodeName: "ios-posko-unit-01",
    adapter = adapter,
    transport = transport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

engine.start()
```
