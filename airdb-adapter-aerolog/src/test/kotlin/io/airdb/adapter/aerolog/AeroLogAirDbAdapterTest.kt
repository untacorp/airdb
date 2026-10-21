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

package io.airdb.adapter.aerolog

import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AeroLogAirDbAdapterTest {

    @Test
    fun testFilePersistenceAcrossRestarts(@TempDir tempDir: Path) = runBlocking {
        val dbDir = File(tempDir.toFile(), "aerolog_data")

        // Phase 1: Open and write records
        AeroLogAirDbAdapter.open(dbDir, nodeId = "node-alpha").use { adapter ->
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

        // Database is closed now. Phase 2: Re-open the database directory
        AeroLogAirDbAdapter.open(dbDir, nodeId = "node-alpha").use { adapter ->
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
    fun testLwwReconciliation(@TempDir tempDir: Path) = runBlocking {
        val dbDir = File(tempDir.toFile(), "aerolog_lww")
        AeroLogAirDbAdapter.open(dbDir).use { adapter ->
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
    fun testTombstoneAndCompaction(@TempDir tempDir: Path) = runBlocking {
        val dbDir = File(tempDir.toFile(), "aerolog_tombstones")
        AeroLogAirDbAdapter.open(dbDir).use { adapter ->
            // Insert
            val rec = ChangeRecord(
                id = "item-del",
                collection = "inventory",
                type = MutationType.INSERT,
                payload = "Water Bottle".toByteArray(),
                hlcTimestamp = "2000-0-nodeA"
            )
            adapter.applyChanges(listOf(rec))
            assertNotNull(adapter.getActiveRecord("inventory", "item-del"))
            assertFalse(adapter.isRecordDeleted("inventory", "item-del"))

            // Soft-delete (tombstone)
            val recDel = ChangeRecord(
                id = "item-del",
                collection = "inventory",
                type = MutationType.DELETE,
                payload = ByteArray(0),
                hlcTimestamp = "2000-1-nodeA"
            )
            adapter.applyChanges(listOf(recDel))

            assertNull(adapter.getActiveRecord("inventory", "item-del"))
            assertTrue(adapter.isRecordDeleted("inventory", "item-del"))

            // Trigger compaction
            adapter.compact("2000-1-nodeA")
            assertNull(adapter.getActiveRecord("inventory", "item-del"))
        }
    }

    @Test
    fun testChangelogDeltaSync(@TempDir tempDir: Path) = runBlocking {
        val dbDir = File(tempDir.toFile(), "aerolog_delta")
        AeroLogAirDbAdapter.open(dbDir).use { adapter ->
            val rec1 = ChangeRecord(
                id = "e1",
                collection = "events",
                type = MutationType.INSERT,
                payload = "Event 1".toByteArray(),
                hlcTimestamp = "1000-0-nodeA"
            )
            val rec2 = ChangeRecord(
                id = "e2",
                collection = "events",
                type = MutationType.INSERT,
                payload = "Event 2".toByteArray(),
                hlcTimestamp = "2000-0-nodeA"
            )
            val rec3 = ChangeRecord(
                id = "e3",
                collection = "events",
                type = MutationType.INSERT,
                payload = "Event 3".toByteArray(),
                hlcTimestamp = "3000-0-nodeA"
            )
            adapter.applyChanges(listOf(rec1, rec2, rec3))

            // Query changes strictly newer than e1
            val delta = adapter.getChangesSince("1000-0-nodeA")
            assertEquals(2, delta.size)
            assertEquals("e2", delta[0].id)
            assertEquals("e3", delta[1].id)
        }
    }

    @Test
    fun testP2PSyncAcrossTwoAeroLogNodes(@TempDir tempDir: Path) = runBlocking {
        val network = SimulatedNetwork()

        val dirNode1 = File(tempDir.toFile(), "node1_db")
        val dirNode2 = File(tempDir.toFile(), "node2_db")

        val adapter1 = AeroLogAirDbAdapter.open(dirNode1, "node-1")
        val adapter2 = AeroLogAirDbAdapter.open(dirNode2, "node-2")

        val transport1 = SimulatedTransport("node-1", network)
        val transport2 = SimulatedTransport("node-2", network)

        val engine1 = AirDbEngine("node-1", adapter1, transport1)
        val engine2 = AirDbEngine("node-2", adapter2, transport2)

        // Node 1 creates a record offline
        val payload = "Autonomous Mesh Sync via AeroLog".toByteArray()
        engine1.saveRecord("messages", "msg-42", MutationType.INSERT, payload)

        // Verify Node 2 doesn't have it yet
        assertNull(adapter2.getActiveRecord("messages", "msg-42"))

        val latch = CountDownLatch(1)
        engine2.addSyncListener { peerId, count ->
            if (peerId == "node-1" && count > 0) {
                latch.countDown()
            }
        }

        // Start engines - SimulatedNetwork connects peers in range
        engine1.start()
        engine2.start()

        // Wait for P2P BLE replication
        assertTrue(latch.await(5, TimeUnit.SECONDS), "P2P sync between AeroLog nodes timed out")

        // Verify Node 2 now has the record replicated from Node 1
        val syncedRecord = adapter2.getActiveRecord("messages", "msg-42")
        assertNotNull(syncedRecord)
        assertEquals("Autonomous Mesh Sync via AeroLog", String(syncedRecord!!.payload))

        // Clean shutdown
        engine1.stop()
        engine2.stop()
        adapter1.close()
        adapter2.close()

        // Reopen Node 2 from disk to verify persistence of the replicated record
        AeroLogAirDbAdapter.open(dirNode2, "node-2").use { reloadedAdapter2 ->
            val persisted = reloadedAdapter2.getActiveRecord("messages", "msg-42")
            assertNotNull(persisted)
            assertEquals("Autonomous Mesh Sync via AeroLog", String(persisted!!.payload))
        }
    }
}
