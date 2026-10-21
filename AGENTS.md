# AirDB Agent & Architecture Guidelines

Welcome to **AirDB**. This file outlines mandatory architectural constraints, coding standards, and design invariants that all AI agents and human contributors must strictly uphold across sessions.

---

## 1. Core Architectural Invariants

### Invariant 1: Sans-I/O Discipline
* `airdb-core`, `airdb-protocol`, `airdb-crypto`, and `airdb-adapter-api` must remain **100% pure Kotlin** with zero dependencies on Android SDK (`android.*`) or platform socket APIs.
* Business logic, state machines, clocks, and binary protocols must be implemented as pure functions:

```math
\text{handle}(\text{Event}, \text{State}) \to (\text{State}', [\text{Effects}])
```
* All platform-specific I/O belongs exclusively to `airdb-transport-ble` (or future `airdb-transport-wifi`, etc.).

### Invariant 2: Deterministic Testability Without Hardware
* Never require a physical Bluetooth radio or Android emulator to verify synchronization algorithms.
* Use `airdb-transport-sim` and `SimulatedTransport` to test all multi-node scenarios (partitioning, packet loss, duplicate packets, reordering).
* All unit and integration tests must run deterministically in standard CI pipelines in under 10 seconds.

### Invariant 3: Single-Flight GATT Rule (Android BLE Constraint)
* The Android Bluetooth stack (Fluoride/BlueDroid) permits only **1 in-flight GATT operation** per peripheral link.
* In `airdb-transport-ble`, every outbound write or notification MUST be queued through `GattOperationQueue`. The next operation is triggered ONLY upon receiving the OS confirmation callback (`onCharacteristicWrite` or `onNotificationSent`).
* Never call `writeCharacteristic` or `notifyCharacteristicChanged` directly from application threads.

### Invariant 4: Monotonic Causality & LWW Merge
* Conflicts are resolved deterministically using **Hybrid Logical Clocks (HLC)**.
* When two mutations have conflicting values for the same key/record:
  1. Compare `HlcTimestamp.physicalTime`
  2. Compare `HlcTimestamp.logicalCounter`
  3. Compare `HlcTimestamp.nodeId` (lexicographical tie-breaker)
* Every node applying the same set of mutations must arrive at the exact same database state regardless of arrival order.

### Invariant 5: Zero-Trust Cryptographic Provenance
* When `enforceSignatures = true`, inbound `ChangeRecord` mutations MUST be verified against the author's Ed25519 public key before invoking `AirDbAdapter.applyChanges()`.
* Untrusted or compromised intermediate mesh relay nodes must never be capable of forging records or tampering with HLC timestamps undetected.
* AEAD encryption (ChaCha20-Poly1305) MUST bind the complete 23/31-byte wire header as Authenticated Additional Data ($AAD$).

### Invariant 6: Zero Third-Party SPM Dependencies on Apple Platforms
* `AirDbKit` must strictly avoid third-party dependencies in `Package.swift`.
* Use native Apple standard library frameworks exclusively: `CryptoKit`, `Compression`, `CoreBluetooth`, and `libsqlite3`.

---

## 2. Code Quality & Conventions

1. **Language**: All code, comments, documentation, and commit messages must be written in **English**.
2. **KDoc Coverage**: All public interfaces, classes, actors, and methods must have comprehensive KDoc/SwiftDoc documentation explaining their parameters, return values, thread-safety, and exception behavior.
3. **Immutability by Default**: Use `data class` in Kotlin and `struct` in Swift. Favor persistent or copy-on-write state representations.
4. **License Header**: All new source files must begin with the standard Apache-2.0 copyright header.
5. **No Deadlocks**: Never perform blocking I/O or `runBlocking` inside transport delegate callbacks or event loops. Use Kotlin Coroutines and Swift structured concurrency.
6. **Zero-Emoji Policy**: Strictly zero emojis across all code, tests, commit messages, and documentation.
7. **Commit Standards**: Strictly follow Conventional Commits v1.0.0 as documented in `CONTRIBUTING.md`.

---

## 3. Engineering Roadmap & Track Status

- **Track 0 (Native Apple Interoperability - AirDbKit)**: COMPLETE. `AirDbKit` Swift package implemented, tested, and passing CI with 100% on-wire parity and structured concurrency.
- **Track 1 (Production Android BLE Driver & CI/CD)**: COMPLETE. `AndroidBleTransport` implemented with `GattOperationQueue`, dynamic `android.jar` resolution, permissions helper, and GitHub Actions CI.
- **Track 2 (Durable Persistence Engine - SQLite WAL)**: COMPLETE. `SqliteAirDbAdapter` on JVM/Android and `SqliteDbAdapter` on Apple platforms with WAL mode, atomic transactions, and tombstone lifecycle.
- **Track 3 (Zero-Trust Cryptographic Mesh)**: COMPLETE. RFC 8439 ChaCha20-Poly1305 AEAD, RFC 8032 Ed25519 digital signatures, RFC 5869 HKDF-SHA256, packet header AAD binding, signed delta batch format, and `enforceSignatures` session verification.
- **Track 4 (Physical Radio Field Benchmarking & Performance Tuning)**: NEXT TARGET. Real hardware testbed across Android and Apple devices measuring RSSI attenuation, BLE 4.2 vs 5.0 PHY throughput, packet drop resilience, and battery impact.
- **Track 5 (Formal Academic Whitepaper Publication)**: FUTURE. Comprehensive technical paper with formal mathematical proofs, benchmark graphs, and replication protocol specifications.
