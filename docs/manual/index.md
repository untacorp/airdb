# AirDB Manual

Welcome to the AirDB technical manual. This section provides an in-depth look at how each layer of the engine operates.

---

## Architectural Map

AirDB is structured into five distinct, decoupled layers:

```mermaid
flowchart TD
    L1["<b>1. Application Layer</b><br/>Domain Entities • Disaster Triage<br/>POS Registers • Sensor & Audit Logs"]
    L2["<b>2. Persistence Seam (AirDbAdapter)</b><br/>• Durable SQLite with WAL mode<br/>• Out-of-the-box Android & Apple support<br/>• Pluggable: AeroLog, Room, GRDB, SQLDelight"]
    L3["<b>3. Causality & Synchronization Core</b><br/>• Hybrid Logical Clocks for deterministic ordering<br/>• Vector Clocks for peer delta tracking<br/>• 4-Phase Sync Protocol (Handshake, Delta, Ack)<br/>• Last-Write-Wins conflict resolution & tombstones"]
    L4["<b>4. Zero-Trust Cryptographic Mesh</b><br/>• RFC 8439 ChaCha20-Poly1305 AEAD encryption<br/>• Header AAD binding for instant tamper rejection<br/>• RFC 8032 Ed25519 author digital signatures<br/>• RFC 5869 HKDF-SHA256 key derivation schedule"]
    L5["<b>5. Wire Codecs & Radio Transports</b><br/>• Binary framing & zlib Deflate compression<br/>• MTU chunking with 30s sliding AssemblyBuffer<br/>• Dual-role BLE concurrency (Central + Peripheral)<br/>• Android single-flight queue & Apple actor<br/>• Sans-I/O in-memory simulator for unit tests"]

    L1 --> L2
    L2 --> L3
    L3 --> L4
    L4 --> L5
```

---

## Manual Chapters

- **[Storage & Bring Your Own Database (BYOD)](persistence/index.md)**: How AirDB interacts with local storage engines, and guides for SQLite, AeroLog (Bitcask), Room, SQLDelight, GRDB, SwiftData, and CoreData.
- **[How Synchronization Works](causality/how-sync-works.md)**: Deep dive into Hybrid Logical Clocks (HLC), vector clocks, the 4-phase synchronization handshake, and tombstone lifecycle.
- **[Security & Cryptographic Mesh](cryptography/security-mesh.md)**: How AirDB secures data over the air using ChaCha20-Poly1305 and guarantees record provenance across untrusted mesh relays with Ed25519 digital signatures.
- **[Networking & Bluetooth Transports](networking/wire-and-ble.md)**: How packets are framed, compressed, fragmented, and delivered over Bluetooth Low Energy without native driver race conditions.
- **[Testing Without Hardware](simulation/testing-with-simulator.md)**: How to use the Sans-I/O in-memory simulator to test multi-node sync topologies in automated test suites in milliseconds.
