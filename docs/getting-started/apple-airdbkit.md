# Apple Platforms Guide (AirDbKit)

`AirDbKit` is the native Swift implementation of the AirDB protocol, designed specifically for **iOS, iPadOS, macOS, and watchOS**.

---

## Architectural Principles on Apple Platforms

AirDbKit was engineered with three core principles:

### 1. Zero Third-Party Dependencies
AirDbKit does not require any third-party Swift packages or CocoaPods. It is built entirely on native Apple system frameworks:
- **Radio Networking**: `CoreBluetooth`
- **Cryptography**: `CryptoKit` (`ChaChaPoly`, `Curve25519.Signing`, `HKDF`)
- **Persistence**: `libsqlite3` (native C SQLite bindings included in every Apple OS)
- **Data Compression**: Apple `Compression` framework (zlib Deflate)

This ensures zero supply-chain risk, zero version conflicts with other dependencies, and tiny binary overhead.

### 2. Swift Actor Concurrency Model
The central engine `AirDbEngine` is implemented as a Swift `actor`:
- **Compile-Time Data Race Safety**: All internal state (active sync sessions, vector clocks, and link queues) is isolated within the actor.
- **Asynchronous Delegates**: Incoming CoreBluetooth events are dispatched into Swift tasks asynchronously without blocking Bluetooth radio threads.

---

## Basic Swift Integration

Here is a typical integration inside a SwiftUI or UIKit app:

```swift
import AirDbKit
import Foundation

@MainActor
final class SyncManager: ObservableObject {
    @Published var lastSyncedPeer: String = ""
    @Published var syncedChangeCount: Int = 0

    private var engine: AirDbEngine?
    private var adapter: SqliteDbAdapter?

    func startEngine() async throws {
        // 1. Locate documents directory for persistent SQLite database
        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dbPath = docsDir.appendingPathComponent("posko_records.sqlite").path

        // 2. Open SQLite persistence
        let sqliteAdapter = try SqliteDbAdapter(databasePath: dbPath)
        self.adapter = sqliteAdapter

        // 3. Derive cluster encryption key
        let clusterKey = try HkdfSha256.deriveKey(
            secret: "disaster-relief-shared-secret".data(using: .utf8)!,
            salt: "airdb-v1".data(using: .utf8)!,
            info: "cluster-aead-key".data(using: .utf8)!
        )
        let authorKeyPair = try Ed25519Signer.generateKeyPair()

        // 4. Initialize CoreBluetooth transport
        let transport = CoreBluetoothTransport()

        // 5. Initialize AirDbEngine actor
        let airdb = AirDbEngine(
            nodeName: "field-team-ios-\(UUID().uuidString.prefix(4))",
            adapter: sqliteAdapter,
            transport: transport,
            clusterKey: clusterKey,
            authorKeyPair: authorKeyPair,
            enforceSignatures: true
        )

        // 6. Listen for incoming changes
        await airdb.addSyncListener { [weak self] peerId, count in
            Task { @MainActor in
                self?.lastSyncedPeer = peerId
                self?.syncedChangeCount += count
            }
        }

        // 7. Start radio operations
        await airdb.start()
        self.engine = airdb
    }

    func saveSurvivorRecord(id: String, name: String, triageStatus: String) async throws {
        guard let engine = engine else { return }

        let payloadJson = """
        {"name": "\(name)", "status": "\(triageStatus)"}
        """

        _ = try await engine.saveRecord(
            collection: "survivors",
            id: id,
            type: .insert,
            payload: payloadJson.data(using: .utf8)!
        )
    }
}
```

---

## Running Unit Tests on macOS

You can verify the entire Swift test suite locally using the Swift Package Manager CLI:

```bash
cd airdb/AirDbKit
swift test
```

This runs comprehensive unit tests for HLC causality math, binary packet codecs, SQLite WAL operations, ChaCha20-Poly1305 encryption, and simulated peer synchronization without needing physical Bluetooth hardware.
