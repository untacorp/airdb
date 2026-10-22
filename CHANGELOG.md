# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.1] - 2026-10-22

### Added
- **AeroLog Native Storage Engine (`airdb-adapter-aerolog`)**:
  - High-throughput Append-Only Bitcask persistence adapter wrapping pure-Rust AeroLog via Mozilla UniFFI.
  - Over 200,000 sequential writes/sec with hardware-accelerated CRC32-IEEE checksums and zero random flash I/O.
  - Single-seek in-memory `KeyDir` indexing with sub-15-microsecond point read latency.
  - Native Hybrid Logical Clock (HLC) physical-logical tuple alignment.
  - Automatic segment compaction and tombstone garbage collection past prune HLC thresholds.
- **Apple Platform BYOD Integration Seam**:
  - Comprehensive documentation and reference implementation for Bring-Your-Own-Database (BYOD) AeroLog integration on iOS and macOS.
  - Enforced Invariant 6 preserving zero third-party SPM dependencies in `AirDbKit`.
- **Visual Performance & Architecture Metrics**:
  - Modernized MkDocs architecture diagrams using Mermaid and `xychart-beta` visual throughput charts.

### Fixed
- **Consolidated Audit Resolutions (Issues #9 to #20 via PR #33)**:
  - **Reconciliation Starvation (#9)**: Added continuous delta batch streaming to prevent peer starvation under heavy write workloads.
  - **BLE Transport Wire Framing (#10)**: Corrected packet encryption layering and AEAD header binding across Android and Apple transports.
  - **SQLite Changelog Scan OOM (#11)**: Implemented chunked pagination cursor (`readChangelogSince`) to prevent heap exhaustion.
  - **HLC Canonical Ordering (#12)**: Standardized HLC comparator parsing and total order across Kotlin and Swift.
  - **GATT Operation Queue Watchdog (#13, #14)**: Added retry budget limits and watchdog timers to prevent Android BLE queue hangs.
  - **AeroLog Changelog Ceiling (#15)**: Added 10,000-entry safety fetch ceiling on stream scans to protect memory bounds.
  - **CoreBluetooth ATT Write Drain (#16)**: Added backpressure drain tracking `peripheralIsReady(toSendWriteWithoutResponse:)`.
  - **Zip-Bomb Protection (#17)**: Enforced strict 64KB max decompressed payload budget in `BinaryCodec`.
  - **SQLite Tombstone Indexing (#18)**: Added composite `(physical_time, logical_counter, origin_node)` indexes and atomic changelog compaction.
  - **Swift Concurrency Hardening (#19)**: Replaced unstructured detached tasks with structured actor tasks and clean lifecycle cancellation.
  - **Kotlin Concurrency Alignment (#20)**: Aligned coroutine dispatchers and lifecycle management with Kotlin Multiplatform standards.
- **AeroLog Cache & Tombstone Compaction**:
  - Restored O(1) `maxSeenHlc` tracking in `AeroLogAirDbAdapter` on `appendChangelog` and `applyChanges`.
  - Implemented KeyDir tombstone purging before executing Bitcask segment compaction.

### Refactored
- **Linux Kernel Filter Discipline (PR #34)**:
  - Stripped narrative comments and cognitive bloat across Kotlin and Swift codebases.
  - Flattened nested conditionals using guard clauses and bouncer pattern (Linus' 3-indent law).
  - Normalized variable naming, replacing cryptic abbreviations (`pt`, `lc`, `nh`, `mut`) and corporate filler with precise domain nouns (`physicalTime`, `logicalCounter`, `originNode`, `nodeHash`).
  - Achieved net-negative lines while maintaining 100% test pass rates across both Linux and macOS CI.

---

## [0.2.0] - 2026-10-21

### Added
- **Zero-Trust Cryptographic Mesh (`airdb-crypto` & `AirDbKit/Crypto`)**:
  - RFC 8439 ChaCha20-Poly1305 AEAD symmetric cipher using Java 17 JCA on Android/JVM and Apple `CryptoKit` on Apple platforms.
  - RFC 8032 Ed25519 digital signatures with canonical byte serialization for author provenance and non-repudiation across untrusted mesh relay nodes.
  - RFC 5869 HKDF-SHA256 key derivation utility for cluster and session key derivation.
  - Wire protocol `FLAG_ENCRYPTED = 0x04` binding full 23B/31B packet headers as Authenticated Additional Data ($AAD$).
  - Delta batch `FLAG_RECORD_SIGNED = 0x80` encoding 32-byte author public key and 64-byte Ed25519 signature.
  - `enforceSignatures` zero-trust validation in `SyncSession` dropping forged or tampered records during reconciliation.
- **Durable SQLite Persistence (`airdb-adapter-sqlite` & `SqliteDbAdapter`)**:
  - Production-grade SQLite adapter for Android and JVM with Write-Ahead Logging (WAL mode), immediate transactions, and indexed HLC ranges.
  - Native Swift SQLite adapter (`SqliteDbAdapter`) using Apple `libsqlite3` with zero third-party package dependencies.
  - Anti-resurrection soft-delete tombstone lifecycle management.
- **Apple Platform Support (`AirDbKit`)**:
  - Native Swift package conforming to 100% on-wire protocol parity with Android.
  - `CoreBluetoothTransport` dual-role driver (Central and Peripheral) with backpressure drain queue.
  - Structured Swift concurrency (`actor`, `async/await`, `Sendable`) ensuring compile-time thread safety.
- **Production Android BLE Driver (`airdb-transport-ble`)**:
  - `AndroidBleTransport` implementing single-flight `GattOperationQueue` with backpressure bounds to prevent Android GATT race conditions.
  - `AndroidBlePermissions` helper matrix handling Android 12+ runtime permissions and legacy location permissions.
- **Continuous Integration Hardening**:
  - Dual-platform CI matrix covering Linux (OpenJDK 17) and macOS ARM64 (Swift 5.9+ / Xcode).
  - Least-privilege GitHub Actions workflow configuration (`permissions: contents: read`).
  - GitHub Actions upgraded to Node 24 runtime actions.

---

## [0.1.0] - 2026-10-20

### Added
- **Core Engine (`airdb-core`)**:
  - Monotonic Hybrid Logical Clock (`HybridLogicalClock`, `HlcTimestamp`) with deterministic tie-breaking.
  - Multi-node `VectorClock` state tracker with compact binary serialization.
  - 4-Phase peer reconciliation state machine (`SyncSession`) managing Handshake, Reconcile, Delta Push, and Ack.
  - Top-level `AirDbEngine` coordinator facade.
- **Protocol & Wire Format (`airdb-protocol`)**:
  - Compact 23-byte fixed header binary framing (`AirDbPacket`) with network byte order.
  - Automatic payload Deflate/zlib compression for payloads exceeding 256 bytes.
  - MTU-aware fragment splitter (`PacketFragmenter`) and TTL-managed reassembler (`AssemblyBuffer`).
- **Database Adapter Seam (`airdb-adapter-api`)**:
  - `AirDbAdapter` interface for decoupling from local database engines.
  - Standard `ChangeRecord` mutation envelope supporting INSERT, UPDATE, and DELETE operations.
  - Reference `MemoryDbAdapter` for high-speed deterministic tests and demonstrations.
- **Transport Abstraction (`airdb-transport-api` & `airdb-transport-sim`)**:
  - Universal `AirDbTransport` contract with link events and non-blocking packet send.
  - In-memory `SimulatedNetwork` and `SimulatedTransport` for instant multi-node test simulation.
- **BLE Transport (`airdb-transport-ble`)**:
  - Strict single-flight `GattOperationQueue` with backpressure bounds to prevent Android GATT race conditions.
  - `BleConfig` defining UUIDs, 517-byte target MTU, and transmission policies.
- **Samples**:
  - `airdb-sim-demo`: Runnable disaster relief emergency posko sync scenario demonstrating causal data convergence.
