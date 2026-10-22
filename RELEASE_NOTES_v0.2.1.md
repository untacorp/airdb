# AirDB v0.2.1: Hardened Persistence, Bitcask Log Integration, and Maintainer-Grade Core

AirDB v0.2.1 is a stabilization, performance, and architectural hardening release. It introduces the high-throughput AeroLog Bitcask append-only persistence adapter for Kotlin/Android, resolves 12 audited concurrency and security edge cases (PR #33), and ruthlessly refactors the codebase under Linux-kernel clean code discipline (PR #34).

---

## What is New in v0.2.1

### 1. High-Throughput AeroLog Persistence (`airdb-adapter-aerolog`)
- **Append-Only Bitcask Log**: First-class integration with the Rust-native [AeroLog](https://github.com/untacorp/aerolog) storage engine via Mozilla UniFFI, achieving over 200,000 sequential writes/sec with hardware-accelerated CRC32-IEEE checksums and zero random flash I/O.
- **Single-Seek Point Reads**: In-memory `KeyDir` hash indexing delivers sub-15-microsecond point reads directly to 64-bit file offsets.
- **Tombstone Compaction**: Garbage collection of soft-deleted records past HLC retention thresholds, purging dead keys from KeyDir and reclaiming physical segment space via Bitcask compaction.
- **O(1) HLC Tracking**: Restored `updateMaxSeenHlc` caching across changelog writes and mutation imports to eliminate linear stream scans.

### 2. Consolidated Security & Concurrency Audit (PR #33, Issues #9 to #20)
- **Reconciliation Data Starvation (#9)**: Continuous delta batch streaming prevents peer starvation under heavy write workloads.
- **Transport Wire Framing & Encryption (#10)**: Bound full 23B/31B packet headers as Authenticated Additional Data (AAD) in ChaCha20-Poly1305 AEAD across Android and Apple transports.
- **Changelog Scan Memory Bounding (#11, #15)**: Cursor-based chunked pagination in SQLite and a 10,000-entry safety ceiling in AeroLog prevent heap exhaustion.
- **HLC Canonical Ordering (#12)**: Unified three-tuple `(physicalTime, logicalCounter, originNode)` total ordering across Kotlin and Swift.
- **GATT Operation Queue Watchdog (#13, #14)**: Added retry budget limits and watchdog timers to prevent Android BLE queue hangs and deadlocks.
- **CoreBluetooth ATT Write Drain (#16)**: Added backpressure drain tracking `peripheralIsReady(toSendWriteWithoutResponse:)`.
- **Zip-Bomb Protection (#17)**: Enforced strict 64KB max decompressed payload budget in `BinaryCodec`.
- **SQLite Tombstone Indexing (#18)**: Added composite `(physical_time, logical_counter, origin_node)` indexes and atomic changelog compaction.
- **Swift Concurrency Hardening (#19)**: Replaced unstructured detached tasks with structured actor tasks and clean lifecycle cancellation.
- **KMP Concurrency Alignment (#20)**: Aligned coroutine dispatchers and lifecycle management with Kotlin Multiplatform standards.

### 3. Maintainer-Grade Clean Code Discipline (PR #34)
- **Linux Kernel Filter**: Eliminated narrative "obvious" comments, corporate filler naming (`dataBuffer`, `tempResult`, `pt`/`lc`/`nh` -> `physicalTime`/`logicalCounter`/`originNode`), and flattened nested conditionals via guard clauses and bouncer patterns (Linus' 3-indent law).
- **Net-Negative Lines**: Reduced overall code footprint while maintaining 100% green test passes across both Linux OpenJDK and macOS Swift CI.
- **Invariant 6 Enforcement**: Reaffirmed zero external SPM dependencies in `AirDbKit` on Apple platforms, providing clean BYOD documentation for custom adapters.

---

## Installation & Coordinates

### Android & JVM (Maven Central)
Add to your `build.gradle.kts`:
```kotlin
dependencies {
    // Core synchronization engine and Hybrid Logical Clock
    implementation("io.github.untacorp:airdb-core:0.2.1")

    // Cryptographic mesh primitives (ChaCha20-Poly1305, Ed25519, HKDF)
    implementation("io.github.untacorp:airdb-crypto:0.2.1")

    // Durable SQLite persistence with WAL mode
    implementation("io.github.untacorp:airdb-adapter-sqlite:0.2.1")

    // High-throughput AeroLog Bitcask storage adapter
    implementation("io.github.untacorp:airdb-adapter-aerolog:0.2.1")

    // Production Android BLE transport driver
    implementation("io.github.untacorp:airdb-transport-ble:0.2.1")
}
```

### Apple Platforms (Swift Package Manager)
Add to your `Package.swift`:
```swift
dependencies: [
    .package(url: "https://github.com/untacorp/airdb.git", from: "0.2.1")
]
```
Or in Xcode: **File > Add Package Dependencies...** and enter:
`https://github.com/untacorp/airdb.git`

---

## Quickstart

### Kotlin (Android / JVM with AeroLog)
```kotlin
import io.airdb.adapter.aerolog.AeroLogAirDbAdapter
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.ble.AndroidBleTransport
import java.io.File

val storageDir = File(context.filesDir, "aerolog_data")
val adapter = AeroLogAirDbAdapter.open(storageDir, nodeId = "node-alpha")

val engine = AirDbEngine(
    nodeName = "node-alpha",
    adapter = adapter,
    transport = AndroidBleTransport(context)
)
engine.start()
```

### Swift (iOS / macOS with SqliteDbAdapter)
```swift
import AirDbKit

let adapter = try SqliteDbAdapter.open(path: dbUrl.path)
let engine = AirDbEngine(
    nodeName = "apple-node-01",
    adapter = adapter,
    transport = CoreBluetoothTransport()
)
engine.start()
```

---

## Integrity & Verification

All release artifacts are cryptographically signed using the maintainer GPG key:
- **Key ID**: `CEB164B84F724DCC`
- **Fingerprint**: `11FF 32A2 9FE9 E6E3 14A4 8894 CEB1 64B8 4F72 4DCC`
- **Signer**: `Agus Arya <kadekaguspnata@gmail.com>`

To verify signatures locally:
```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys CEB164B84F724DCC
gpg --verify airdb-core-0.2.1.jar.asc airdb-core-0.2.1.jar
```

---

## Documentation & Prior Art
- **Official Documentation**: [https://untacorp.github.io/airdb/](https://untacorp.github.io/airdb/)
- **Publishing & Release Guide**: [https://untacorp.github.io/airdb/community/publishing/](https://untacorp.github.io/airdb/community/publishing/)
- **Full Changelog**: See [CHANGELOG.md](https://github.com/untacorp/airdb/blob/main/CHANGELOG.md)
