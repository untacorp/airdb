# Cookbook: SQLDelight Multiplatform Integration

This guide demonstrates how to integrate AirDB with **SQLDelight** (Kotlin Multiplatform / Android / JVM / iOS).

---

## 1. Schema Definition (`AirDbChangelog.sq`)

Create an SQLDelight definition file in `src/commonMain/sqldelight/io/airdb/storage/AirDbChangelog.sq`:

```sql
CREATE TABLE IF NOT EXISTS airdb_changelog (
    collection TEXT NOT NULL,
    id TEXT NOT NULL,
    mutation_type INTEGER NOT NULL,
    payload BLOB NOT NULL,
    hlc_timestamp TEXT NOT NULL,
    author_public_key BLOB,
    signature BLOB,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (collection, id)
);

CREATE INDEX IF NOT EXISTS idx_airdb_changelog_hlc
ON airdb_changelog(hlc_timestamp ASC);

-- Queries:
getChangesAfter:
SELECT * FROM airdb_changelog
WHERE hlc_timestamp > :sinceHlc
ORDER BY hlc_timestamp ASC
LIMIT :limit;

getEarliestChanges:
SELECT * FROM airdb_changelog
ORDER BY hlc_timestamp ASC
LIMIT :limit;

getLatestHlc:
SELECT MAX(hlc_timestamp) FROM airdb_changelog;

getRecordHlc:
SELECT hlc_timestamp FROM airdb_changelog
WHERE collection = :collection AND id = :id;

upsertChange:
INSERT OR REPLACE INTO airdb_changelog (
    collection, id, mutation_type, payload, hlc_timestamp, author_public_key, signature, created_at
) VALUES (
    :collection, :id, :mutationType, :payload, :hlcTimestamp, :authorPublicKey, :signature, :createdAt
);
```

---

## 2. Implementing `AirDbAdapter`

```kotlin
package io.airdb.storage.sqldelight

import io.airdb.adapter.AirDbAdapter
import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import io.airdb.storage.AirDbDatabase

class SqlDelightAirDbAdapter(
    private val database: AirDbDatabase
) : AirDbAdapter {

    private val queries = database.airDbChangelogQueries

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        val rows = if (sinceHlcTimestamp == null) {
            queries.getEarliestChanges(limit.toLong()).executeAsList()
        } else {
            queries.getChangesAfter(sinceHlcTimestamp, limit.toLong()).executeAsList()
        }

        return rows.map { row ->
            ChangeRecord(
                id = row.id,
                collection = row.collection,
                type = MutationType.entries[row.mutation_type.toInt()],
                payload = row.payload,
                hlcTimestamp = row.hlc_timestamp,
                authorPublicKey = row.author_public_key,
                signature = row.signature
            )
        }
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        database.transaction {
            val now = System.currentTimeMillis()
            for (change in changes) {
                val currentHlc = queries.getRecordHlc(change.collection, change.id).executeAsOneOrNull()

                // Last-Write-Wins (LWW) check
                if (currentHlc == null || change.hlcTimestamp > currentHlc) {
                    queries.upsertChange(
                        collection = change.collection,
                        id = change.id,
                        mutationType = change.type.ordinal.toLong(),
                        payload = change.payload,
                        hlcTimestamp = change.hlcTimestamp,
                        authorPublicKey = change.authorPublicKey,
                        signature = change.signature,
                        createdAt = now
                    )
                }
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return queries.getLatestHlc().executeAsOneOrNull()?.MAX
    }
}
```
