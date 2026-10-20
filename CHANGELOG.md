# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-10-20

### Added
- **Core Engine (`airdb-core`)**:
  - Monotonic Hybrid Logical Clock (`HybridLogicalClock`, `HlcTimestamp`) with deterministic tie-breaking.
  - Multi-node `VectorClock` state tracker with compact binary serialization.
  - 4-Phase peer reconciliation state machine (`SyncSession`) managing Handshake, Reconcile, Delta Push, and Ack.
  - Top-level `AirDbEngine` coordinator facade.
- **Protocol & Wire Format (`airdb-protocol`)**:
  - Compact 15-byte fixed header binary framing (`AirDbPacket`) with network byte order.
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
