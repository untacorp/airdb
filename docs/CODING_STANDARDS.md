# AirDB Coding & Engineering Standards

These standards ensure that AirDB maintains high architectural integrity, safety, security, and readability as an enterprise-grade open-source project.

---

## 1. Concurrency & Thread-Safety

1. **State Isolation**:
   - Internal JVM collections must be thread-safe (`ConcurrentHashMap`, `CopyOnWriteArrayList`, or atomic primitives).
   - Any mutable critical sections across multiple fields must be protected by explicit locks (`synchronized(lock) { ... }`).
   - On Apple platforms, mutable state must be encapsulated within Swift `actor` types or protected by lock closures (`NSLock.withScopedLock`).
2. **Non-Blocking Delegates**:
   - Never call `Thread.sleep()` or blocking I/O inside transport callbacks or event listeners.
   - Use Kotlin Coroutines (`CoroutineScope`, `launch`, `delay`) with appropriate dispatchers (`Dispatchers.Default` for CPU/state machine, `Dispatchers.IO` for disk adapters).
   - In Swift, delegate methods must dispatch work asynchronously to actor tasks without blocking CoreBluetooth delegate queues.

---

## 2. API Design & Seams

1. **Clean Seams**:
   - High-level business logic must never know about Android Bluetooth classes (`BluetoothGatt`, `BluetoothDevice`) or Apple CoreBluetooth classes (`CBPeripheral`, `CBCentralManager`).
   - All I/O must enter and leave through `AirDbTransport` and `AirDbAdapter` interfaces.
2. **KDoc & SwiftDoc Coverage**:
   - Every public interface, class, actor, and method MUST have comprehensive documentation describing:
     - Purpose and invariants
     - Thread-safety guarantees
     - Parameter descriptions
     - Return values and error conditions

---

## 3. Cryptographic Invariants & Zero-Trust Mesh

1. **Authenticated Wire Framing**:
   - Every encrypted packet must bind the complete wire header into the AEAD cipher as Authenticated Additional Data ($AAD$).
   - Never decrypt payloads without validating the Poly1305 MAC tag.
2. **Record Signing Hygiene**:
   - The canonical signing buffer must deterministically serialize collection, ID, mutation type, HLC timestamp, and payload bytes in Big-Endian order.
   - Inbound records must be verified against author public keys prior to calling `applyChanges`.
3. **Key Derivation Standards**:
   - Use RFC 5869 HKDF-SHA256 with distinct domain separation strings (`info` tags) for symmetric cluster keys.

---

## 4. Dependencies & Platform Purity

1. **Sans-I/O Core**:
   - `airdb-core`, `airdb-protocol`, `airdb-crypto`, and `airdb-adapter-api` must remain 100% pure Kotlin with zero dependencies on the Android SDK (`android.*`).
2. **Zero Third-Party SPM Dependencies**:
   - `AirDbKit` must strictly rely on native Apple standard frameworks (`CryptoKit`, `Compression`, `CoreBluetooth`, and `libsqlite3`). No external SPM packages.

---

## 5. Zero-Emoji Policy

1. Strictly zero emojis across all code, tests, comments, docstrings, commit messages, PR descriptions, and markdown documentation.
2. Automated scanners reject any pull requests containing emoji characters.

---

## 6. Testing Rules

1. **Deterministic Tests**:
   - No `Thread.sleep` in tests. Use coroutine test dispatchers (`runTest`, `advanceTimeBy`, `StandardTestDispatcher`) or `CountDownLatch` with explicit timeouts.
2. **Property & Fuzz Testing**:
   - Binary serialization and reassembly logic must be tested with edge cases: 0-byte arrays, maximum integer limits, and out-of-order chunk arrival.
3. **Cryptographic Coverage**:
   - All AEAD ciphers and digital signature implementations must be verified against known RFC test vectors or round-trip tampering assertions.
