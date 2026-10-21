# AirDbKit

Swift implementation of the AirDB peer-to-peer database replication engine for Apple platforms (iOS, macOS, watchOS, and visionOS).

AirDbKit provides decentralized, local-first synchronization over Bluetooth Low Energy (BLE). It has zero dependency on central servers, cellular internet, or cloud infrastructure, making it suitable for disaster response, emergency medical triage, remote logistics, and mobile point-of-sale environments.

AirDbKit maintains 100% on-wire binary compatibility with AirDB for Android and Kotlin/JVM.

---

## Architectural Principles

1. **Sans-I/O Layering**: Pure causal replication and wire serialization decoupled from I/O mechanisms.
2. **Actor Concurrency**: Built with Swift structured concurrency (`actor`, `async/await`, `Sendable`) ensuring compile-time data race freedom.
3. **Zero Third-Party Dependencies**: Built exclusively on Apple standard frameworks (`CryptoKit`, `Compression`, `CoreBluetooth`, and `libsqlite3`). No external CocoaPods or SPM package dependencies.
4. **Zero-Trust Cryptographic Mesh**:
   - Cluster-level AEAD symmetric encryption via `ChaChaPolyCipher` (RFC 8439 ChaCha20-Poly1305) with packet header authentication.
   - Record-level digital signatures via `Ed25519Signer` (RFC 8032) for cryptographic provenance and tamper detection across untrusted relay nodes.
   - Key derivation via `HkdfSha256` (RFC 5869).
5. **Durable Persistence Engine**: Built-in `SqliteDbAdapter` with Write-Ahead Logging (WAL) and atomic transactions.
6. **Resilient BLE Transport**: Dual-role `CoreBluetoothTransport` (Central + Peripheral) with MTU fragmentation, 30-second TTL assembly, and backpressure flow control.

---

## Architecture Overview

```
+-------------------------------------------------------------+
| Application Storage (Triage Data, Offline Orders, Registries)|
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
| SqliteDbAdapter (Durable SQLite3 Persistence Seam)          |
| - libsqlite3 C-bindings (Zero External Dependencies)        |
| - Write-Ahead Logging (WAL) & Immediate Transactions        |
| - Indexed Change Tracking & Monotonic HLC Compaction        |
+------------------------------+------------------------------+
                               | Conforms to AirDbAdapter
+------------------------------v------------------------------+
| AirDbKit Core (Actor State & Causal Order Engine)           |
| - HybridLogicalClock (HLC) & HlcTimestamp                   |
| - VectorClock State Tracker                                 |
| - SyncSession 4-Phase Protocol with Signature Enforcement   |
+------------------------------+------------------------------+
                               | Signs / Verifies Records
+------------------------------v------------------------------+
| AirDbKit Crypto (Zero-Trust Cryptographic Mesh)             |
| - CryptoKit ChaChaPoly (RFC 8439 256-bit AEAD)              |
| - CryptoKit Curve25519.Signing (RFC 8032 Ed25519)           |
| - CryptoKit HKDF<SHA256> (RFC 5869 Key Derivation)          |
+------------------------------+------------------------------+
                               | Encodes / Decodes
+------------------------------v------------------------------+
| AirDbKit Protocol (Binary Wire Codec & MTU Framing)         |
| - 23/31-byte Fixed Header + Big-Endian Network Byte Order   |
| - Authenticated Additional Data (AAD) Header Binding        |
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

### 2. Initialize AirDB Engine with Security and Durable SQLite

```swift
import AirDbKit
import Foundation

// 1. Initialize durable native SQLite persistence
let dbPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    .appendingPathComponent("airdb.db").path
let adapter = try SqliteDbAdapter(databasePath: dbPath)

// 2. Derive cluster encryption key and node signing key pair
let clusterKey = try HkdfSha256.deriveKey(
    secret: "emergency-cluster-shared-secret".data(using: .utf8)!,
    salt: "airdb-mesh-v1".data(using: .utf8)!,
    info: "cluster-aead-key".data(using: .utf8)!
)
let authorKeyPair = try Ed25519Signer.generateKeyPair()

// 3. Initialize CoreBluetooth transport
let transport = CoreBluetoothTransport()

// 4. Create the AirDB Engine with cryptographic provenance enforcement
let engine = AirDbEngine(
    nodeName: "posko-merapi-ios-01",
    adapter: adapter,
    transport: transport,
    clusterKey: clusterKey,
    authorKeyPair: authorKeyPair,
    enforceSignatures: true
)

// 5. Listen for synchronization updates from nearby peers
engine.addSyncListener { peerId, changeCount in
    print("Synchronized \(changeCount) verified records from peer: \(peerId)")
}

// 6. Start radio operations
engine.start()
```

### 3. Record Mutations

Save records locally using `saveRecord`. Every mutation is stamped with an authoritative Hybrid Logical Clock (HLC) timestamp, signed with the local `authorKeyPair`, and propagated to discovered peers:

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
print("Author public key: \(record.authorPublicKey?.count ?? 0) bytes")
```

---

## Wire Protocol Compatibility

AirDbKit adheres strictly to the AirDB Wire Protocol Specification ([`docs/reference/wire-spec.md`](../docs/reference/wire-spec.md)):

- **GATT Service UUID**: `41697244-4200-0000-8000-00805F9B34FB`
- **GATT Characteristic UUID**: `41697244-4201-0000-8000-00805F9B34FB`
- **Magic Bytes**: `0x41 0x44` ("AD")
- **Network Byte Order**: Big-Endian multi-byte integers
- **AEAD Encryption**: ChaCha20-Poly1305 (`FLAG_ENCRYPTED = 0x04`) with 12-byte nonce, 16-byte Poly1305 authentication tag, and header bound as AAD.
- **Cryptographic Provenance**: Ed25519 digital signatures (`FLAG_RECORD_SIGNED = 0x80`) on delta batches with 32-byte public key and 64-byte signature.
- **Deflate Compression**: Apple `Compression` framework zlib integration for payloads > 256 bytes (`FLAG_COMPRESSED = 0x01`).
- **Fragmentation**: 13-byte fragment envelope with sliding 30-second TTL reassembly buffer.

---

## Testing

AirDbKit includes automated unit tests covering causality, vector clocks, wire codecs, fragmentation reassembly, SQLite persistence, cryptographic primitives, and simulated P2P convergence:

```bash
swift test
```

---

## Acknowledgements & Prior Art

AirDbKit's design and protocols were inspired by:
- **BitChat**: For pioneering practical mobile ad-hoc BLE mesh networking and store-and-forward relay mechanics across Apple and Android ecosystems. AirDbKit is a clean-room implementation built for causal database replication.
- **Hybrid Logical Clocks**: Research by Kulkarni et al. (2014) on monotonic physical-logical clock ordering.
- **Vector Clocks**: Foundational distributed systems research by Lamport (1978), Fidge (1988), and Mattern (1988).
- **Local-First Computing**: The Local-First Software Manifesto by Kleppmann et al. (Ink & Switch, 2019).
- **Apple Cryptography & Persistence**: Apple CryptoKit (RFC 8439 / RFC 8032) and native `libsqlite3` architecture.

---

## License

Copyright 2026 AirDB Authors. Licensed under the Apache License, Version 2.0.
