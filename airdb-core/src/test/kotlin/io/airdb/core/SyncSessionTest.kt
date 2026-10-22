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

package io.airdb.core

import io.airdb.adapter.MemoryDbAdapter
import io.airdb.adapter.MutationType
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncSessionTest {

    @Test
    fun testTwoNodesReconcileAndConverge() = runBlocking {
        val network = SimulatedNetwork()

        val adapterA = MemoryDbAdapter()
        val transportA = SimulatedTransport("nodeA", network)
        val engineA = AirDbEngine("nodeA", adapterA, transportA)

        val adapterB = MemoryDbAdapter()
        val transportB = SimulatedTransport("nodeB", network)
        val engineB = AirDbEngine("nodeB", adapterB, transportB)

        engineA.saveRecord(
            collection = "survivors",
            id = "survivor-101",
            type = MutationType.INSERT,
            payload = "{\"name\": \"John Doe\", \"status\": \"Safe\"}".toByteArray()
        )

        engineA.saveRecord(
            collection = "medical_supplies",
            id = "med-01",
            type = MutationType.INSERT,
            payload = "{\"item\": \"Bandages\", \"quantity\": 500}".toByteArray()
        )

        engineB.saveRecord(
            collection = "survivors",
            id = "survivor-102",
            type = MutationType.INSERT,
            payload = "{\"name\": \"Jane Smith\", \"status\": \"Injured\"}".toByteArray()
        )

        val latchA = CountDownLatch(1)
        val latchB = CountDownLatch(1)

        engineA.addSyncListener { peerId, count ->
            if (peerId == "nodeB") latchA.countDown()
        }
        engineB.addSyncListener { peerId, count ->
            if (peerId == "nodeA") latchB.countDown()
        }

        engineA.start()
        engineB.start()

        val syncSuccessA = latchA.await(5, TimeUnit.SECONDS)
        val syncSuccessB = latchB.await(5, TimeUnit.SECONDS)

        assertTrue(syncSuccessA, "Node A should have received changes from Node B")
        assertTrue(syncSuccessB, "Node B should have received changes from Node A")

        delay(200)

        val bRecordInA = adapterA.getRecord("survivors", "survivor-102")
        assertNotNull(bRecordInA)
        assertEquals("Jane Smith", String(bRecordInA!!.payload).substringAfter("\"name\": \"").substringBefore("\""))

        val aRecord1InB = adapterB.getRecord("survivors", "survivor-101")
        assertNotNull(aRecord1InB)
        val aRecord2InB = adapterB.getRecord("medical_supplies", "med-01")
        assertNotNull(aRecord2InB)

        assertEquals(2, adapterA.getAllRecords("survivors").size)
        assertEquals(2, adapterB.getAllRecords("survivors").size)

        engineA.stop()
        engineB.stop()
    }
}
