# Cookbook: Android Jetpack Room Integration

This guide demonstrates how to integrate AirDB into an Android application that uses **Android Jetpack Room** for local persistence.

---

## Architecture Overview

```
+-------------------------------------------------------------+
| Android Application UI (Jetpack Compose / ViewModel)        |
+------------------------------+------------------------------+
                               | Queries / Updates
+------------------------------v------------------------------+
| AppDatabase (Room)                                          |
| - Domain Tables: (e.g., survivor_entity, pos_orders)        |
| - Changelog Table: airdb_changelog (append-only sync feed)  |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
| RoomAirDbAdapter (Conforms to AirDbAdapter)                 |
| - getChangesSince(hlc, limit) -> Reads airdb_changelog      |
| - applyChanges(changes) -> Applies LWW merge to Room tables |
| - getLatestHlc() -> SELECT MAX(hlc_timestamp)               |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
| AirDbEngine (Causal Ordering, BLE Transport, Cryptography)  |
+-------------------------------------------------------------+
```

---

## 1. Schema Definition (Room Entities)

Add a changelog entity to your Room schema alongside your domain tables:

```kotlin
package com.example.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "airdb_changelog",
    indices = [
        Index(value = ["hlcTimestamp"], unique = false),
        Index(value = ["collection", "recordId"], unique = false)
    ]
)
data class ChangelogEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val collection: String,
    val recordId: String,
    val mutationType: String, // INSERT, UPDATE, DELETE
    val payload: ByteArray,
    val hlcTimestamp: String,
    val authorPublicKey: ByteArray? = null,
    val signature: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis()
)
```

And your domain entity (for example, emergency disaster survivors):

```kotlin
@Entity(tableName = "survivors")
data class SurvivorEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val triageStatus: String, // RED, YELLOW, GREEN, BLACK
    val location: String,
    val updatedAtHlc: String
)
```

---

## 2. Room DAO Definition

```kotlin
package com.example.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AirDbDao {

    @Query("SELECT * FROM airdb_changelog WHERE hlcTimestamp > :sinceHlc ORDER BY hlcTimestamp ASC LIMIT :limit")
    suspend fun getChangesAfter(sinceHlc: String, limit: Int): List<ChangelogEntity>

    @Query("SELECT * FROM airdb_changelog ORDER BY hlcTimestamp ASC LIMIT :limit")
    suspend fun getEarliestChanges(limit: Int): List<ChangelogEntity>

    @Query("SELECT MAX(hlcTimestamp) FROM airdb_changelog")
    suspend fun getMaxHlc(): String?

    @Query("SELECT hlcTimestamp FROM airdb_changelog WHERE collection = :collection AND recordId = :recordId ORDER BY hlcTimestamp DESC LIMIT 1")
    suspend fun getLatestHlcForRecord(collection: String, recordId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChangelog(entry: ChangelogEntity)

    // Domain queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSurvivor(survivor: SurvivorEntity)

    @Query("DELETE FROM survivors WHERE id = :id")
    suspend fun deleteSurvivor(id: String)
}
```

---

## 3. Implementing `AirDbAdapter`

```kotlin
package com.example.app.data.local

import androidx.room.withTransaction
import io.airdb.adapter.AirDbAdapter
import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import org.json.JSONObject

class RoomAirDbAdapter(
    private val database: AppDatabase
) : AirDbAdapter {

    private val dao = database.airDbDao()

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        val rows = if (sinceHlcTimestamp == null) {
            dao.getEarliestChanges(limit)
        } else {
            dao.getChangesAfter(sinceHlcTimestamp, limit)
        }

        return rows.map { row ->
            ChangeRecord(
                id = row.recordId,
                collection = row.collection,
                type = MutationType.valueOf(row.mutationType),
                payload = row.payload,
                hlcTimestamp = row.hlcTimestamp,
                authorPublicKey = row.authorPublicKey,
                signature = row.signature
            )
        }
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        database.withTransaction {
            for (change in changes) {
                val currentHlc = dao.getLatestHlcForRecord(change.collection, change.id)

                // Last-Write-Wins (LWW) check: only apply if change is strictly newer
                if (currentHlc == null || change.hlcTimestamp > currentHlc) {
                    // 1. Record to sync changelog
                    dao.insertChangelog(
                        ChangelogEntity(
                            collection = change.collection,
                            recordId = change.id,
                            mutationType = change.type.name,
                            payload = change.payload,
                            hlcTimestamp = change.hlcTimestamp,
                            authorPublicKey = change.authorPublicKey,
                            signature = change.signature
                        )
                    )

                    // 2. Project into domain tables
                    if (change.collection == "survivors") {
                        when (change.type) {
                            MutationType.INSERT, MutationType.UPDATE -> {
                                val json = JSONObject(String(change.payload, Charsets.UTF_8))
                                val entity = SurvivorEntity(
                                    id = change.id,
                                    name = json.getString("name"),
                                    triageStatus = json.getString("triageStatus"),
                                    location = json.getString("location"),
                                    updatedAtHlc = change.hlcTimestamp
                                )
                                dao.upsertSurvivor(entity)
                            }
                            MutationType.DELETE -> {
                                dao.deleteSurvivor(change.id)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return dao.getMaxHlc()
    }
}
```

---

## 4. Initialization with AirDB Engine

```kotlin
// Inside your Application or Dagger/Hilt Module:
val roomAdapter = RoomAirDbAdapter(appDatabase)
val bleTransport = AndroidBleTransport(applicationContext)

val airDbEngine = AirDbEngine(
    nodeName = "responder-unit-alpha",
    adapter = roomAdapter,
    transport = bleTransport,
    clusterKey = clusterKey,
    authorKeyPair = authorKeyPair,
    enforceSignatures = true
)

// Start background BLE mesh discovery and replication
airDbEngine.start()
```
