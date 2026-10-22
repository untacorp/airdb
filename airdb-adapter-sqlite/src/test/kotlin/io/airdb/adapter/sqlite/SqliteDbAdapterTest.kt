/*
 * Copyright 2026 AirDB Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.airdb.adapter.sqlite

import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SqliteDbAdapterTest {

    @Test
    fun testFilePersistenceAcrossRestarts(@TempDir tempDir: Path) = runBlocking {
        val dbFile = File(tempDir.toFile(), "test_airdb.sqlite")

        // Phase 1: Open and write records
        SqliteDbAdapter.open(dbFile).use { adapter ->
            val record1 = ChangeRecord(
                id = "item-101",
                collection = "supplies",
                type = MutationType.INSERT,
                payload = "First Aid Kit".toByteArray(),
                hlcTimestamp = "1700000000000-0-nodeA"
            )
            adapter.applyChanges(listOf(record1))

            val retrieved = adapter.getActiveRecord("supplies", "item-101")
            assertNotNull(retrieved)
            assertEquals("First Aid Kit", String(retrieved!!.payload))
        }

        // Database is closed now. Phase 2: Re-open the database file
        SqliteDbAdapter.open(dbFile).use { adapter ->
            val retrieved = adapter.getActiveRecord("supplies", "item-101")
            assertNotNull(retrieved)
            assertEquals("First Aid Kit", String(retrieved!!.payload))
            assertEquals("1700000000000-0-nodeA", retrieved.hlcTimestamp)

            val changelog = adapter.getChangesSince(null)
            assertEquals(1, changelog.size)
            assertEquals("item-101", changelog[0].id)
        }
    }

    @Test
    fun testLwwReconciliationInSqlite() = runBlocking {
        SqliteDbAdapter.inMemory().use { adapter ->
            // Initial insert
            val rec1 = ChangeRecord(
                id = "doc-1",
                collection = "records",
                type = MutationType.INSERT,
                payload = "Version 1".toByteArray(),
                hlcTimestamp = "1000-2-nodeA"
            )
            adapter.applyChanges(listOf(rec1))
            assertEquals("Version 1", String(adapter.getActiveRecord("records", "doc-1")!!.payload))

            // Newer mutation (counter 10 > 2 numerically)
            val rec2 = ChangeRecord(
                id = "doc-1",
                collection = "records",
                type = MutationType.UPDATE,
                payload = "Version 2".toByteArray(),
                hlcTimestamp = "1000-10-nodeA"
            )
            adapter.applyChanges(listOf(rec2))
            assertEquals("Version 2", String(adapter.getActiveRecord("records", "doc-1")!!.payload))

            // Stale mutation (counter 1 < 10) must be rejected
            val recStale = ChangeRecord(
                id = "doc-1",
                collection = "records",
                type = MutationType.UPDATE,
                payload = "Stale Version".toByteArray(),
                hlcTimestamp = "1000-1-nodeA"
            )
            adapter.applyChanges(listOf(recStale))
            assertEquals("Version 2", String(adapter.getActiveRecord("records", "doc-1")!!.payload))
        }
    }

    @Test
    fun testTombstoneAntiResurrectionAndCompaction() = runBlocking {
        SqliteDbAdapter.inMemory().use { adapter ->
            // Insert
            val insertRec = ChangeRecord(
                id = "patient-007",
                collection = "patients",
                type = MutationType.INSERT,
                payload = "John Doe".toByteArray(),
                hlcTimestamp = "1000-0-nodeA"
            )
            adapter.applyChanges(listOf(insertRec))
            assertNotNull(adapter.getActiveRecord("patients", "patient-007"))
            assertFalse(adapter.isRecordDeleted("patients", "patient-007"))

            // Soft-delete (tombstone)
            val deleteRec = ChangeRecord(
                id = "patient-007",
                collection = "patients",
                type = MutationType.DELETE,
                payload = ByteArray(0),
                hlcTimestamp = "1000-5-nodeA"
            )
            adapter.applyChanges(listOf(deleteRec))
            assertNull(adapter.getActiveRecord("patients", "patient-007"))
            assertTrue(adapter.isRecordDeleted("patients", "patient-007"))

            // Delayed packet arrives attempting resurrection with older HLC
            val lateInsert = ChangeRecord(
                id = "patient-007",
                collection = "patients",
                type = MutationType.INSERT,
                payload = "Resurrected John".toByteArray(),
                hlcTimestamp = "1000-2-nodeA"
            )
            adapter.applyChanges(listOf(lateInsert))

            // Anti-resurrection guaranteed: remains soft-deleted
            assertNull(adapter.getActiveRecord("patients", "patient-007"))
            assertTrue(adapter.isRecordDeleted("patients", "patient-007"))

            // Compact tombstones older than threshold 1000-6-nodeA
            adapter.compact(pruneHlcThreshold = "1000-6-nodeA")

            // After compaction, tombstone is purged completely from disk
            assertFalse(adapter.isRecordDeleted("patients", "patient-007"))
            assertNull(adapter.readEntity("patients", "patient-007"))
        }
    }

    @Test
    fun testGetMissingChangesAgainstVectorClock() = runBlocking {
        SqliteDbAdapter.inMemory().use { adapter ->
            adapter.applyChanges(
                listOf(
                    ChangeRecord("e-1", "shelters", MutationType.INSERT, "S1".toByteArray(), "1000-0-poskoA"),
                    ChangeRecord("e-2", "shelters", MutationType.INSERT, "S2".toByteArray(), "1020-0-poskoA"),
                    ChangeRecord("e-3", "shelters", MutationType.INSERT, "S3".toByteArray(), "1000-0-poskoB")
                )
            )

            // Peer knows up to 1000-0-poskoA and nothing from poskoB
            val peerClock = mapOf("poskoA" to "1000-0-poskoA")
            val missing = adapter.getMissingChanges(peerClock)

            assertEquals(2, missing.size)
            assertTrue(missing.any { it.id == "e-2" })
            assertTrue(missing.any { it.id == "e-3" })
        }
    }

    @Test
    fun testLatestHlcDoesNotRegressOnOlderHistoricalSync() = runBlocking {
        SqliteDbAdapter.inMemory().use { adapter ->
            // Local record created at newer HLC (seq = 1)
            adapter.applyChanges(
                listOf(
                    ChangeRecord("local-1", "docs", MutationType.INSERT, "v1".toByteArray(), "1700000000000-0-nodeA")
                )
            )
            assertEquals("1700000000000-0-nodeA", adapter.getLatestHlc())

            // Historical record synced from peer with older HLC (seq = 2)
            adapter.applyChanges(
                listOf(
                    ChangeRecord("peer-historical", "docs", MutationType.INSERT, "v0".toByteArray(), "1600000000000-0-nodeB")
                )
            )

            // getLatestHlc MUST NOT regress to the older timestamp just because it was inserted later (seq = 2)
            assertEquals("1700000000000-0-nodeA", adapter.getLatestHlc())

            // And counter ordering must be respected
            adapter.applyChanges(
                listOf(
                    ChangeRecord("local-2", "docs", MutationType.INSERT, "v2".toByteArray(), "1700000000000-10-nodeA")
                )
            )
            assertEquals("1700000000000-10-nodeA", adapter.getLatestHlc())
        }
    }
}
