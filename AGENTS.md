# AirDB Agent & Architecture Guidelines

Welcome to **AirDB**. This file outlines mandatory architectural constraints, coding standards, and design invariants that all AI agents and human contributors must strictly uphold across sessions.

---

## 1. Core Architectural Invariants

### Invariant 1: Sans-I/O Discipline
* `airdb-core`, `airdb-protocol`, and `airdb-adapter-api` must remain **100% pure Kotlin** with zero dependencies on Android SDK (`android.*`) or platform socket APIs.
* Business logic, state machines, clocks, and binary protocols must be implemented as pure functions:
  $$\text{handle}(Event, State) \to (State', [Effects])$$
* All platform-specific I/O belongs exclusively to `airdb-transport-ble` (or future `airdb-transport-wifi`, etc.).

### Invariant 2: Deterministic Testability Without Hardware
* Never require a physical Bluetooth radio or Android emulator to verify synchronization algorithms.
* Use `airdb-transport-sim` to test all multi-node scenarios (partitioning, packet loss, duplicate packets, reordering).
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

---

## 2. Code Quality & Conventions

1. **Language**: All code, comments, documentation, and commit messages must be written in **English**.
2. **KDoc Coverage**: All public interfaces, classes, and methods must have comprehensive KDoc documentation explaining their parameters, return values, thread-safety, and exception behavior.
3. **Immutability by Default**: Use `data class` with `val` properties. Favor persistent or copy-on-write state representations.
4. **License Header**: All new source files must begin with the standard Apache-2.0 copyright header.
5. **No Deadlocks**: Never perform blocking I/O or `runBlocking` inside transport delegate callbacks or event loops. Use Kotlin Coroutines and non-blocking channels.
