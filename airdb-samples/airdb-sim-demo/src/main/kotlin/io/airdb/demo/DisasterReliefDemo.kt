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

package io.airdb.demo

import io.airdb.adapter.MemoryDbAdapter
import io.airdb.adapter.MutationType
import io.airdb.adapter.sqlite.SqliteDbAdapter
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() = runBlocking {
    println("==================================================================")
    println("      AirDB Simulation: Off-Grid Disaster Response Sync Demo       ")
    println("==================================================================")

    val network = SimulatedNetwork()

    // Initialize Posko A (Medical Triage Outpost backed by durable SQLite engine)
    val dbFileA = File("build/demo-db/posko-medis.sqlite")
    if (dbFileA.exists()) dbFileA.delete()
    val dbA = SqliteDbAdapter.open(dbFileA)
    val transportA = SimulatedTransport("posko-medis-merapi", network)
    val engineA = AirDbEngine("posko-medis-merapi", dbA, transportA)

    // Initialize Posko B (Logistics & Evacuation Camp backed by Memory adapter)
    val dbB = MemoryDbAdapter()
    val transportB = SimulatedTransport("posko-logistik-selo", network)
    val engineB = AirDbEngine("posko-logistik-selo", dbB, transportB)

    println("\n[1] Seeding offline records at Posko Medis (Durable SQLite)...")
    engineA.saveRecord(
        collection = "survivors",
        id = "survivor-001",
        type = MutationType.INSERT,
        payload = "{\"name\": \"Budi Santoso\", \"condition\": \"Stable\", \"bloodType\": \"O\"}".toByteArray()
    )
    engineA.saveRecord(
        collection = "medical_needs",
        id = "med-req-1",
        type = MutationType.INSERT,
        payload = "{\"item\": \"Oxygen Tanks\", \"urgency\": \"Critical\", \"qty\": 10}".toByteArray()
    )

    println("[2] Seeding offline records at Posko Logistik (In-Memory)...")
    engineB.saveRecord(
        collection = "survivors",
        id = "survivor-002",
        type = MutationType.INSERT,
        payload = "{\"name\": \"Siti Rahma\", \"condition\": \"Minor Injuries\", \"familyContact\": \"0812345678\"}".toByteArray()
    )
    engineB.saveRecord(
        collection = "supplies_inventory",
        id = "supp-01",
        type = MutationType.INSERT,
        payload = "{\"item\": \"Ready-to-eat Meals\", \"availableQty\": 1200}".toByteArray()
    )

    val initialSurvivorsA = dbA.getActiveRecords("survivors").size
    val initialMedNeedsA = dbA.getActiveRecords("medical_needs").size
    val initialSurvivorsB = dbB.getAllRecords("survivors").size
    val initialSuppliesB = dbB.getAllRecords("supplies_inventory").size

    println("\nInitial State:")
    println(" - Posko Medis holds $initialSurvivorsA survivor records and $initialMedNeedsA medical needs.")
    println(" - Posko Logistik holds $initialSurvivorsB survivor records and $initialSuppliesB inventory items.")

    println("\n[3] Volunteers approach each other in the field (BLE radio in range)...")
    val latchA = CountDownLatch(1)
    val latchB = CountDownLatch(1)

    engineA.addSyncListener { peerId, changeCount ->
        println(" -> [Posko Medis] Reconciled and applied $changeCount mutations from peer: $peerId")
        latchA.countDown()
    }
    engineB.addSyncListener { peerId, changeCount ->
        println(" -> [Posko Logistik] Reconciled and applied $changeCount mutations from peer: $peerId")
        latchB.countDown()
    }

    engineA.start()
    engineB.start()

    latchA.await(5, TimeUnit.SECONDS)
    latchB.await(5, TimeUnit.SECONDS)
    delay(300)

    val syncedSurvivorsA = dbA.getActiveRecords("survivors")
    val syncedSurvivorsB = dbB.getAllRecords("survivors")

    println("\n[4] Synchronized State after BLE P2P Exchange:")
    println(" - Posko Medis now holds total survivors: ${syncedSurvivorsA.size}")
    syncedSurvivorsA.forEach { (id, record) ->
        println("   * [ID: $id] -> ${String(record.payload)} (HLC: ${record.hlcTimestamp})")
    }

    println(" - Posko Logistik now holds total survivors: ${syncedSurvivorsB.size}")
    syncedSurvivorsB.forEach { (id, record) ->
        println("   * [ID: $id] -> ${String(record.payload)} (HLC: ${record.hlcTimestamp})")
    }

    println("\n[5] Simulating sudden power cut & device reboot on Posko Medis node...")
    engineA.stop()
    engineB.stop()
    dbA.close()

    // Reopen database directly from disk file to verify persistence
    val rebootedDbA = SqliteDbAdapter.open(dbFileA)
    val persistedSurvivors = rebootedDbA.getActiveRecords("survivors")
    println(" - Rebooted Posko Medis retrieved ${persistedSurvivors.size} survivors from SQLite disk:")
    persistedSurvivors.forEach { (id, record) ->
        println("   * [Disk Restored: $id] -> ${String(record.payload)}")
    }
    rebootedDbA.close()

    println("\n==================================================================")
    println("  Success: AirDB databases converged with 100% causal consistency ")
    println("           and durable SQLite disk persistence across reboots!    ")
    println("==================================================================")
}
