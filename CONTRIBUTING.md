# Contributing to AirDB

Thank you for your interest in contributing to AirDB. This project is a local-first, peer-to-peer, offline database engine designed for resilient low-bandwidth Bluetooth Low Energy (BLE) environments.

To maintain engineering excellence across both Kotlin and Swift codebases, please adhere to the following standards.

---

## 1. Zero-Emoji Policy

This repository strictly enforces a **Zero-Emoji Policy**.
- Do not use emoji characters anywhere: in source code, inline comments, commit messages, PR descriptions, or documentation files.
- Automated CI linters will reject any pull requests containing emoji characters.

---

## 2. Commit Message Standardization

AirDB strictly follows the [Conventional Commits v1.0.0](https://www.conventionalcommits.org/) specification.

### Commit Format

```text
<type>(<scope>): <short summary in imperative mood>

[optional body explaining context, rationale, and technical decisions]

[optional footer for issue tracking or breaking changes]
```

### Allowed Types

- `feat`: Adds a new user-facing or platform feature.
- `fix`: Fixes a bug or handles an edge case in reconciliation or transport.
- `perf`: Improves runtime throughput, latency, or memory allocations without API changes.
- `docs`: Updates architecture documents, protocol specifications, or guides.
- `test`: Adds or fixes unit tests, integration tests, or simulation scenarios.
- `refactor`: Changes code structure without changing functional behavior.
- `ci`: Modifies GitHub Actions workflows or build automation scripts.
- `chore`: Updates dependencies, Gradle/SPM metadata, or repository tooling.

### Standard Scopes

- `core`: State store, CRDT engine, HLC (Hybrid Logical Clock), Vector Clock.
- `protocol`: Binary wire format, CBOR serialization, AssemblyBuffer, Delta framing.
- `ble-android`: `AndroidBleTransport`, `AndroidBlePermissions`, `GattOperationQueue`.
- `ble-ios`: Apple `AirDbKit`, CoreBluetooth driver (`AppleBleTransport`).
- `sim`: Virtual BLE simulation transport (`SimulatedBleTransport`).
- `adapter`: Adapter API, SQLite/Room/CoreData integration contracts.
- `arch`: Architectural definitions, formal mathematical proofs.
- `ci`: GitHub Actions workflows and build pipelines.

### Examples

Good:
- `feat(ble-android): implement AndroidBleTransport driver with single-flight GATT queue`
- `fix(core): resolve HLC comparison bug when counter values exceed 9`
- `docs(arch): formalize monotonic convergence proof under partial order`
- `test(ble-android): add AndroidBlePermissions test matrix for API 31 and legacy`

Bad:
- `update ble stuff` (missing type, scope, and context)
- `feat: fixed a bug and added tests` (mixed concerns, imperative mood violated)
- `fix: fixed bug` with emojis (violates Zero-Emoji Policy)

---

## 3. Branch Naming Conventions

Branch names should reflect the work being conducted:
- `feat/<short-description>`: New capabilities or transport drivers.
- `fix/<short-description>`: Bug fixes and edge case remediation.
- `docs/<short-description>`: Documentation improvements.
- `refactor/<short-description>`: Non-functional structural enhancements.

Example: `feat/android-ble-driver`, `fix/hlc-string-order`.

---

## 4. Code Quality and Testing Standards

Before submitting changes or pushing commits:
1. Ensure all JVM unit tests pass:
   ```bash
   ./gradlew test
   ```
2. Verify end-to-end simulation:
   ```bash
   ./gradlew :airdb-samples:airdb-sim-demo:run
   ```
3. If modifying Apple Swift packages (`AirDbKit`):
   ```bash
   cd AirDbKit && swift test
   ```
4. Verify zero compiler warnings.
5. All new source files must include the standard Apache-2.0 license header.

---

## 5. Architecture Principles

- **Sans-I/O Engine**: Keep `airdb-core` and `airdb-protocol` completely free of OS-specific I/O or platform libraries.
- **Backpressure Discipline**: Bluetooth LE operations on Android and iOS must be strictly single-flight per connection. Never call `writeCharacteristic` or `requestMtu` concurrently before receiving the corresponding callback.
- **Monotonic Causality**: State mutations must remain causally monotonic. State reconciliations must be idempotent, commutative, and associative ($A \sqcup B = B \sqcup A$).
