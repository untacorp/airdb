# Contributing to AirDB

Thank you for your interest in contributing to AirDB!

AirDB is an open-source project designed to provide reliable, local-first database synchronization over Bluetooth Low Energy. We welcome bug reports, feature proposals, documentation improvements, and pull requests.

---

## 1. Zero-Emoji Policy

This repository strictly enforces a **Zero-Emoji Policy**:
- Do not use emoji characters anywhere: in source code, inline comments, docstrings, commit messages, PR descriptions, or documentation files.
- Automated CI scanners will reject any commits or pull requests containing emoji characters.
- Use clear headings, bullet points, and GitHub-style callouts (`[!NOTE]`, `[!TIP]`, `[!WARNING]`) for visual structure.

---

## 2. Commit Message Convention

AirDB follows the [Conventional Commits v1.0.0](https://www.conventionalcommits.org/) specification:

```text
<type>(<scope>): <short summary in imperative mood>

[optional body explaining motivation and technical decisions]

[optional footer for issue references]
```

### Allowed Types
- `feat`: New user-facing feature or API capability.
- `fix`: Bug fix or edge case resolution.
- `perf`: Performance improvement (allocations, latency, throughput).
- `docs`: Documentation updates.
- `test`: Adding or correcting tests.
- `refactor`: Code change that neither fixes a bug nor adds a feature.
- `ci`: CI/CD workflows and build automation.
- `chore`: Tooling, version bumps, or dependency updates.

### Allowed Scopes
- `core`: State engine, HLC, Vector Clock, reconciliation.
- `protocol`: Binary framing, codecs, compression, fragmentation.
- `adapter`: Persistence seam, SQLite, Room, GRDB, SwiftData.
- `crypto`: ChaCha20-Poly1305, Ed25519, HKDF.
- `ble-android`: Android BLE transport and `GattOperationQueue`.
- `ble-apple`: Apple `AirDbKit` and `CoreBluetoothTransport`.
- `sim`: Virtual simulation network and transports.

---

## 3. Pull Request Guidelines

1. **Keep Pull Requests Focused**: A pull request should address a single bug fix or feature.
2. **Include Tests**:
   - For Kotlin modules: Add unit tests under `src/test/kotlin`.
   - For `AirDbKit`: Add tests under `AirDbKit/Tests`.
3. **Verify Locally Before Submitting**:
   ```bash
   cd airdb
   ./gradlew test
   ```
   For Apple platforms:
   ```bash
   cd airdb/AirDbKit
   swift test
   ```
4. **Sign Off Your Work**: Ensure all commits include your developer signature (`git commit -s`).
