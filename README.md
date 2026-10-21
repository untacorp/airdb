<div align="center">

# AirDB

**Decentralized, Local-First P2P Database Synchronization Engine over Bluetooth Low Energy**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org)
[![Swift](https://img.shields.io/badge/Swift-5.9%2B-orange.svg)](AirDbKit/)
[![Architecture](https://img.shields.io/badge/Architecture-Sans--I%2FO-success.svg)](docs/manual/index.md)
[![Security](https://img.shields.io/badge/Security-Zero--Trust%20Mesh-blueviolet.svg)](docs/manual/cryptography/security-mesh.md)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)](#testing)

*Zero Internet. Zero Central Servers. Automatic Peer Reconciliation. Cryptographic Provenance.*

</div>

---

## Overview

**AirDB** is an open-source, modular, local-first database replication engine designed for off-grid operations. When mobile devices running AirDB enter Bluetooth Low Energy (BLE) proximity (~10–30 meters), they automatically discover each other, negotiate causal state differences, and stream database mutations directly device-to-device.

### Primary Use Cases

- **Disaster Response & Emergency Camps**: Coordinate survivor registries, triage status, and medical supplies in disaster zones where cellular towers and power grids are offline.
- **Off-Grid Mobile POS**: Synchronize inventory, menu updates, and offline sales transactions across distributed cashier tablets at outdoor venues or remote markets.
- **Field Logistics & Conservation**: Replicate wildlife observations, inspection checklists, and sensor logs between rangers in the field without satellite connectivity.

---

## Key Capabilities

- **Dual-Role BLE Concurrency**: Every device operates simultaneously as both a GATT Central (scanner) and GATT Peripheral (advertiser).
- **Sans-I/O Layered Design**: Core causality logic, vector clocks, and wire codecs are 100% pure Kotlin and pure Swift standard libraries with zero socket or platform framework leaks.
- **Monotonic Hybrid Logical Clocks (HLC)**: Provides deterministic distributed causality and Last-Write-Wins (LWW) resolution without network time synchronization.
- **Zero-Trust Cryptographic Mesh**:
  - **Cluster Symmetric AEAD (RFC 8439)**: Payloads are encrypted with ChaCha20-Poly1305 using a 256-bit cluster key, 12-byte CSPRNG nonce, and full packet headers bound as Authenticated Additional Data (AAD).
  - **Record-Level Digital Signatures (RFC 8032)**: Every mutation is signed by the originating author with Ed25519 (32-byte public key, 64-byte signature), enabling non-repudiation and tamper prevention across untrusted mesh relay hops.
  - **Key Derivation (RFC 5869)**: HKDF-SHA256 for deterministic key material and subkey derivation.
- **Durable Local Persistence**: Built-in production SQLite storage engines (`airdb-adapter-sqlite` on Android/JVM and `SqliteDbAdapter` on Apple platforms) with Write-Ahead Logging (WAL), indexed HLC ranges, and atomic tombstone handling. Zero third-party package dependencies on Apple platforms via native `libsqlite3`.
- **Serialized In-Flight GATT Queue**: Enforces single-flight operation ordering per link, eliminating native Bluetooth stack race conditions.
- **Automated Compression & Framing**: Serializes compact binary packets, automatically compressing payloads (>256 B) via zlib Deflate and partitioning packets exceeding link MTU.
- **Deterministic Sans-I/O Simulator**: Includes `airdb-transport-sim` and `SimulatedTransport` to test complete multi-node sync topologies in memory in milliseconds without hardware radios.

---

## Architecture

```
+-------------------------------------------------------------------+
| Application Layer (Local Domain Entities, Posko Triage, POS Data) |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
| airdb-adapter-sqlite / SqliteDbAdapter (Durable Persistence Seam) |
| - Write-Ahead Logging (WAL) & ACID Transactions                   |
| - Monotonic HLC Indexing (_airdb_changes)                         |
| - Soft-Delete Tombstone Lifecycle Management                      |
+---------------------------------+---------------------------------+
                                  | Implements AirDbAdapter
+---------------------------------v---------------------------------+
| airdb-core / AirDbKit Core (Causality & Reconciliation Engine)     |
| - Hybrid Logical Clock (HLC) & Vector Clock State Tracker         |
| - 4-Phase SyncSession Protocol (Handshake, Reconcile, Delta, Ack) |
| - Ed25519 Record Author Verification (Zero-Trust Enforcement)     |
+---------------------------------+---------------------------------+
                                  | Signs / Verifies Records
+---------------------------------v---------------------------------+
| airdb-crypto / AirDbKit Crypto (Zero-Trust Cryptographic Mesh)    |
| - RFC 8439 ChaCha20-Poly1305 AEAD Symmetric Cipher                |
| - RFC 8032 Ed25519 Digital Signature & Key Verification           |
| - RFC 5869 HKDF-SHA256 Key Derivation Function                    |
+---------------------------------+---------------------------------+
                                  | Encodes / Decodes
+---------------------------------v---------------------------------+
| airdb-protocol / AirDbKit Protocol (Wire Codec & MTU Framing)     |
| - 23/31-byte Header with Authenticated Additional Data (AAD)      |
| - zlib Deflate Compression (>256 B Payloads)                      |
| - 13-byte Fragment Envelope with AssemblyBuffer (30s TTL)         |
+---------------------------------+---------------------------------+
                                  | Streams Bytes
+---------------------------------v---------------------------------+
| Universal Transport Boundary (AirDbTransport)                     |
|            ^                                     ^                |
|            |                                     |                |
| +----------+--------------------+   +------------+--------------+ |
| | airdb-transport-ble           |   | airdb-transport-sim       | |
| | CoreBluetoothTransport        |   | SimulatedTransport        | |
| | (Android & Apple BLE Drivers) |   | (Sans-I/O In-Memory       | |
| | - Central + Peripheral Roles  |   |  Multi-Node Mesh Lab)     | |
| | - Single-Flight GATT Queue    |   +---------------------------+ |
| | - Backpressure Flow Control   |                                 |
| +-------------------------------+                                 |
+-------------------------------------------------------------------+
```

---

## Quick Start

### 1. Initialize Durable SQLite Adapter & Cryptographic Keys

```kotlin
// 1. Initialize SQLite persistence adapter
val adapter = SqliteAirDbAdapter(dbPath = "/data/data/com.example.app/databases/airdb.db")

// 2. Derive cluster encryption key and node signing key pair
val clusterKey = HkdfSha256.deriveKey(
    ikm = "emergency-posko-cluster-shared-secret".toByteArray(Charsets.UTF_8),
    salt = "airdb-mesh-v1".toByteArray(Charsets.UTF_8),
    info = "cluster-aead-key".toByteArray(Charsets.UTF_8)
)
val authorKeyPair = Ed25519Signer.generateKeyPair()

// 3. Initialize transport and engine
val transport = AndroidBleTransport(context)
val engine = AirDbEngine(
    nodeName = "posko-merapi-1",
    adapter = adapter,
    transport = transport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

// 4. Register sync completion listener
engine.addSyncListener { peerId, changeCount ->
    Log.i("AirDB", "Applied $changeCount verified mutations from peer: $peerId")
}

// 5. Start radio operations
engine.start()

// 6. Record a local mutation (automatically signed with authorKeyPair)
val survivorJson = """{"name": "Budi", "status": "Rescued", "triage": "GREEN"}"""
engine.saveRecord(
    collection = "survivors",
    id = "survivor-101",
    type = MutationType.INSERT,
    payload = survivorJson.toByteArray(Charsets.UTF_8)
)
```

### 2. Apple Platforms (Swift / AirDbKit)

```swift
import AirDbKit

// 1. Initialize durable native SQLite persistence
let dbPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    .appendingPathComponent("airdb.db").path
let adapter = try SqliteDbAdapter(databasePath: dbPath)

// 2. Generate or load cryptographic keys
let clusterKey = try HkdfSha256.deriveKey(
    secret: "emergency-posko-cluster-shared-secret".data(using: .utf8)!,
    salt: "airdb-mesh-v1".data(using: .utf8)!,
    info: "cluster-aead-key".data(using: .utf8)!
)
let authorKeyPair = try Ed25519Signer.generateKeyPair()

// 3. Initialize CoreBluetooth transport & engine
let transport = CoreBluetoothTransport()
let engine = AirDbEngine(
    nodeName: "posko-merapi-ios-01",
    adapter = adapter,
    transport = transport,
    clusterKey: clusterKey,
    authorKeyPair: authorKeyPair,
    enforceSignatures: true
)

// 4. Start radio operations and record mutations
engine.start()

let payload = """{"name": "Siti", "status": "Treated"}""".data(using: .utf8)!
let record = try await engine.saveRecord(
    collection: "survivors",
    id: "survivor-102",
    type: .insert,
    payload: payload
)
```

---

## Modules

| Module | Description | Dependencies |
|---|---|---|
| [`airdb-adapter-api`](airdb-adapter-api/) | Core database abstraction contracts (`AirDbAdapter`, `ChangeRecord`). | Pure Kotlin |
| [`airdb-adapter-sqlite`](airdb-adapter-sqlite/) | Durable SQLite persistence engine with WAL mode and schema migrations. | Kotlin / SQLite3 |
| [`airdb-crypto`](airdb-crypto/) | RFC 8439 ChaCha20-Poly1305, RFC 8032 Ed25519 signatures, RFC 5869 HKDF. | Pure Kotlin / Java 17 JCA |
| [`airdb-protocol`](airdb-protocol/) | Binary packet framing, AEAD AAD binding, zlib compression, and MTU fragmentation buffer. | Pure Kotlin |
| [`airdb-core`](airdb-core/) | HLC, Vector Clock, and 4-phase synchronization state machine with signature verification. | Pure Kotlin |
| [`airdb-transport-api`](airdb-transport-api/) | P2P transport interfaces, events, and commands. | Pure Kotlin |
| [`airdb-transport-sim`](airdb-transport-sim/) | In-memory packet radio mesh simulator for fast automated tests. | Pure Kotlin |
| [`airdb-transport-ble`](airdb-transport-ble/) | Production Android BLE GATT server/client and anti-race queue. | Android SDK / Kotlin |
| [`airdb-samples`](airdb-samples/) | Runnable CLI simulation demos demonstrating multi-node sync. | Kotlin |
| [`AirDbKit`](AirDbKit/) | Native Swift package for iOS, macOS, watchOS with CoreBluetooth, native SQLite3, and CryptoKit. | Swift 5.9+ / Apple SDK |

---

## Testing

AirDB embraces a **Sans-I/O testing discipline**. You can run the entire JVM test suite without physical hardware:

```bash
./gradlew test
```

To run the Apple test suite on macOS:

```bash
cd AirDbKit && swift test
```

To run the interactive off-grid disaster response sync simulation:

```bash
./gradlew :airdb-samples:airdb-sim-demo:run
```

---

## Documentation Directory

- [Introduction & Overview](docs/about/index.md): What AirDB is, why off-grid sync matters, and core pillars.
- [Before You Start](docs/about/before-you-start.md): Mental models, BLE bandwidth realities, and platform requirements.
- [Getting Started & Quickstart](docs/getting-started/quickstart.md): Step-by-step 5-minute initialization for Kotlin and Swift.
- [Engine Manual](docs/manual/index.md): Concurrency, Sans-I/O, and architectural breakdown.
- [Bring Your Own Database (BYOD)](docs/manual/persistence/index.md): SQLite, Room, SQLDelight, GRDB, SwiftData, and CoreData integration.
- [How Synchronization Works](docs/manual/causality/how-sync-works.md): Hybrid Logical Clocks (HLC), Vector Clocks, and convergence proofs.
- [Security & Cryptographic Mesh](docs/manual/cryptography/security-mesh.md): ChaCha20-Poly1305 AEAD, Ed25519 digital signatures, and HKDF.
- [Wire Protocol & BLE Networking](docs/manual/networking/wire-and-ble.md): Packet framing, compression, MTU fragmentation, and queue concurrency.
- [Testing Without Hardware](docs/manual/simulation/testing-with-simulator.md): Sans-I/O in-memory simulation engine.
- [Frequently Asked Questions (FAQ)](docs/about/faq.md): Answers to operational, security, and design questions.
- [Security Policy](docs/community/security.md): Threat modeling, cryptographic guarantees, and disclosure procedures.
- [Coding Standards](docs/community/coding-standards.md): Guidelines on concurrency, immutability, and tests.
- [Architecture Decision Records (ADRs)](docs/community/adr/): Design rationales and trade-offs:
  - [ADR 0001: Sans-I/O Architecture](docs/community/adr/0001-sans-io-architecture.md)
  - [ADR 0002: HLC Conflict Resolution](docs/community/adr/0002-hlc-conflict-resolution.md)
  - [ADR 0003: Single-Flight GATT Queue](docs/community/adr/0003-single-flight-gatt-queue.md)
  - [ADR 0004: Durable SQLite Persistence Seam](docs/community/adr/0004-sqlite-persistence-seam.md)
  - [ADR 0005: Zero-Trust Cryptographic Mesh](docs/community/adr/0005-zero-trust-cryptographic-mesh.md)
- [Wire Protocol Specification](docs/reference/wire-spec.md): Formal binary packet layout table and bitmasks.
- [API Contracts Reference](docs/reference/api-contracts.md): Cheat sheet for Kotlin and Swift interfaces.

---

## Acknowledgements & Prior Art

AirDB's protocols, architecture, and design decisions were informed and inspired by foundational research and open-source projects across distributed systems, cryptography, and wireless mesh networks:

- **BitChat**: For demonstrating the feasibility of ad-hoc mobile Bluetooth Low Energy (BLE) mesh communication, dual-role GATT operation, and opportunistic store-and-forward relay mechanics in off-grid environments. AirDB is a clean-room implementation engineered specifically for structured database replication and causal consistency (CRDT/HLC), rather than ephemeral messaging.
- **Hybrid Logical Clocks (HLC)**: Based on the research paper *"Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases"* by Sandeep Kulkarni, Murat Demirbas, David Madeppa, Bharath Balasubramanian, and Sugumar Radhakrishnan (2014).
- **Vector Clocks & Distributed Ordering**: Grounded in the foundational research of Leslie Lamport (1978), Colin Fidge (1988), and Friedemann Mattern (1988) on causal event ordering, partial order lattices, and state reconciliation.
- **The Local-First Software Movement**: Inspired by the manifesto *"Local-First Software: You Own Your Data, in spite of the Cloud"* by Martin Kleppmann, Adam Wiggins, Peter van Hardenberg, and Mark McGranaghan (Ink & Switch, 2019).
- **SQLite Engine**: Informed by D. Richard Hipp's architectural principles of crash resilience, Write-Ahead Logging (WAL), and zero-dependency embedded persistence.
- **RFC Cryptographic Standards**: RFC 8439 (ChaCha20-Poly1305 AEAD), RFC 8032 (Ed25519 Digital Signatures), and RFC 5869 (HKDF-SHA256).

---

## License

AirDB is licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for details.
