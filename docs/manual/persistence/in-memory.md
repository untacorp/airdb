# In-Memory KV Testing Adapter

When developing automated test suites, running migrations or managing SQLite files on disk can slow down feedback loops. AirDB provides an in-memory key-value adapter (`MemoryDbAdapter`) designed for instant unit and integration testing.

---

## 1. Quick Usage

=== "Kotlin"

    ```kotlin
    import io.airdb.adapter.MemoryDbAdapter
    import io.airdb.core.engine.AirDbEngine
    import io.airdb.transport.sim.SimulatedTransport

    val adapter = MemoryDbAdapter()
    val transport = SimulatedTransport("test-node", simulatedNetwork)
    val engine = AirDbEngine("test-node", adapter, transport)

    engine.start()
    ```

=== "Swift"

    ```swift
    import AirDbKit

    // In AirDbKit, you can use the in-memory SQLite constructor for testing:
    let adapter = try SqliteDbAdapter.inMemory()
    let transport = SimulatedTransport(nodeId: 1, network: simulatedNetwork)
    let engine = AirDbEngine(nodeName: "test-node", adapter: adapter, transport: transport)

    await engine.start()
    ```

---

## 2. In-Memory Invariants

The `MemoryDbAdapter`:
- Stores records in concurrent, thread-safe hash maps (`ConcurrentHashMap`).
- Implements the exact same Last-Write-Wins (LWW) conflict logic and tombstone tracking as disk-backed adapters.
- Provides helper inspection methods like `adapter.getAllRecords(collection)` and `adapter.clear()` for fast test assertions.
