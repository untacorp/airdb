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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class AbstractAirDbAdapterTest {

    private class MockCustomStoreAdapter : AbstractAirDbAdapter() {
        val entityStore = ConcurrentHashMap<String, StoredEntity>()
        val changelogStore = CopyOnWriteArrayList<ChangeRecord>()

        private fun key(collection: String, id: String) = "$collection:$id"

        override suspend fun readEntity(collection: String, id: String): StoredEntity? {
            return entityStore[key(collection, id)]
        }

        override suspend fun writeEntity(entity: StoredEntity) {
            entityStore[key(entity.collection, entity.id)] = entity
        }

        override suspend fun readChangelogSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
            if (sinceHlcTimestamp == null) {
                return changelogStore.take(limit)
            }
            return changelogStore
                .filter { HlcComparator.compare(it.hlcTimestamp, sinceHlcTimestamp) > 0 }
                .take(limit)
        }

        override suspend fun appendChangelog(record: ChangeRecord) {
            changelogStore.add(record)
        }
    }

    @Test
    fun testLwwReconciliation() = runBlocking {
        val adapter = MockCustomStoreAdapter()

        // Mutation 1: initial insert
        val rec1 = ChangeRecord("doc-1", "users", MutationType.INSERT, "{\"v\":1}".toByteArray(), "1000-2-nodeA")
        adapter.applyChanges(listOf(rec1))

        assertEquals("{\"v\":1}", String(adapter.readEntity("users", "doc-1")!!.payload))

        // Mutation 2: newer counter (10 > 2 numerically)
        val rec2 = ChangeRecord("doc-1", "users", MutationType.UPDATE, "{\"v\":2}".toByteArray(), "1000-10-nodeA")
        adapter.applyChanges(listOf(rec2))

        assertEquals("{\"v\":2}", String(adapter.readEntity("users", "doc-1")!!.payload))

        // Mutation 3: stale update (1 < 10)
        val recStale = ChangeRecord("doc-1", "users", MutationType.UPDATE, "{\"v\":stale}".toByteArray(), "1000-1-nodeA")
        adapter.applyChanges(listOf(recStale))

        assertEquals("{\"v\":2}", String(adapter.readEntity("users", "doc-1")!!.payload))
    }

    @Test
    fun testTombstoneAntiResurrection() = runBlocking {
        val adapter = MockCustomStoreAdapter()

        // Insert
        val insertRec = ChangeRecord("item-1", "inventory", MutationType.INSERT, "tent".toByteArray(), "1000-0-nodeA")
        adapter.applyChanges(listOf(insertRec))
        assertEquals(MutationType.INSERT, adapter.readEntity("inventory", "item-1")!!.type)

        // Delete (tombstone)
        val deleteRec = ChangeRecord("item-1", "inventory", MutationType.DELETE, ByteArray(0), "1000-5-nodeA")
        adapter.applyChanges(listOf(deleteRec))
        assertEquals(MutationType.DELETE, adapter.readEntity("inventory", "item-1")!!.type)

        // Late arriving insert with older HLC
        val lateInsert = ChangeRecord("item-1", "inventory", MutationType.INSERT, "tent-resurrected".toByteArray(), "1000-2-nodeA")
        adapter.applyChanges(listOf(lateInsert))

        // Must remain deleted (anti-resurrection guaranteed)
        assertEquals(MutationType.DELETE, adapter.readEntity("inventory", "item-1")!!.type)
    }

    @Test
    fun testGetMissingChangesAgainstPeerVectorClock() = runBlocking {
        val adapter = MockCustomStoreAdapter()

        adapter.applyChanges(
            listOf(
                ChangeRecord("doc-1", "items", MutationType.INSERT, "A1".toByteArray(), "1000-0-nodeA"),
                ChangeRecord("doc-2", "items", MutationType.INSERT, "A2".toByteArray(), "1005-0-nodeA"),
                ChangeRecord("doc-3", "items", MutationType.INSERT, "B1".toByteArray(), "1000-0-nodeB")
            )
        )

        // Peer knows up to 1000-0-nodeA and nothing from nodeB
        val peerClock = mapOf("nodeA" to "1000-0-nodeA")
        val missing = adapter.getMissingChanges(peerClock)

        assertEquals(2, missing.size)
        assertTrue(missing.any { it.id == "doc-2" }) // 1005 > 1000
        assertTrue(missing.any { it.id == "doc-3" }) // nodeB unknown to peer
    }
}
