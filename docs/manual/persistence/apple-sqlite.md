# Apple Native SQLite Guide

`AirDbKit` includes an out-of-the-box durable persistence engine: `SqliteDbAdapter`. It is implemented directly on top of Apple's system `libsqlite3` C library, providing **zero third-party dependencies**.

---

## Key Capabilities

- **Zero External Dependencies**: Relies strictly on Apple's built-in `import SQLite3`.
- **Write-Ahead Logging (WAL mode)**: Supports concurrent non-blocking reads while writing mutations.
- **Indexed HLC Range Queries**: Fast delta queries using the `idx_airdb_changes_hlc` index.
- **Atomic Transactions**: Batch changes are wrapped in immediate transactions (`BEGIN IMMEDIATE` ... `COMMIT`).
- **Thread Safety**: Fully thread-safe across Swift concurrency tasks using scoped locks.

---

## Basic Usage

```swift
import AirDbKit
import Foundation

// 1. Locate documents directory for persistent storage
let documentsUrl = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
let databasePath = documentsUrl.appendingPathComponent("airdb_production.sqlite").path

// 2. Initialize the adapter
let adapter = try SqliteDbAdapter(databasePath: databasePath)

// 3. Initialize BLE Transport & Engine
let transport = CoreBluetoothTransport()
let engine = AirDbEngine(
    nodeName: "posko-field-ipad-01",
    adapter = adapter,
    transport = transport,
    clusterKey: clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures: true
)

await engine.start()
```

---

## Saving and Querying Records

```swift
// Record an insert or update
let jsonString = """
{
    "patientId": "SURVIVOR-204",
    "name": "Agus Santoso",
    "status": "Treated",
    "triage": "GREEN"
}
"""
let payload = jsonString.data(using: .utf8)!

let record = try await engine.saveRecord(
    collection: "survivors",
    id: "SURVIVOR-204",
    type: .insert,
    payload: payload
)

print("Saved record with HLC: \(record.hlcTimestamp)")
```
