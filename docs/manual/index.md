# AirDB Manual

Welcome to the AirDB technical manual. This section provides an in-depth look at how each layer of the engine operates.

---

## Architectural Map

AirDB is structured into five distinct, decoupled layers:

```
+-------------------------------------------------------------------+
| 1. Application Layer (Domain Entities, Triage, Sales, Inventory)  |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
| 2. Persistence Seam (AirDbAdapter)                                |
|    - Durable SQLite with WAL mode (Android & Apple)               |
|    - Pluggable Adapters: Room, SQLDelight, GRDB, SwiftData        |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
| 3. Causality & Synchronization (AirDbEngine & SyncSession)        |
|    - Hybrid Logical Clocks (HLC) for deterministic total ordering |
|    - Vector Clocks for tracking peer state & computing delta sets |
|    - 4-Phase Sync Protocol (Handshake, Clock, Delta, Ack)         |
|    - Last-Write-Wins (LWW) conflict resolution & tombstones       |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
| 4. Zero-Trust Cryptographic Mesh                                  |
|    - RFC 8439 ChaCha20-Poly1305 AEAD (Cluster link encryption)    |
|    - Header Authenticated Additional Data (AAD) tamper rejection  |
|    - RFC 8032 Ed25519 digital signatures (Record author proof)    |
|    - RFC 5869 HKDF-SHA256 key derivation schedule                 |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
| 5. Wire Codecs & Radio Transports                                 |
|    - Binary packet framing (AirDbPacket) & zlib compression (>256B)|
|    - MTU fragmentation with 30s sliding AssemblyBuffer            |
|    - Dual-role BLE concurrency (Central + Peripheral)             |
|    - Android single-flight GattOperationQueue & Apple Swift Actor |
|    - Sans-I/O in-memory simulator for instant testing             |
+-------------------------------------------------------------------+
```

---

## Manual Chapters

- **[Storage & Bring Your Own Database (BYOD)](persistence/index.md)**: How AirDB interacts with local storage engines, and guides for SQLite, Room, SQLDelight, GRDB, SwiftData, and CoreData.
- **[How Synchronization Works](causality/how-sync-works.md)**: Deep dive into Hybrid Logical Clocks (HLC), vector clocks, the 4-phase synchronization handshake, and tombstone lifecycle.
- **[Security & Cryptographic Mesh](cryptography/security-mesh.md)**: How AirDB secures data over the air using ChaCha20-Poly1305 and guarantees record provenance across untrusted mesh relays with Ed25519 digital signatures.
- **[Networking & Bluetooth Transports](networking/wire-and-ble.md)**: How packets are framed, compressed, fragmented, and delivered over Bluetooth Low Energy without native driver race conditions.
- **[Testing Without Hardware](simulation/testing-with-simulator.md)**: How to use the Sans-I/O in-memory simulator to test multi-node sync topologies in automated test suites in milliseconds.
