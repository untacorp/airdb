# Tutorial: Building an Off-Grid Disaster Triage App

In this step-by-step tutorial, you will build a realistic disaster response coordination system. We will create two independent nodes representing two remote emergency response posts:

1. **Posko Medis (Medical Triage Post)**: Treats injured survivors and logs medical supply requests.
2. **Posko Logistik (Logistics Camp)**: Manages food, blankets, and shelter supplies.

Both posts start completely separated with no internet or radio connectivity. Each post logs records locally. Then, volunteers move between camps, bringing their devices into Bluetooth range, and AirDB automatically reconciles both databases into complete synchronization.

---

## 1. Setting Up the Simulation Environment

To demonstrate this in an easy, repeatable way on your laptop without needing physical Android or iPhone hardware, we use AirDB's `SimulatedNetwork` and `SimulatedTransport`.

```kotlin
package io.airdb.tutorial

import io.airdb.adapter.MutationType
import io.airdb.adapter.sqlite.SqliteDbAdapter
import io.airdb.adapter.MemoryDbAdapter
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() = runBlocking {
    println("Starting Disaster Triage Synchronization Tutorial...")

    // Simulated radio space shared by both devices
    val network = SimulatedNetwork()
```

---

## 2. Initializing Posko Medis (Medical Post)

Posko Medis uses the durable SQLite adapter (`SqliteDbAdapter`) to store records on local disk:

```kotlin
    // 1. Initialize Posko Medis
    val dbFile = File("build/posko-medis.sqlite")
    if (dbFile.exists()) dbFile.delete()
    
    val dbMedis = SqliteDbAdapter.open(dbFile)
    val transportMedis = SimulatedTransport("posko-medis", network)
    val engineMedis = AirDbEngine("posko-medis", dbMedis, transportMedis)
```

---

## 3. Initializing Posko Logistik (Logistics Camp)

Posko Logistik uses an in-memory adapter (`MemoryDbAdapter`) to manage supplies and incoming evacuees:

```kotlin
    // 2. Initialize Posko Logistik
    val dbLogistik = MemoryDbAdapter()
    val transportLogistik = SimulatedTransport("posko-logistik", network)
    val engineLogistik = AirDbEngine("posko-logistik", dbLogistik, transportLogistik)
```

---

## 4. Seeding Offline Data While Disconnected

While both camps are disconnected, each team records local updates:

```kotlin
    println("\n[1] Posko Medis records survivor registrations offline...")
    engineMedis.saveRecord(
        collection = "survivors",
        id = "survivor-001",
        type = MutationType.INSERT,
        payload = """{"name": "Budi Santoso", "condition": "Stable", "bloodType": "O"}""".toByteArray()
    )
    engineMedis.saveRecord(
        collection = "medical_needs",
        id = "med-req-1",
        type = MutationType.INSERT,
        payload = """{"item": "Oxygen Tanks", "urgency": "High", "qty": 10}""".toByteArray()
    )

    println("[2] Posko Logistik records available emergency supplies offline...")
    engineLogistik.saveRecord(
        collection = "survivors",
        id = "survivor-002",
        type = MutationType.INSERT,
        payload = """{"name": "Siti Rahma", "condition": "Minor", "familyContact": "0812345678"}""".toByteArray()
    )
    engineLogistik.saveRecord(
        collection = "supplies_inventory",
        id = "supplies-01",
        type = MutationType.INSERT,
        payload = """{"item": "Ready-to-eat Meals", "qty": 1500}""".toByteArray()
    )
```

At this moment:
- Posko Medis has 1 survivor (`survivor-001`) and 1 medical need.
- Posko Logistik has 1 survivor (`survivor-002`) and 1 supply inventory record.
- Neither camp knows about the other camp's data.

---

## 5. Volunteers Enter Bluetooth Proximity

A field volunteer walks from Posko Medis toward Posko Logistik. As their Bluetooth radios come within range (~10–30m), we start both engines:

```kotlin
    println("\n[3] Devices enter Bluetooth range...")
    val latchMedis = CountDownLatch(1)
    val latchLogistik = CountDownLatch(1)

    engineMedis.addSyncListener { peerId, changeCount ->
        println(" -> [Posko Medis] Reconciled and applied $changeCount mutations from $peerId")
        latchMedis.countDown()
    }

    engineLogistik.addSyncListener { peerId, changeCount ->
        println(" -> [Posko Logistik] Reconciled and applied $changeCount mutations from $peerId")
        latchLogistik.countDown()
    }

    // Start radio operations
    engineMedis.start()
    engineLogistik.start()

    // Wait for the 4-phase synchronization handshake to complete
    latchMedis.await(5, TimeUnit.SECONDS)
    latchLogistik.await(5, TimeUnit.SECONDS)
    delay(300)
```

---

## 6. Verifying Convergent State

Let's inspect the databases of both camps:

```kotlin
    val syncedSurvivorsAtMedis = dbMedis.getActiveRecords("survivors")
    val syncedSurvivorsAtLogistik = dbLogistik.getAllRecords("survivors")

    println("\n[4] State After Synchronization:")
    println("Posko Medis total survivors: ${syncedSurvivorsAtMedis.size}")
    syncedSurvivorsAtMedis.forEach { (id, record) ->
        println(" - [$id] -> ${String(record.payload)} (HLC: ${record.hlcTimestamp})")
    }

    println("\nPosko Logistik total survivors: ${syncedSurvivorsAtLogistik.size}")
    syncedSurvivorsAtLogistik.forEach { (id, record) ->
        println(" - [$id] -> ${String(record.payload)} (HLC: ${record.hlcTimestamp})")
    }
}
```

### Result:
Both camps now hold **2 survivors** (`survivor-001` and `survivor-002`) with identical data and timestamps. The medical post also learned about available meals, and the logistics camp learned about the critical need for oxygen tanks!

---

## Try It Yourself!

You can run this exact simulation live in your terminal right now from the root of the repository:

```bash
cd airdb
./gradlew :airdb-samples:airdb-sim-demo:run
```

Continue to the [Manual](../manual/index.md) to explore the deep inner workings of AirDB's storage engines, causality algorithms, and security mesh.
