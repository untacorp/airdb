# Changelog

All notable changes to AirDB are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.2.0] - 2026-10-21

### Added
- **Zero-Trust Cryptographic Mesh (`airdb-crypto` & `AirDbKit/Crypto`)**:
  - RFC 8439 ChaCha20-Poly1305 AEAD symmetric cipher using Java 17 JCA on Android/JVM and Apple `CryptoKit` on Apple platforms.
  - RFC 8032 Ed25519 digital signatures with canonical byte serialization for author provenance across untrusted mesh relay nodes.
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
