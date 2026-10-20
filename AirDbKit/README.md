# AirDbKit

Swift implementation of the AirDB peer-to-peer database replication engine for Apple platforms (iOS, macOS, watchOS, and visionOS).

AirDbKit provides decentralized, local-first synchronization over Bluetooth Low Energy (BLE). It has zero dependency on central servers, cellular internet, or cloud infrastructure, making it suitable for disaster response, emergency medical triage, remote logistics, and mobile point-of-sale environments.

AirDbKit has 100% on-wire binary compatibility with AirDB for Android.

---

## Architectural Principles

1. **Sans-I/O Layering**: Pure causal replication and wire serialization decoupled from I/O mechanisms.
2. **Actor Concurrency**: Built with Swift structured concurrency (`actor`, `async/await`, `Sendable`) ensuring compile-time race freedom.
3. **Bring Your Own Database (BYOD)**: Any local persistence library conforming to `AirDbAdapter` (SwiftData, GRDB.swift, CoreData, SQLite.swift) can be synchronized.
4. **Resilient BLE Transport**: Dual-role `CoreBluetooth` architecture (Central + Peripheral) with MTU fragmentation, 30-second TTL assembly, and backpressure flow control.

---

## Architecture Overview

```
+-------------------------------------------------------------+
| Application Storage (SwiftData, GRDB, CoreData, SQLite)     |
+------------------------------+------------------------------+
                               | Conforms to AirDbAdapter
+------------------------------v------------------------------+
| AirDbKit Core (Actor State & Causal Order Engine)           |
| - HybridLogicalClock (HLC) & HlcTimestamp                   |
| - VectorClock State Tracker                                 |
| - SyncSession 4-Phase Protocol                              |
+------------------------------+------------------------------+
                               | Encodes / Decodes
+------------------------------v------------------------------+
| AirDbKit Protocol (Binary Wire Codec & MTU Framing)         |
| - 23/31-byte Fixed Header + Big-Endian Network Byte Order   |
| - Apple Compression Framework (zlib Deflate)                |
| - 13-byte Fragment Envelope with AssemblyBuffer (30s TTL)   |
+------------------------------+------------------------------+
                               | Streams Bytes
+------------------------------v------------------------------+
| AirDbKit Transport Boundary                                 |
|       ^                                        ^            |
|       |                                        |            |
| +-----+-----------------------+ +--------------+----------+ |
| | CoreBluetoothTransport      | | SimulatedTransport      | |
| | - CBCentralManager (Client) | | (Sans-I/O in-memory     | |
| | - CBPeripheralManager (Srv) | |  topology for tests)    | |
| | - WriteWithoutResponse      | +-------------------------+ |
| | - Backpressure Drain Queue  |                             |
| +-----------------------------+                             |
+-------------------------------------------------------------+
```

---

## Installation

### Swift Package Manager (SPM)

In Xcode:
1. Select **File > Add Package Dependencies...**
2. Enter the repository URL:
   ```
   https://github.com/untacorp/airdb.git
   ```
3. Set dependency rule to branch `main` or specific version tag.
4. Add `AirDbKit` to your application target.

Or add it directly to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/untacorp/airdb.git", branch: "main")
]
```

---

## Requirements

| Platform | Minimum Deployment Target |
| :--- | :--- |
| iOS | 15.0+ |
| macOS | 12.0+ |
| watchOS | 8.0+ |
| tvOS | 15.0+ |
| Swift | 5.9+ |

---

## Getting Started

### 1. Configure Info.plist for Bluetooth

To enable Bluetooth peripheral and central roles on iOS, declare the following privacy keys in your application `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>AirDB synchronizes local records with nearby responders over Bluetooth Low Energy.</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>AirDB advertises local database changes to nearby devices.</string>
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>bluetooth-peripheral</string>
</array>
```

### 2. Initialize AirDB Engine

```swift
import AirDbKit

// 1. Choose or implement an adapter (MemoryDbAdapter provided for testing)
let adapter = MemoryDbAdapter()

// 2. Initialize CoreBluetooth transport
let transport = CoreBluetoothTransport()

// 3. Create the AirDB Engine with a node identifier
let engine = AirDbEngine(
    nodeName: "posko-merapi-ios-01",
    adapter: adapter,
    transport: transport
)

// 4. Listen for synchronization updates from nearby peers
engine.addSyncListener { peerId, changeCount in
    print("Synchronized \(changeCount) records from peer: \(peerId)")
}

// 5. Start radio operations
engine.start()
```

### 3. Record Mutations

Save records locally using `saveRecord`. Every mutation is stamped with an authoritative Hybrid Logical Clock (HLC) timestamp and propagated to discovered peers:

```swift
let payload = """
{
    "patientId": "P-9842",
    "name": "Siti Rahma",
    "triage": "YELLOW",
    "pulse": 88
}
""".data(using: .utf8)!

let record = try await engine.saveRecord(
    collection: "triage_assessments",
    id: "P-9842",
    type: .insert,
    payload: payload
)

print("Saved change record: \(record.id) with HLC: \(record.hlcTimestamp)")
```

### 4. Custom Database Seam (e.g., GRDB.swift)

To sync your production SQLite or GRDB database, conform your database manager to `AirDbAdapter`:

```swift
import AirDbKit
import GRDB

public final class GrdbAirDbAdapter: AirDbAdapter, @unchecked Sendable {
    private let dbQueue: DatabaseQueue

    public init(dbQueue: DatabaseQueue) {
        self.dbQueue = dbQueue
    }

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord] {
        try await dbQueue.read { db in
            let query: SQLRequest<ChangeRecordRow>
            if let since = sinceHlcTimestamp {
                query = SQLRequest(sql: "SELECT * FROM _airdb_changes WHERE hlc > ? ORDER BY hlc ASC LIMIT ?", arguments: [since, limit])
            } else {
                query = SQLRequest(sql: "SELECT * FROM _airdb_changes ORDER BY hlc ASC LIMIT ?", arguments: [limit])
            }
            let rows = try query.fetchAll(db)
            return rows.map { $0.toChangeRecord() }
        }
    }

    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        try await dbQueue.write { db in
            for change in changes {
                // Apply LWW idempotent upsert
                try change.applyToDatabase(db)
            }
        }
    }

    public func getLatestHlc() async throws -> String? {
        try await dbQueue.read { db in
            try String.fetchOne(db, sql: "SELECT MAX(hlc) FROM _airdb_changes")
        }
    }
}
```

---

## Wire Protocol Compatibility

AirDbKit adheres strictly to `docs/PROTOCOL_SPEC.md`:

- **GATT Service UUID**: `41697244-4200-0000-8000-00805F9B34FB`
- **GATT Characteristic UUID**: `41697244-4201-0000-8000-00805F9B34FB`
- **Magic Bytes**: `0x41 0x44` ("AD")
- **Network Byte Order**: Big-Endian multi-byte integers
- **Deflate Compression**: Apple `Compression` framework zlib integration for payloads > 256 bytes
- **Fragmentation**: 13-byte fragment envelope with sliding 30-second TTL reassembly buffer

---

## Testing

AirDbKit includes automated unit tests covering causality, vector clocks, wire codecs, fragmentation reassembly, and simulated P2P convergence:

```bash
swift test
```

---

## License

Copyright 2026 AirDB Authors. Licensed under the Apache License, Version 2.0.
