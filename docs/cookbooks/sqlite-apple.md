# Cookbook: Built-in Native SQLite Adapter for Apple Platforms

`AirDbKit` includes an out-of-the-box durable persistence engine: `SqliteDbAdapter`. It is implemented directly on top of Apple's system `libsqlite3` C library, which means **zero third-party dependencies** (no GRDB, no SQLite.swift, and no external CocoaPods/SPM packages).

---

## 1. Key Features

- **Zero External Dependencies**: Uses native Apple system `import SQLite3`.
- **Write-Ahead Logging (WAL mode)**: Supports concurrent non-blocking reads while writing mutations.
- **Indexed HLC Range Queries**: Fast $\mathcal{O}(\log N + K)$ delta fetches via `idx_airdb_changes_hlc`.
- **Atomic Transactions**: Batch changes are wrapped in immediate transactions (`BEGIN IMMEDIATE` ... `COMMIT`).
- **Thread Safety**: Fully thread-safe across Swift concurrency tasks using scoped locks (`NSLock.withScopedLock`).

---

## 2. Basic Usage

```swift
import AirDbKit
import Foundation

// 1. Locate documents directory
let fileManager = FileManager.default
let documentsUrl = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
let databasePath = documentsUrl.appendingPathComponent("airdb_production.db").path

// 2. Initialize SqliteDbAdapter
let adapter = try SqliteDbAdapter(databasePath: databasePath)

// 3. Initialize BLE Transport
let transport = CoreBluetoothTransport()

// 4. Start AirDB Engine
let engine = AirDbEngine(
    nodeName: "posko-field-ipad-01",
    adapter = adapter,
    transport = transport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

engine.start()
```

---

## 3. Saving & Reading Records

```swift
// Record an insert or update
let jsonString = """
{
    "patientId": "SURVIVOR-204",
    "name": "Agus Santoso",
    "status": "Evacuated to Base Hospital",
    "triage": "GREEN"
}
"""
let payloadData = jsonString.data(using: .utf8)!

let record = try await engine.saveRecord(
    collection: "triage_registry",
    id: "SURVIVOR-204",
    type: .insert,
    payload: payloadData
)

print("Saved record with HLC: \(record.hlcTimestamp)")
```

---

## 4. Deleting Records (Tombstone Propagation)

AirDB treats deletions as first-class mutations. To delete a record:

```swift
try await engine.saveRecord(
    collection: "triage_registry",
    id: "SURVIVOR-204",
    type: .delete,
    payload: Data() // empty payload for tombstones
)
```

The tombstone will be assigned a new HLC timestamp and automatically propagated to nearby devices, ensuring all replicas delete the record and prevent older writes from resurrecting it.
