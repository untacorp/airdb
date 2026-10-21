# Coding Standards & Engineering Discipline

These engineering standards ensure that AirDB maintains high architectural integrity, stability, and readability across both Kotlin and Swift codebases.

---

## 1. Concurrency & Thread-Safety

1. **State Isolation**:
   - Internal JVM collections shared across threads must use thread-safe data structures (`ConcurrentHashMap`, `CopyOnWriteArrayList`, or atomic primitives).
   - Any mutable critical sections across multiple state fields must be guarded by locks.
   - On Apple platforms, mutable engine state must be encapsulated within a Swift `actor` or protected by scoped locks (`NSLock.withScopedLock`).
2. **Non-Blocking Radio Callbacks**:
   - Never call `Thread.sleep()` or execute blocking disk I/O inside Bluetooth callbacks or event listeners.
   - Use Kotlin Coroutines (`CoroutineScope`, `launch`, `withContext`) with appropriate dispatchers (`Dispatchers.Default` for CPU operations, `Dispatchers.IO` for disk adapters).
   - In Swift, delegate methods must dispatch work asynchronously to actor tasks without stalling CoreBluetooth queues.

---

## 2. API Design & Clean Seams

1. **Strict Sans-I/O Discipline**:
   - Core synchronization algorithms and wire codecs must never import Android Bluetooth classes (`BluetoothGatt`, `BluetoothDevice`) or Apple CoreBluetooth classes (`CBPeripheral`, `CBCentralManager`).
   - All I/O must enter and leave through the universal `AirDbTransport` and `AirDbAdapter` interfaces.
2. **Platform Purity**:
   - `airdb-core`, `airdb-protocol`, `airdb-crypto`, and `airdb-adapter-api` must remain 100% pure Kotlin with zero dependencies on the Android SDK (`android.*`).
   - `AirDbKit` must strictly rely on native Apple standard frameworks (`CryptoKit`, `Compression`, `CoreBluetooth`, and `libsqlite3`). Zero third-party SPM packages.

---

## 3. Cryptographic Invariants

1. **Authenticated Wire Framing**:
   - Every encrypted packet must bind the complete wire header into the AEAD cipher as Authenticated Additional Data ($AAD$).
   - Never decrypt payloads without verifying the Poly1305 MAC tag.
2. **Canonical Signing**:
   - Canonical signing buffers must deterministically serialize collection, ID, mutation type, HLC timestamp, and payload in Big-Endian order.
   - Inbound records must be verified against author public keys prior to calling `applyChanges`.

---

## 4. Zero-Emoji Policy

Strictly zero emojis across all code, tests, comments, docstrings, commit messages, PR descriptions, and documentation files.
