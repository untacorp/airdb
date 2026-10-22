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

package io.airdb.adapter

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryDbAdapterTest {

    @Test
    fun testApplyChangesAndLwwResolution() = runBlocking {
        val adapter = MemoryDbAdapter()

        val record1 = ChangeRecord(
            id = "item-1",
            collection = "inventory",
            type = MutationType.INSERT,
            payload = "{\"qty\": 10}".toByteArray(),
            hlcTimestamp = "1000-0-nodeA"
        )

        adapter.applyChanges(listOf(record1))
        assertEquals("1000-0-nodeA", adapter.getLatestHlc())
        assertEquals(record1, adapter.getRecord("inventory", "item-1"))

        val staleRecord = ChangeRecord(
            id = "item-1",
            collection = "inventory",
            type = MutationType.UPDATE,
            payload = "{\"qty\": 5}".toByteArray(),
            hlcTimestamp = "0900-0-nodeB"
        )
        adapter.applyChanges(listOf(staleRecord))
        assertEquals(record1, adapter.getRecord("inventory", "item-1"))

        val freshRecord = ChangeRecord(
            id = "item-1",
            collection = "inventory",
            type = MutationType.UPDATE,
            payload = "{\"qty\": 20}".toByteArray(),
            hlcTimestamp = "1100-0-nodeB"
        )
        val deleteRecord = ChangeRecord(
            id = "item-1",
            collection = "inventory",
            type = MutationType.DELETE,
            payload = byteArrayOf(),
            hlcTimestamp = "1200-0-nodeA"
        )
        adapter.applyChanges(listOf(deleteRecord))
        assertTrue(adapter.isRecordDeleted("inventory", "item-1"))
        assertEquals(0, adapter.getActiveRecords("inventory").size)

        val zombieRecord = ChangeRecord(
            id = "item-1",
            collection = "inventory",
            type = MutationType.INSERT,
            payload = "{\"qty\": 50}".toByteArray(),
            hlcTimestamp = "1150-0-nodeC"
        )
        adapter.applyChanges(listOf(zombieRecord))
        assertTrue(adapter.isRecordDeleted("inventory", "item-1"), "Stale write must not resurrect tombstoned record")
        assertEquals(0, adapter.getActiveRecords("inventory").size)
    }

    @Test
    fun testGetChangesSince() = runBlocking {
        val adapter = MemoryDbAdapter()

        val r1 = ChangeRecord("1", "orders", MutationType.INSERT, byteArrayOf(1), "100-0-A")
        val r2 = ChangeRecord("2", "orders", MutationType.INSERT, byteArrayOf(2), "200-0-A")
        val r3 = ChangeRecord("3", "orders", MutationType.INSERT, byteArrayOf(3), "300-0-A")

        adapter.applyChanges(listOf(r1, r2, r3))

        val all = adapter.getChangesSince(null, 10)
        assertEquals(3, all.size)

        val sinceR1 = adapter.getChangesSince("100-0-A", 10)
        assertEquals(2, sinceR1.size)
        assertEquals("2", sinceR1[0].id)
        assertEquals("3", sinceR1[1].id)
    }
}
