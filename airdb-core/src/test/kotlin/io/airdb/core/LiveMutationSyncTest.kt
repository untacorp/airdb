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

class LiveMutationSyncTest {

    @Test
    fun testLiveMutationStreamsWhileConnected() = runBlocking {
        val network = SimulatedNetwork()

        val adapterA = MemoryDbAdapter()
        val transportA = SimulatedTransport("node-a", network)
        val engineA = AirDbEngine("node-a", adapterA, transportA)

        val adapterB = MemoryDbAdapter()
        val transportB = SimulatedTransport("node-b", network)
        val engineB = AirDbEngine("node-b", adapterB, transportB)

        // Start both engines empty
        engineA.start()
        engineB.start()

        // Wait for link-up handshake to finish
        delay(300)

        val latchLiveRecord = CountDownLatch(1)
        engineB.addSyncListener { peerId, count ->
            if (peerId == "node-a" && count >= 1) {
                latchLiveRecord.countDown()
            }
        }

        // Commit a mutation on Node A while actively connected
        val record = engineA.saveRecord(
            collection = "triage",
            id = "triage-live-01",
            type = MutationType.INSERT,
            payload = "{\"patient\": \"Zulfikar\", \"condition\": \"RED\"}".toByteArray()
        )

        // Verify Node B receives and applies it live without reconnecting
        assertTrue(
            latchLiveRecord.await(3, TimeUnit.SECONDS),
            "Node B must receive live mutation committed on Node A over existing active link"
        )

        val recordOnB = adapterB.getRecord("triage", "triage-live-01")
        assertNotNull(recordOnB, "Record must be present in Node B adapter")
        assertEquals(record.hlcTimestamp, recordOnB!!.hlcTimestamp)
        assertEquals("triage", recordOnB.collection)

        engineA.stop()
        engineB.stop()
    }

    @Test
    fun testEngineRestartLifecycle() = runBlocking {
        val network = SimulatedNetwork()
        val adapter = MemoryDbAdapter()
        val transport = SimulatedTransport("node-restart", network)
        val engine = AirDbEngine("node-restart", adapter, transport)

        engine.start()
        engine.stop()

        // Restarting engine should succeed and allow saving records and processing coroutines
        engine.start()
        val record = engine.saveRecord(
            collection = "triage",
            id = "triage-restart-01",
            type = MutationType.INSERT,
            payload = "restarted".toByteArray()
        )
        assertNotNull(record)
        assertEquals("triage-restart-01", record.id)

        engine.stop()
    }
}
