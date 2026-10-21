# Cookbook: In-Memory Key-Value Adapter

This guide demonstrates how to use or implement an in-memory database adapter for fast prototyping, automated integration testing, CLI tools, or ephemeral synchronization sessions.

---

## 1. Built-in `MemoryDbAdapter`

AirDB provides `MemoryDbAdapter` in `:airdb-adapter-api` (Kotlin) and `AirDbKit` (Swift). It holds records in a thread-safe concurrent collection and requires zero disk configuration.

### Kotlin / Android / JVM:

```kotlin
import io.airdb.adapter.MemoryDbAdapter
import io.airdb.core.engine.AirDbEngine
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport

// 1. Create in-memory adapter
val adapter = MemoryDbAdapter()

// 2. Initialize in-memory simulated transport for instant multi-node testing
val network = SimulatedNetwork()
val transport = SimulatedTransport(nodeId = 1L, network = network)

val engine = AirDbEngine(
    nodeName = "test-node-1",
    adapter = adapter,
    transport = transport
)

engine.start()
```

### Apple Platforms (Swift):

```swift
import AirDbKit

let adapter = MemoryDbAdapter()
let transport = SimulatedTransport(nodeId = 1)

let engine = AirDbEngine(
    nodeName: "test-node-ios-1",
    adapter: adapter,
    transport: transport
)

engine.start()
```

---

## 2. Custom Key-Value Store Implementation

If you want to back AirDB with an existing in-memory cache (such as Caffeine, an LRU cache, or Redis):

```kotlin
package com.example.cache

import io.airdb.adapter.AirDbAdapter
import io.airdb.adapter.ChangeRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

class CustomMemoryKvAdapter : AirDbAdapter {

    // Primary record storage: (collection:id) -> ChangeRecord
    private val records = ConcurrentHashMap<String, ChangeRecord>()

    // HLC-ordered index: hlcTimestamp -> (collection:id)
    private val hlcIndex = ConcurrentSkipListMap<String, String>()

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        val tailMap = if (sinceHlcTimestamp == null) {
            hlcIndex
        } else {
            hlcIndex.tailMap(sinceHlcTimestamp, false)
        }

        return tailMap.values
            .take(limit)
            .mapNotNull { key -> records[key] }
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        for (change in changes) {
            val key = "${change.collection}:${change.id}"
            records.compute(key) { _, existing ->
                if (existing == null || change.hlcTimestamp > existing.hlcTimestamp) {
                    if (existing != null) {
                        hlcIndex.remove(existing.hlcTimestamp)
                    }
                    hlcIndex[change.hlcTimestamp] = key
                    change
                } else {
                    existing
                }
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return hlcIndex.lastKey()
    }
}
```
