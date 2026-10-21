# Getting Started with AirDB

This guide walks you through integrating AirDB into an Android application, Kotlin JVM service, or Apple application (iOS/macOS) to enable peer-to-peer database synchronization over Bluetooth Low Energy with zero-trust cryptographic guarantees.

---

## 1. Add Dependencies

### Android & Kotlin JVM (`build.gradle.kts`)

```kotlin
dependencies {
    implementation("io.airdb:airdb-core:0.2.0")
    implementation("io.airdb:airdb-protocol:0.2.0")
    implementation("io.airdb:airdb-crypto:0.2.0")
    implementation("io.airdb:airdb-adapter-sqlite:0.2.0")
    implementation("io.airdb:airdb-transport-ble:0.2.0")
}
```

### Apple Platforms (`Package.swift` or Xcode SPM)

```swift
dependencies: [
    .package(url: "https://github.com/untacorp/airdb.git", branch: "main")
]
```

---

## 2. Setting Up Durable SQLite Persistence

AirDB provides out-of-the-box persistent storage via SQLite WAL mode, eliminating the need to write boilerplate database adapters.

### Kotlin / Android:

```kotlin
import io.airdb.adapter.sqlite.SqliteAirDbAdapter

val dbFile = context.getDatabasePath("airdb_posko.db")
val adapter = SqliteAirDbAdapter(dbPath = dbFile.absolutePath)
```

### Apple Platforms / Swift:

```swift
import AirDbKit

let dbPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    .appendingPathComponent("airdb_posko.db").path
let adapter = try SqliteDbAdapter(databasePath: dbPath)
```

*(Note: If your application already uses Room, SQLDelight, GRDB, or CoreData, you can implement the clean [`AirDbAdapter`](file:///home/auttomus/Documents/Code/PROJECT/ble-research/airdb/airdb-adapter-api/src/main/kotlin/io/airdb/adapter/AirDbAdapter.kt) interface directly).*

---

## 3. Configuring Cryptographic Keys (Zero-Trust Mesh)

AirDB protects data in flight using ChaCha20-Poly1305 AEAD and verifies record authenticity using Ed25519 digital signatures.

### Kotlin / Android:

```kotlin
import io.airdb.crypto.Ed25519Signer
import io.airdb.crypto.HkdfSha256

// 1. Derive 256-bit symmetric AEAD cluster key from pre-shared secret
val clusterKey = HkdfSha256.deriveKey(
    ikm = "disaster-relief-cluster-secret".toByteArray(Charsets.UTF_8),
    salt = "airdb-mesh-v1".toByteArray(Charsets.UTF_8),
    info = "cluster-aead-key".toByteArray(Charsets.UTF_8)
)

// 2. Generate or load local device's Ed25519 signing key pair
val authorKeyPair = Ed25519Signer.generateKeyPair()
```

### Apple Platforms / Swift:

```swift
import AirDbKit

let clusterKey = try HkdfSha256.deriveKey(
    secret: "disaster-relief-cluster-secret".data(using: .utf8)!,
    salt: "airdb-mesh-v1".data(using: .utf8)!,
    info: "cluster-aead-key".data(using: .utf8)!
)

let authorKeyPair = try Ed25519Signer.generateKeyPair()
```

---

## 4. Initializing and Starting AirDbEngine

### Android / Kotlin:

```kotlin
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.ble.AndroidBleTransport

val transport = AndroidBleTransport(context)

val engine = AirDbEngine(
    nodeName = "posko-merapi-1",
    adapter = adapter,
    transport = transport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

// Register listener for sync progress
engine.addSyncListener { peerId, changeCount ->
    Log.i("AirDB", "Applied $changeCount verified mutations from peer: $peerId")
}

// Start discovery and synchronization
engine.start()
```

### Apple / Swift:

```swift
import AirDbKit

let transport = CoreBluetoothTransport()

let engine = AirDbEngine(
    nodeName: "posko-merapi-ios-01",
    adapter = adapter,
    transport = transport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

engine.addSyncListener { peerId, changeCount in
    print("Applied \(changeCount) verified mutations from peer: \(peerId)")
}

engine.start()
```

---

## 5. Saving and Propagating Mutations

When your application creates, updates, or deletes records, invoke `engine.saveRecord()`:

### Android / Kotlin:

```kotlin
import io.airdb.adapter.MutationType

val triageJson = """{"patientId": "P-101", "name": "Budi", "triage": "YELLOW"}"""

engine.saveRecord(
    collection = "triage_assessments",
    id = "P-101",
    type = MutationType.INSERT,
    payload = triageJson.toByteArray(Charsets.UTF_8)
)
```

### Apple / Swift:

```swift
let triageJson = """{"patientId": "P-101", "name": "Budi", "triage": "YELLOW"}""".data(using: .utf8)!

let record = try await engine.saveRecord(
    collection: "triage_assessments",
    id: "P-101",
    type: .insert,
    payload: triageJson
)
```

The mutation is automatically stamped with a monotonic HLC timestamp, signed with the local private key, written to persistent storage, and synchronized to all discovered peers within BLE proximity.
