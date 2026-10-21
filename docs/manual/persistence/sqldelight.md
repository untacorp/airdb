# SQLDelight Multiplatform Integration

This guide demonstrates how to integrate AirDB with **SQLDelight** in Kotlin Multiplatform (KMP), Android, and JVM projects.

---

## 1. Schema Definition (`AirDbChangelog.sq`)

Create an SQLDelight definition file in your project at `src/commonMain/sqldelight/io/airdb/storage/AirDbChangelog.sq`:

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

upsertChange:
INSERT OR REPLACE INTO airdb_changelog (
    collection, id, mutation_type, payload, hlc_timestamp, author_public_key, signature, created_at
) VALUES (
    :collection, :id, :mutationType, :payload, :hlcTimestamp, :authorPublicKey, :signature, :createdAt
);
```

---

## 2. Implementing the `AirDbAdapter`

```kotlin
package io.airdb.storage.sqldelight

import io.airdb.adapter.AirDbAdapter
import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.HlcComparator
import io.airdb.adapter.MutationType

class SqlDelightAirDbAdapter(
    private val db: Database
) : AirDbAdapter {

    private val queries = db.airDbChangelogQueries

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
                type = MutationType.fromCode(row.mutation_type.toInt()),
                payload = row.payload,
                hlcTimestamp = row.hlc_timestamp,
                authorPublicKey = row.author_public_key,
                signature = row.signature
            )
        }
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        db.transaction {
            for (change in changes) {
                // Apply LWW and insert into changelog
                queries.upsertChange(
                    collection = change.collection,
                    id = change.id,
                    mutationType = change.type.code.toLong(),
                    payload = change.payload,
                    hlcTimestamp = change.hlcTimestamp,
                    authorPublicKey = change.authorPublicKey,
                    signature = change.signature,
                    createdAt = System.currentTimeMillis()
                )
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return queries.getLatestHlc().executeAsOneOrNull()?.MAX
    }
}
```
