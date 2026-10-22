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

/**
 * End-to-end integration test for delay-tolerant epidemic sync (Data Mule scenario).
 *
 * Skenario:
 * 1. Node A (Camp Alpha) creates records while disconnected.
 * 2. Node B (Courier/Volunteer) visits Camp Alpha, syncs, then disconnects.
 * 3. Node B travels and visits Node C (Base Hospital), which was never in radio range of Node A.
 * 4. Verify Node C receives all records originated by Node A.
 */
class EpidemicMultiHopSyncTest {

    @Test
    fun testTransitiveSyncViaDataMule() = runBlocking {
        val campAlphaNet = SimulatedNetwork()

        val dbAlpha = MemoryDbAdapter()
        val transportAlpha = SimulatedTransport("camp-alpha", campAlphaNet)
        val engineAlpha = AirDbEngine("camp-alpha", dbAlpha, transportAlpha)

        for (i in 1..3) {
            engineAlpha.saveRecord(
                collection = "survivors",
                id = "alpha-victim-$i",
                type = MutationType.INSERT,
                payload = "{\"id\": $i, \"origin\": \"Alpha\"}".toByteArray()
            )
        }

        val dbMule = MemoryDbAdapter()
        val transportMuleAtAlpha = SimulatedTransport("volunteer-mule", campAlphaNet)
        val engineMuleAtAlpha = AirDbEngine("volunteer-mule", dbMule, transportMuleAtAlpha)

        val latchAlphaToMule = CountDownLatch(1)
        engineMuleAtAlpha.addSyncListener { peerId, count ->
            if (peerId == "camp-alpha") latchAlphaToMule.countDown()
        }

        engineAlpha.start()
        engineMuleAtAlpha.start()

        assertTrue(latchAlphaToMule.await(5, TimeUnit.SECONDS), "Volunteer Mule should sync from Camp Alpha")
        delay(150)

        assertEquals(3, dbMule.getActiveRecords("survivors").size)

        engineAlpha.stop()
        engineMuleAtAlpha.stop()

        val hospitalNet = SimulatedNetwork()

        val dbHospital = MemoryDbAdapter()
        val transportHospital = SimulatedTransport("base-hospital", hospitalNet)
        val engineHospital = AirDbEngine("base-hospital", dbHospital, transportHospital)

        val transportMuleAtHospital = SimulatedTransport("volunteer-mule", hospitalNet)
        val engineMuleAtHospital = AirDbEngine("volunteer-mule", dbMule, transportMuleAtHospital)

        val latchMuleToHospital = CountDownLatch(1)
        engineHospital.addSyncListener { peerId, count ->
            if (peerId == "volunteer-mule") latchMuleToHospital.countDown()
        }

        engineHospital.start()
        engineMuleAtHospital.start()

        assertTrue(latchMuleToHospital.await(5, TimeUnit.SECONDS), "Base Hospital should sync from Volunteer Mule")
        delay(150)

        val hospitalRecords = dbHospital.getActiveRecords("survivors")
        assertEquals(3, hospitalRecords.size, "Base Hospital should have 3 survivor records")
        assertTrue(hospitalRecords.containsKey("alpha-victim-1"))
        assertTrue(hospitalRecords.containsKey("alpha-victim-2"))
        assertTrue(hospitalRecords.containsKey("alpha-victim-3"))

        engineHospital.stop()
        engineMuleAtHospital.stop()
    }
}
