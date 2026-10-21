# Welcome to AirDB

**Decentralized, Local-First P2P Database Synchronization Engine over Bluetooth Low Energy**

*Zero Internet. Zero Central Servers. Automatic Peer Reconciliation. Cryptographic Provenance.*

---

## What is AirDB?

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
- **Durable Local Persistence (BYOD)**: Built-in production SQLite storage engines (`airdb-adapter-sqlite` on Android/JVM and `SqliteDbAdapter` on Apple platforms) with Write-Ahead Logging (WAL), indexed HLC ranges, and atomic tombstone handling. Pluggable interface for Room, SQLDelight, GRDB, SwiftData, and CoreData.
- **Serialized In-Flight GATT Queue**: Enforces single-flight operation ordering per link, eliminating native Bluetooth stack race conditions.
- **Automated Compression & Framing**: Serializes compact binary packets, automatically compressing payloads (>256 B) via zlib Deflate and partitioning packets exceeding link MTU.
- **Deterministic Sans-I/O Simulator**: Includes `airdb-transport-sim` and `SimulatedTransport` to test complete multi-node sync topologies in memory in milliseconds without hardware radios.

---

## Quick Navigation

- [Getting Started Guide](GETTING_STARTED.md): Step-by-step setup for Android & Apple.
- [Architecture & Causality Math](ARCHITECTURE.md): Mathematical formulations for HLC, vector clocks, and convergence.
- [Wire Protocol Specification](PROTOCOL_SPEC.md): Binary frame schemas, AEAD layouts, and fragment headers.
- [BYOD Cookbooks](cookbooks/room-android.md): Guides for Room, GRDB, SwiftData, SQLDelight, CoreData, and SQLite.
- [Security Policy](SECURITY.md): Threat model and cryptographic verification invariants.
- [Architecture Decision Records (ADRs)](adr/0001-sans-io-architecture.md): Design rationales and trade-offs.
