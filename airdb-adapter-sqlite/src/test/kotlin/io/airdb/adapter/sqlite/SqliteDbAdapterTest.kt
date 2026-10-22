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
            val rec1 = ChangeRecord(
                id = "doc-1",
                collection = "records",
                type = MutationType.INSERT,
                payload = "Version 1".toByteArray(),
                hlcTimestamp = "1000-2-nodeA"
            )
            adapter.applyChanges(listOf(rec1))
            assertEquals("Version 1", String(adapter.getActiveRecord("records", "doc-1")!!.payload))

            val rec2 = ChangeRecord(
                id = "doc-1",
                collection = "records",
                type = MutationType.UPDATE,
                payload = "Version 2".toByteArray(),
                hlcTimestamp = "1000-10-nodeA"
            )
            adapter.applyChanges(listOf(rec2))
            assertEquals("Version 2", String(adapter.getActiveRecord("records", "doc-1")!!.payload))

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

            val lateInsert = ChangeRecord(
                id = "patient-007",
                collection = "patients",
                type = MutationType.INSERT,
                payload = "Resurrected John".toByteArray(),
                hlcTimestamp = "1000-2-nodeA"
            )
            adapter.applyChanges(listOf(lateInsert))

            assertNull(adapter.getActiveRecord("patients", "patient-007"))
            assertTrue(adapter.isRecordDeleted("patients", "patient-007"))

            adapter.compact(pruneHlcThreshold = "1000-6-nodeA")

            assertFalse(adapter.isRecordDeleted("patients", "patient-007"))
            assertNull(adapter.readEntity("patients", "patient-007"))
            val changelog = adapter.readChangelogSince(null, 100)
            assertFalse(changelog.any { it.id == "patient-007" && it.type == MutationType.DELETE })
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

            val peerClock = mapOf("poskoA" to "1000-0-poskoA")
            val missing = adapter.getMissingChanges(peerClock)

            assertEquals(2, missing.size)
            assertTrue(missing.any { it.id == "e-2" })
            assertTrue(missing.any { it.id == "e-3" })
        }
    }

    @Test
    fun testReadChangelogSinceNumericOrderingAndLimit() = runBlocking {
        SqliteDbAdapter.inMemory().use { adapter ->
            adapter.applyChanges(
                listOf(
                    ChangeRecord("rec-2", "tests", MutationType.INSERT, byteArrayOf(1), "1000-2-nodeA"),
                    ChangeRecord("rec-10", "tests", MutationType.INSERT, byteArrayOf(2), "1000-10-nodeA"),
                    ChangeRecord("rec-20", "tests", MutationType.INSERT, byteArrayOf(3), "1000-20-nodeA")
                )
            )

            val changesSince2 = adapter.readChangelogSince("1000-2-nodeA", limit = 10)
            assertEquals(2, changesSince2.size)
            assertEquals("rec-10", changesSince2[0].id)
            assertEquals("rec-20", changesSince2[1].id)

            val limited = adapter.readChangelogSince("1000-2-nodeA", limit = 1)
            assertEquals(1, limited.size)
            assertEquals("rec-10", limited[0].id)
        }
    }

    @Test
    fun testLatestHlcDoesNotRegressOnOlderHistoricalSync() = runBlocking {
        SqliteDbAdapter.inMemory().use { adapter ->
            adapter.applyChanges(
                listOf(
                    ChangeRecord("local-1", "docs", MutationType.INSERT, "v1".toByteArray(), "1700000000000-0-nodeA")
                )
            )
            assertEquals("1700000000000-0-nodeA", adapter.getLatestHlc())

            adapter.applyChanges(
                listOf(
                    ChangeRecord("peer-historical", "docs", MutationType.INSERT, "v0".toByteArray(), "1600000000000-0-nodeB")
                )
            )

            assertEquals("1700000000000-0-nodeA", adapter.getLatestHlc())

            adapter.applyChanges(
                listOf(
                    ChangeRecord("local-2", "docs", MutationType.INSERT, "v2".toByteArray(), "1700000000000-10-nodeA")
                )
            )
            assertEquals("1700000000000-10-nodeA", adapter.getLatestHlc())
        }
    }
}
