# Testing Without Hardware (Sans-I/O Simulation)

Testing distributed Bluetooth applications on physical hardware is notoriously slow. Pairing phones, managing battery levels, walking across rooms to test range, and waiting for radio timeouts makes automated continuous integration (CI) painful.

AirDB embraces a **Sans-I/O architecture** that completely separates networking protocols and causality state machines from physical hardware radios.

---

## 1. What is Sans-I/O?

In a traditional library, state logic is tightly coupled to socket delegates or Android Bluetooth callbacks.

In AirDB:
- The core engine (`AirDbEngine`), wire codecs (`BinaryCodec`), and causality trackers (`HybridLogicalClock`, `VectorClock`) are pure standard Kotlin / Swift. They take bytes or events in, perform state transformations, and emit outgoing packets.
- Bluetooth hardware code lives exclusively inside transport drivers (`AndroidBleTransport` and `CoreBluetoothTransport`).
- Both drivers implement the universal **`AirDbTransport`** interface.

Because of this seam, we can swap out the physical Bluetooth radio with **`SimulatedTransport`**!

---

## 2. Running In-Memory Multi-Node Tests

You can spin up an entire simulated Bluetooth mesh network in a single unit test:

```kotlin
import io.airdb.adapter.MemoryDbAdapter
import io.airdb.adapter.MutationType
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MultiNodeSyncTest {

    @Test
    fun testTwoNodeConvergence() = runBlocking {
        // 1. Create a simulated virtual radio space
        val network = SimulatedNetwork()

        // 2. Initialize Node A (Outpost Alpha)
        val dbA = MemoryDbAdapter()
        val transportA = SimulatedTransport("node-A", network)
        val engineA = AirDbEngine("node-A", dbA, transportA)

        // 3. Initialize Node B (Outpost Beta)
        val dbB = MemoryDbAdapter()
        val transportB = SimulatedTransport("node-B", network)
        val engineB = AirDbEngine("node-B", dbB, transportB)

        // 4. Save offline records on Node A
        engineA.saveRecord("patients", "p-01", MutationType.INSERT, "Alice".toByteArray())

        // 5. Connect nodes virtually and start engines
        engineA.start()
        engineB.start()

        // Give the virtual radio 100 milliseconds to complete the 4-phase sync
        kotlinx.coroutines.delay(100)

        // 6. Assert that Node B received and applied the record!
        val recordsOnB = dbB.getAllRecords("patients")
        assertEquals(1, recordsOnB.size)
        assertEquals("Alice", String(recordsOnB["p-01"]!!.payload))
    }
}
```

This test runs in **less than 200 milliseconds** on your laptop or inside GitHub Actions CI, with zero physical radios, zero flakiness, and 100% deterministic results.

---

## 3. Running the Interactive Simulation Demo

AirDB includes a ready-to-run CLI demonstration simulating two disaster response outposts exchanging medical triage and relief supplies:

```bash
cd airdb
./gradlew :airdb-samples:airdb-sim-demo:run
```

You can view the full source code for the demonstration in [`airdb-samples/airdb-sim-demo/src/main/kotlin/io/airdb/demo/DisasterReliefDemo.kt`](file:///home/auttomus/Documents/Code/PROJECT/ble-research/airdb/airdb-samples/airdb-sim-demo/src/main/kotlin/io/airdb/demo/DisasterReliefDemo.kt).
