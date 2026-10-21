# List of Features

This page provides a comprehensive summary of all capabilities built into AirDB.

---

## 1. Local Persistence & Storage

- **Built-in Durable SQLite Engine**:
  - Out-of-the-box storage engine for Android/JVM (`SqliteDbAdapter`) and Apple platforms (`SqliteDbAdapter` in `AirDbKit`).
  - Automatically enables Write-Ahead Logging (`PRAGMA journal_mode = WAL`) and normal disk syncing (`PRAGMA synchronous = NORMAL`) for concurrent, non-blocking reads and writes.
  - Automatically manages indexed changes in an append-only changelog table.
- **Pluggable BYOD (Bring Your Own Database) Seam**:
  - Connect your existing application database via the simple `AirDbAdapter` interface.
  - Ready-to-use recipes for Android Jetpack Room, SQLDelight, Apple GRDB.swift, SwiftData, CoreData, and in-memory key-value maps.
- **Soft-Delete Tombstone Lifecycle**:
  - Records deleted locally retain an authoritative tombstone timestamp.
  - Outdated mutations from disconnected devices are safely dropped, preventing deleted entities from resurrecting.

---

## 2. Distributed Synchronization & Causality

- **Hybrid Logical Clocks (HLC)**:
  - Generates strictly monotonic timestamps combining physical wall-clock time, an internal counter, and a node tie-breaker.
  - Tolerates device clock drift, dead batteries, and devices resetting to the year 1970 without corrupting ordering.
- **Vector Clock State Tracking**:
  - Every device maintains a compact knowledge map of the latest timestamp seen from every peer.
  - When two devices meet, they compute the exact missing delta set in $\mathcal{O}(|\Delta|)$ instead of transferring the entire database.
- **Deterministic Conflict Resolution (Last-Write-Wins)**:
  - When two users edit the same entity while separated, the change with the higher HLC timestamp wins deterministically across all nodes.
  - Guarantees eventual mathematical convergence without needing central consensus rounds.

---

## 3. Zero-Trust Cryptographic Mesh

- **Cluster-Level AEAD Encryption (RFC 8439)**:
  - Protects data in transit using symmetric ChaCha20-Poly1305 encryption with a 256-bit cluster key.
  - The entire binary packet header is bound into the authentication tag as Authenticated Additional Data ($AAD$), immediately rejecting tampered headers or spoofed sender IDs.
  - Generates a fresh 12-byte CSPRNG nonce for every transmitted packet.
- **Record-Level Digital Signatures (RFC 8032)**:
  - The original author digitally signs each mutation using an Ed25519 private key.
  - Prevents untrusted or compromised intermediate gossip relay phones from tampering with, forging, or backdating records.
- **Key Derivation (RFC 5869)**:
  - Derives operational cluster keys and session keys deterministically using HKDF-SHA256.

---

## 4. Bluetooth Networking & Framing

- **Dual-Role BLE Concurrency**:
  - Devices simultaneously operate as a GATT Central (scanning for peers) and a GATT Peripheral (advertising presence).
- **Single-Flight GATT Queue (`GattOperationQueue`)**:
  - Enforces strict one-at-a-time operation dispatching per link on Android.
  - Eliminates native Fluoride/BlueDroid driver packet drops and race conditions.
- **Swift Actor Concurrency**:
  - On Apple platforms, `AirDbKit` runs the core engine inside a Swift `actor` for compile-time thread safety and non-blocking CoreBluetooth callbacks.
- **Automatic Payload Compression**:
  - Payloads exceeding 256 bytes are automatically compressed with zlib Deflate, cutting transmission airtime and battery consumption by up to 70%.
- **MTU Fragmentation & Assembly Buffer**:
  - Automatically slices packets larger than the link MTU into 13-byte fragment envelopes.
  - Reassembles chunks on the receiver with a sliding 30-second TTL to prevent memory leaks from abandoned connections.

---

## 5. Sans-I/O Simulation & Developer Experience

- **Zero-Radio In-Memory Simulation**:
  - Core synchronization logic is 100% decoupled from hardware sockets and OS drivers.
  - Simulate multi-node mesh networks with variable packet drop rates and delays directly in JVM unit tests in milliseconds.
- **Zero Third-Party Dependencies on Apple**:
  - `AirDbKit` relies exclusively on Apple standard frameworks (`CryptoKit`, `CoreBluetooth`, `Compression`, and native `libsqlite3`).
- **Interactive CLI Demo**:
  - Includes a runnable multi-node disaster relief simulation (`:airdb-samples:airdb-sim-demo`) demonstrating real-time offline edits, peer discovery, and convergence.
