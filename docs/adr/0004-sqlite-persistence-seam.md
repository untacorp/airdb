# ADR 0004: Durable SQLite Persistence Seam

## Status
Accepted

## Context
AirDB was initially demonstrated with an in-memory database adapter (`MemoryDbAdapter`). For production deployments in disaster triage and mobile POS systems, nodes require durable disk persistence that survives application termination, power loss, and battery depletion. Furthermore, Apple platforms require a lightweight storage engine without bringing in heavy third-party SPM or CocoaPods dependencies (such as Realm, CoreData overhead, or third-party wrappers).

## Decision
We introduce durable SQLite storage adapters conforming to `AirDbAdapter`:
1. **JVM & Android**: `SqliteAirDbAdapter` in `:airdb-adapter-sqlite`.
2. **Apple Platforms**: `SqliteDbAdapter` in `AirDbKit` utilizing native `libsqlite3` C-bindings.
3. **Database Engine Configuration**:
   - Write-Ahead Logging enabled via `PRAGMA journal_mode = WAL`.
   - Synchronous disk writes set to `PRAGMA synchronous = NORMAL`.
   - Foreign keys enabled where applicable.
4. **Change Tracking & Tombstones**:
   - A dedicated `_airdb_changes` table records mutation types (INSERT, UPDATE, DELETE), HLC timestamps, author public keys, and signatures.
   - An index on `hlc_timestamp ASC` ensures range queries (`getChangesSince`) execute in $\mathcal{O}(\log N + K)$ time.
   - Soft-delete tombstones prevent concurrent reconciliation from resurrecting deleted records.

## Consequences
- **Positive**:
  - High write throughput and concurrent non-blocking reads via SQLite WAL.
  - Zero external package dependencies on Apple platforms.
  - Atomic transactions guarantee that delta batches are either applied in full or rolled back.
- **Negative**:
  - Requires maintaining SQL schema evolution and tombstone compaction logic across two platforms.
