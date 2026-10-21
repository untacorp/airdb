# Cookbook: Native SQLite Adapter for Android & JVM

AirDB includes an optimized, production-ready SQLite persistence engine in the `:airdb-adapter-sqlite` module. It features automatic schema management, Write-Ahead Logging (WAL mode), monotonic HLC indexing, and tombstone lifecycle handling.

---

## 1. Dependency Setup

Add `:airdb-adapter-sqlite` to your application's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.airdb:airdb-core:0.2.0")
    implementation("io.airdb:airdb-adapter-sqlite:0.2.0")
    implementation("io.airdb:airdb-transport-ble:0.2.0")
}
```

---

## 2. Basic Initialization

```kotlin
import io.airdb.adapter.sqlite.SqliteAirDbAdapter
import java.io.File

// For Android applications:
val dbPath = context.getDatabasePath("airdb_local.db").absolutePath

// For JVM server / CLI applications:
// val dbPath = File("./data/airdb_local.db").absolutePath

val adapter = SqliteAirDbAdapter(dbPath = dbPath)
```

The adapter automatically executes:
- `PRAGMA journal_mode = WAL;` (enables concurrent readers during writes)
- `PRAGMA synchronous = NORMAL;` (optimal balance of performance and crash durability)
- Creates `_airdb_changes` table and `idx_airdb_changes_hlc` index if they do not exist.

---

## 3. Direct Domain Integration & Mutation Recording

To record changes in your local database and propagate them across the BLE mesh:

```kotlin
import io.airdb.adapter.MutationType

// 1. Create your AirDB engine with the SQLite adapter
val engine = AirDbEngine(
    nodeName = "posko-merapi-01",
    adapter = adapter,
    transport = transport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

// 2. Start synchronization
engine.start()

// 3. Save a record locally
val jsonPayload = """
{
    "itemId": "MED-501",
    "name": "Antibiotics Amoxicillin 500mg",
    "quantity": 250,
    "unit": "box"
}
""".trimIndent().toByteArray(Charsets.UTF_8)

val changeRecord = engine.saveRecord(
    collection = "medical_supplies",
    id = "MED-501",
    type = MutationType.INSERT,
    payload = jsonPayload
)

println("Saved record ${changeRecord.id} with HLC ${changeRecord.hlcTimestamp}")
```

---

## 4. Observing Changes with Sync Listeners

Listen for mutations arriving from nearby peers over Bluetooth:

```kotlin
engine.addSyncListener { peerId, changeCount ->
    println("Received and applied $changeCount mutations from peer: $peerId")
    
    // Query the latest state from your UI / state flow
    // ...
}
```
