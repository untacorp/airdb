# Getting Started with AirDB

This guide walks you through integrating AirDB into an Android application (or Kotlin JVM service) to enable peer-to-peer database synchronization over Bluetooth Low Energy.

---

## 1. Add Dependencies

Add the AirDB modules to your application's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.airdb:airdb-core:0.1.0")
    implementation("io.airdb:airdb-adapter-api:0.1.0")
    implementation("io.airdb:airdb-transport-ble:0.1.0")
}
```

---

## 2. Implementing the Database Adapter

AirDB does not dictate your local database technology. You connect your local database (e.g. Room, raw SQLite, or Realm) by implementing [`AirDbAdapter`](file:///home/auttomus/Documents/Code/PROJECT/ble-research/airdb/airdb-adapter-api/src/main/kotlin/io/airdb/adapter/AirDbAdapter.kt).

### Example with Room / SQLite:

Create a simple changelog table to track mutations:

```sql
CREATE TABLE airdb_changelog (
    id TEXT NOT NULL,
    collection TEXT NOT NULL,
    mutation_type TEXT NOT NULL,
    payload BLOB,
    hlc_timestamp TEXT NOT NULL,
    PRIMARY KEY (id, hlc_timestamp)
);
CREATE INDEX idx_changelog_hlc ON airdb_changelog(hlc_timestamp);
```

Then implement `AirDbAdapter`:

```kotlin
class RoomAirDbAdapter(private val database: AppDatabase) : AirDbAdapter {

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        val entities = if (sinceHlcTimestamp == null) {
            database.changelogDao().getEarliestChanges(limit)
        } else {
            database.changelogDao().getChangesAfter(sinceHlcTimestamp, limit)
        }
        return entities.map { it.toChangeRecord() }
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        database.withTransaction {
            for (change in changes) {
                val current = database.changelogDao().getLatestForId(change.collection, change.id)

                // Last-Write-Wins based on HLC string comparison
                if (current == null || change.hlcTimestamp > current.hlcTimestamp) {
                    database.changelogDao().upsert(change.toEntity())

                    when (change.type) {
                        MutationType.INSERT, MutationType.UPDATE -> {
                            // Update domain entity table (e.g. survivors, inventory)
                            database.survivorDao().upsert(Survivor.fromBytes(change.payload))
                        }
                        MutationType.DELETE -> {
                            // Mark domain entity as deleted
                            database.survivorDao().deleteById(change.id)
                        }
                    }
                }
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return database.changelogDao().getMaxHlc()
    }
}
```

---

## 3. Configuring Bluetooth Permissions (Android)

Declare the required Bluetooth permissions in your `AndroidManifest.xml`:

```xml
<!-- Required for Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Legacy permissions for Android 11 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## 4. Initializing AirDbEngine

```kotlin
// Create a stable or descriptive node identifier
val nodeId = "posko-merapi-" + UUID.randomUUID().toString().take(6)

val adapter = RoomAirDbAdapter(myAppDatabase)
val transport = AndroidBleTransport(context)

val engine = AirDbEngine(
    nodeName = nodeId,
    adapter = adapter,
    transport = transport
)

// Listen for sync completion events
engine.addSyncListener { peerId, changeCount ->
    Log.i("AirDB", "Applied $changeCount mutations from peer: $peerId")
}

// Start discovery & background sync
engine.start()
```

---

## 5. Saving Mutations

When your app creates, updates, or deletes records, invoke `engine.saveRecord()`:

```kotlin
val survivorJson = """{"name": "Budi", "status": "Rescued"}"""

engine.saveRecord(
    collection = "survivors",
    id = "survivor-101",
    type = MutationType.INSERT,
    payload = survivorJson.toByteArray(Charsets.UTF_8)
)
```

The mutation will be stamped with a monotonically increasing HLC timestamp and automatically propagated to any peer in BLE range!
