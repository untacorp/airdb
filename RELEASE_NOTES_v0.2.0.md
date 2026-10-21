# AirDB v0.2.0: Zero-Trust Cryptographic Mesh & Durable Persistence

AirDB v0.2.0 is a milestone release delivering production-grade security, durable write-ahead logging (WAL) persistence, and complete cross-platform parity across Android (JVM) and Apple platforms (iOS, macOS).

---

## What is New in v0.2.0

### 1. Zero-Trust Cryptographic Mesh (`airdb-crypto` & `AirDbKit/Crypto`)
- **RFC 8439 ChaCha20-Poly1305 AEAD**: Full symmetric packet payload encryption using Java 17 JCA on Android/JVM and native Apple `CryptoKit` on iOS/macOS. Wire packet headers (23B/31B) are bound as Authenticated Additional Data ($AAD$), preventing header spoofing and replay attacks.
- **RFC 8032 Ed25519 Digital Signatures**: Every mutating record is stamped with a 32-byte author public key and 64-byte Ed25519 digital signature. Non-repudiation and author provenance are preserved across untrusted mesh relay nodes.
- **RFC 5869 HKDF-SHA256**: Deterministic cluster and session key derivation.
- **Strict Signature Verification**: `SyncSession` drops any unverified, forged, or tampered record during peer reconciliation.

### 2. Durable SQLite Persistence (`airdb-adapter-sqlite` & `SqliteDbAdapter`)
- **JVM & Android**: Production SQLite adapter with Write-Ahead Logging (WAL mode), immediate write transactions, and indexed Hybrid Logical Clock (HLC) ranges.
- **Apple (iOS / macOS)**: Native Swift SQLite adapter (`SqliteDbAdapter`) implemented directly over Apple `libsqlite3` with zero third-party dependencies.
- **Anti-Resurrection Soft Deletes**: Tombstone lifecycle management preventing deleted records from resurrecting across partitioned network edges.

### 3. Apple Platform Parity (`AirDbKit`)
- Distributed as a native Swift package via Swift Package Manager (SPM).
- Dual-role BLE driver (`CoreBluetoothTransport`) acting simultaneously as Central and Peripheral with an internal backpressure drain queue.
- Modern Swift concurrency (`actor`, `async/await`, `Sendable`) ensuring 100% compile-time thread safety.

### 4. Hardened Android BLE Driver (`airdb-transport-ble`)
- Single-flight `GattOperationQueue` preventing Android OS GATT deadlocks and buffer overruns.
- Dynamic MTU negotiation up to 517 bytes with automatic MTU-aware packet fragmentation.
- Comprehensive runtime permission helper (`AndroidBlePermissions`) for Android 12+ (API 31+) and legacy versions.

---

## Installation & Coordinates

### Android & JVM (Maven Central)
Add to your `build.gradle.kts`:
```kotlin
dependencies {
    // Core synchronization engine and Hybrid Logical Clock
    implementation("io.github.untacorp:airdb-core:0.2.0")

    // Cryptographic mesh primitives (ChaCha20-Poly1305, Ed25519, HKDF)
    implementation("io.github.untacorp:airdb-crypto:0.2.0")

    // Durable SQLite persistence with WAL mode
    implementation("io.github.untacorp:airdb-adapter-sqlite:0.2.0")

    // Hardware BLE transport driver for Android
    implementation("io.github.untacorp:airdb-transport-ble:0.2.0")
}
```

### Apple Platforms (Swift Package Manager)
Add to your `Package.swift`:
```swift
dependencies: [
    .package(url: "https://github.com/untacorp/airdb.git", from: "0.2.0")
]
```
Or in Xcode: **File > Add Package Dependencies...** and enter:
`https://github.com/untacorp/airdb.git`

---

## Quickstart

### Kotlin (Android)
```kotlin
val sqliteAdapter = SqliteAirDbAdapter(dbPath = File(context.filesDir, "airdb.db").absolutePath)
val cryptoEngine = ChaCha20CryptoEngine(clusterKey = clusterKey32Bytes)

val engine = AirDbEngine(
    nodeId = "android-node-01",
    dbAdapter = sqliteAdapter,
    transport = AndroidBleTransport(context),
    cryptoEngine = cryptoEngine
)
engine.start()
```

### Swift (iOS / macOS)
```swift
import AirDbKit

let sqliteAdapter = try SqliteDbAdapter(databasePath: dbUrl.path)
let cryptoEngine = ChaCha20Poly1305Engine(clusterKey: clusterKeyData)

let engine = AirDbEngine(
    nodeId: "ios-node-01",
    dbAdapter: sqliteAdapter,
    transport: CoreBluetoothTransport(),
    cryptoEngine: cryptoEngine
)
try await engine.start()
```

---

## Integrity & Verification

All release artifacts are cryptographically signed using the project maintainer GPG key:
- **Key ID**: `CEB164B84F724DCC`
- **Fingerprint**: `11FF 32A2 9FE9 E6E3 14A4 8894 CEB1 64B8 4F72 4DCC`
- **Signer**: `Agus Arya <kadekaguspnata@gmail.com>`

To verify signatures locally:
```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys CEB164B84F724DCC
gpg --verify airdb-core-0.2.0.jar.asc airdb-core-0.2.0.jar
```

---

## Documentation & Prior Art
- **Official Documentation**: [https://untacorp.github.io/airdb/](https://untacorp.github.io/airdb/)
- **AI Agent Context**: [https://untacorp.github.io/airdb/llms.txt](https://untacorp.github.io/airdb/llms.txt)
- **Full Changelog**: See [CHANGELOG.md](https://github.com/untacorp/airdb/blob/main/CHANGELOG.md)
