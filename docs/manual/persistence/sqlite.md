# Built-in SQLite Storage Engine

AirDB includes an optimized, production-grade SQLite persistence engine out of the box:
- On **Android and Kotlin JVM**: `SqliteDbAdapter` in `:airdb-adapter-sqlite`.
- On **Apple Platforms (iOS/macOS)**: `SqliteDbAdapter` in `AirDbKit` (backed by native `libsqlite3`).

It requires zero manual SQL queries, configures high-performance Write-Ahead Logging (WAL) automatically, and provides crash resilience with atomic batch transactions.

---

## 1. Quick Initialization

=== "Kotlin (Android / JVM)"

    ```kotlin
    import io.airdb.adapter.sqlite.SqliteDbAdapter
    import java.io.File

    // On Android:
    val dbFile = File(context.filesDir, "airdb_local.sqlite")
    val adapter = SqliteDbAdapter.open(dbFile)

    // For in-memory unit tests:
    // val testAdapter = SqliteDbAdapter.inMemory()
    ```

=== "Swift (Apple Platforms)"

    ```swift
    import AirDbKit
    import Foundation

    let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    let dbPath = docsDir.appendingPathComponent("airdb_local.sqlite").path

    let adapter = try SqliteDbAdapter(databasePath: dbPath)

    // For in-memory unit tests:
    // let testAdapter = try SqliteDbAdapter.inMemory()
    ```

---

## 2. Under the Hood: Tables & Performance Primitives

When initialized, the SQLite adapter automatically executes the following performance optimizations and schema setup:

### Pragmas
- `PRAGMA journal_mode = WAL;`: Enables Write-Ahead Logging. Readers never block writers, and writers never block readers.
- `PRAGMA synchronous = NORMAL;`: Reduces disk sync overhead while maintaining full durability against application crashes.
- `PRAGMA foreign_keys = ON;`: Enforces relational integrity.

### Tables
1. **`airdb_entities`**: Stores the latest known state of each entity:
   ```sql
   CREATE TABLE IF NOT EXISTS airdb_entities (
       collection TEXT NOT NULL,
       id TEXT NOT NULL,
       type INTEGER NOT NULL,
       payload BLOB NOT NULL,
       hlc_timestamp TEXT NOT NULL,
       updated_at INTEGER NOT NULL,
       PRIMARY KEY (collection, id)
   );
   CREATE INDEX IF NOT EXISTS idx_entities_hlc ON airdb_entities (hlc_timestamp);
   ```

2. **`airdb_changelog`**: An append-only sequence tracking every applied mutation:
   ```sql
   CREATE TABLE IF NOT EXISTS airdb_changelog (
       seq INTEGER PRIMARY KEY AUTOINCREMENT,
       collection TEXT NOT NULL,
       id TEXT NOT NULL,
       type INTEGER NOT NULL,
       payload BLOB NOT NULL,
       hlc_timestamp TEXT NOT NULL,
       created_at INTEGER NOT NULL
   );
   CREATE INDEX IF NOT EXISTS idx_changelog_hlc ON airdb_changelog (hlc_timestamp);
   ```

---

## 3. Atomic Transactions & Conflict Resolution

When a batch of remote mutations arrives from a peer:
1. The adapter starts an immediate transaction (`BEGIN IMMEDIATE`).
2. For each change record, it compares the incoming HLC timestamp against the stored entity's timestamp using `HlcComparator`.
3. If the incoming change is newer, the live table `airdb_entities` is updated and an entry is appended to `airdb_changelog`.
4. If an error occurs or the process is killed midway, SQLite cleanly rolls back the entire batch, preserving database consistency.
