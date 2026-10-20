# Contributing to AirDB

Thank you for your interest in contributing to AirDB! We welcome contributions from developers, researchers, and field operators worldwide.

---

## Code of Conduct
By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Development Setup

1. **Prerequisites**:
   - Java 17+ JDK installed.
   - Android Studio (Jellyfish+) or IntelliJ IDEA (2024.1+).
2. **Build and Test**:
   ```bash
   git clone https://github.com/your-org/airdb.git
   cd airdb
   ./gradlew test
   ```
3. **Run Simulated Demo**:
   ```bash
   ./gradlew :airdb-samples:airdb-sim-demo:run
   ```

---

## Pull Request Guidelines

1. **Adhere to Invariants**: Ensure all changes conform to [AGENTS.md](AGENTS.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
2. **Include Unit Tests**: Every bugfix or new feature must include automated tests in the appropriate module.
3. **Commit Style**: Use conventional commits (e.g. `feat(core): add vector clock pruning`, `fix(protocol): handle 0-byte fragment payload`).
4. **License**: All contributions fall under the project's Apache License 2.0.
