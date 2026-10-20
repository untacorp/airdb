# Universal Engineering Standards & Guardrails

## 1. Professional Output & Communication Discipline
- **Zero-Emoji Policy**: Strictly no emojis anywhere—in source code, docstrings, commit messages, PR descriptions, artifacts, documentation, or chat responses. Keep communication clean, dense, and professional.
- **Language**: All code, symbol names, comments, docstrings, architectural documents, and commit messages must be in English. User communication follows user preference.
- **Precision Over Assumptions**: Never make silent assumptions regarding design decisions, power profiles, or storage backends; clarify or propose explicitly before irreversible implementation.

## 2. Version Control Discipline & Commit Standards
- **Conventional Commits v1.0.0**: Every commit must follow `<type>(<scope>): <imperative summary>`.
  - Types: `feat`, `fix`, `docs`, `perf`, `refactor`, `test`, `ci`, `chore`.
  - Scopes must reflect module boundaries (e.g., `core`, `protocol`, `ble-android`, `ble-ios`, `adapter`, `sim`, `arch`, `ci`).
- **Atomic Commits**: Each commit must address a single concern with a descriptive body explaining the why and architectural decisions.
- **Semantic Versioning Awareness**:
  - `fix`: Patch increment.
  - `feat`: Minor increment.
  - `feat!` or `BREAKING CHANGE:` footer: Major increment (protocol wire changes, API breaks).
- **Branch Naming**: Use `<type>/<short-description>` (e.g., `feat/storage-sqlite`, `fix/hlc-ordering`).

## 3. Repository Hygiene & Clean Working Tree
- **No Scratch/Stray Scripts**: Never create, leave, or commit ad-hoc verification or debug scripts (`.py`, `.sh`, `.tmp`) in the project repository root.
- **Self-Contained Automation**: All linter checks and verification pipelines must be embedded inline within CI workflows (`.github/workflows/*.yml`) or integrated into established build tools (Gradle, SPM).
- **Clean VCS State**: Always verify `git status` before and after operations; never leave uncommitted generated files or dirty working trees.

## 4. Architecture: Sans-I/O & Deep Module Seams
- **Pure Core (Sans-I/O)**: Core state machines, CRDT algorithms, logical clocks, and protocol parsers must remain 100% pure, deterministic, and free of platform I/O or operating system frameworks (`android.*`, `UIKit`, etc.).
- **Deterministic Testing Without Hardware**: Algorithms must be completely verifiable via simulated in-memory transports (`SimulatedTransport`) in standard CI without hardware radios or emulators.
- **Deep Seams**: Abstract external boundaries (persistence, network transport, UI) behind clean interfaces (e.g., `AirDbAdapter`, `AirDbTransport`) so that engines can be tested in total isolation.

## 5. Concurrency, Backpressure & Hardware Constraints
- **Single-Flight Hardware Discipline**: Radio interfaces (like BLE GATT) and serial hardware often do not support concurrent calls. All asynchronous hardware interactions must be serialized through an explicit single-flight backpressure queue (`GattOperationQueue`).
- **Non-Blocking Execution**: Never invoke blocking calls (`Thread.sleep`, `runBlocking`) inside event loops or transport delegate callbacks. Use structured concurrency (Kotlin Coroutines / Swift Actors).
- **Immutability by Default**: Models and delta packets must use immutable structures (`data class` in Kotlin, `struct` in Swift) with persistent or copy-on-write patterns.

## 6. Defensive Engineering & Robustness
- **Defensive Boundary Validation**: Always validate buffer lengths, slice bounds, and payload headers before parsing to prevent buffer underflow exceptions (`BufferUnderflowException`) on corrupted, truncated, or malicious packets.
- **Idempotency & Monotonicity**: State reconciliations must be idempotent ($A \sqcup A = A$), commutative ($A \sqcup B = B \sqcup A$), and monotonic.
- **Anti-Resurrection & Tombstone Lifecycle**: Soft-deleted entities must preserve tombstones with monotonic timestamps to prevent concurrent sync operations from resurrecting deleted records.

## 7. Documentation & Mathematical Rigor
- **GitHub MathJax Syntax**: Always format mathematical equations using standalone ````math code blocks flush against the left margin. Never nest LaTeX inside bullet points, which breaks GitHub rendering.
- **Formal Specifications**: Formally specify total orders, partial orders, and invariants alongside code implementations.
- **Comprehensive API Documentation**: Every public class, actor, interface, and method must have thorough KDoc/SwiftDoc documenting thread safety, parameters, return values, and exceptions.

## 8. Verification Standards
- **Zero Warnings Policy**: Code must compile with 0 errors and 0 compiler warnings.
- **Test Before Push**: Never push code without running the full test suite (`./gradlew test` and `swift test`) and verifying all tests pass.
- **License**: All new source files must include the standard Apache-2.0 copyright header.
