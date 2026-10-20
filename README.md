<div align="center">

# AirDB

**Decentralized, Local-First P2P Database Synchronization Engine over Bluetooth Low Energy**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org)
[![Swift](https://img.shields.io/badge/Swift-5.9%2B-orange.svg)](AirDbKit/)
[![Architecture](https://img.shields.io/badge/Architecture-Sans--I%2FO-success.svg)](docs/ARCHITECTURE.md)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)](#testing)

*Zero Internet. Zero Central Servers. Automatic Peer Reconciliation.*

</div>

---

## Overview

**AirDB** is an open-source, modular, local-first database replication engine designed for off-grid operations. When mobile devices running AirDB enter Bluetooth Low Energy (BLE) proximity (~10–30 meters), they automatically discover each other, negotiate causal state differences, and stream database mutations directly device-to-device.

### Primary Use Cases

- **Disaster Response & Emergency Camps**: Coordinate survivor registries, triage status, and medical supplies in disaster zones where cellular towers and power grids are offline.
- **Off-Grid Mobile POS**: Synchronize inventory, menu updates, and offline sales transactions across distributed cashier tablets at outdoor venues or remote markets.
- **Field Logistics & Conservation**: Replicate wildlife observations, inspection checklists, and sensor logs between rangers in the field without satellite connectivity.

---

## Key Capabilities

- **Dual-Role BLE Concurrency**: Every device operates simultaneously as both a GATT Central (scanner) and GATT Peripheral (advertiser).
- **Sans-I/O Layered Design**: Core causality logic, vector clocks, and wire codecs are 100% pure Kotlin with zero Android or socket dependencies.
- **Monotonic Hybrid Logical Clocks (HLC)**: Provides deterministic distributed causality and Last-Write-Wins (LWW) resolution without network time synchronization.
- **Serialized In-Flight GATT Queue**: Enforces single-flight operation ordering per link, eliminating Android's native Bluetooth stack race conditions.
- **Automated Compression & Framing**: Serializes compact binary packets, automatically compressing payloads (>256 B) via zlib and partitioning packets exceeding link MTU.
- **Pluggable Database Adapter Seam**: Connects to any database engine (Room, SQLite, Realm, SQLCipher, or Key-Value stores) by implementing a single interface.
- **Deterministic Sans-I/O Simulator**: Includes `airdb-transport-sim` to test complete multi-node sync topologies in memory in milliseconds without hardware radios.

---

## Architecture

```
+-------------------------------------------------------------+
| Application / Local Database (Room, SQLite, In-Memory)      |
+------------------------------+------------------------------+
                               | Implements AirDbAdapter
+------------------------------v------------------------------+
| airdb-core (Causal Ordering & Reconciliation State Machine) |
| - Hybrid Logical Clock (HLC)                                |
| - Vector Clock Tracker                                      |
| - SyncSession 4-Phase Protocol                              |
+------------------------------+------------------------------+
                               | Encodes/Decodes
+------------------------------v------------------------------+
| airdb-protocol (Binary Wire Codec & MTU Fragmentation)      |
| - Fixed 15-byte Header + Variable Payload                   |
| - zlib Deflate Compression                                  |
| - 13-byte Fragment Envelope with AssemblyBuffer (30s TTL)   |
+------------------------------+------------------------------+
                               | Streams Bytes
+------------------------------v------------------------------+
| airdb-transport-api (Universal Transport Seam)              |
|       ^                                        ^            |
|       |                                        |            |
| +-----+-----------------------+ +--------------+----------+ |
| | airdb-transport-ble         | | airdb-transport-sim     | |
| | (Android Native BLE Stack)  | | (Sans-I/O In-Memory     | |
| | - GATT Central & Server     | |  Multi-node Simulator)  | |
| | - Single-Flight Queue       | +-------------------------+ |
| +-----------------------------+                             |
+-------------------------------------------------------------+
```

---

## Quick Start

### 1. Implement `AirDbAdapter` for Local Persistence

```kotlin
class RoomAirDbAdapter(private val database: AppDatabase) : AirDbAdapter {

    override suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int): List<ChangeRecord> {
        return database.changelogDao().getChangesAfter(sinceHlcTimestamp ?: "", limit)
            .map { it.toChangeRecord() }
    }

    override suspend fun applyChanges(changes: List<ChangeRecord>) {
        database.withTransaction {
            for (change in changes) {
                val current = database.changelogDao().getLatestForId(change.collection, change.id)
                if (current == null || change.hlcTimestamp > current.hlcTimestamp) {
                    database.changelogDao().upsert(change.toEntity())
                    if (change.type == MutationType.DELETE) {
                        database.survivorDao().deleteById(change.id)
                    } else {
                        database.survivorDao().upsert(Survivor.fromBytes(change.payload))
                    }
                }
            }
        }
    }

    override suspend fun getLatestHlc(): String? {
        return database.changelogDao().getMaxHlc()
    }
}
```

### 2. Initialize and Start AirDB

```kotlin
val adapter = RoomAirDbAdapter(myAppDatabase)
val transport = AndroidBleTransport(context)

val engine = AirDbEngine(
    nodeName = "posko-merapi-1",
    adapter = adapter,
    transport = transport
)

// Listen for sync completion events
engine.addSyncListener { peerId, changeCount ->
    Log.i("AirDB", "Applied $changeCount mutations from peer: $peerId")
}

// Start discovery & background sync
engine.start()

// Record a new local mutation
engine.saveRecord(
    collection = "survivors",
    id = "survivor-101",
    type = MutationType.INSERT,
    payload = jsonString.toByteArray()
)
```

---

## Modules

| Module | Description | Dependencies |
|---|---|---|
| [`airdb-adapter-api`](airdb-adapter-api/) | Core database abstraction contracts (`AirDbAdapter`, `ChangeRecord`). | Pure Kotlin |
| [`airdb-protocol`](airdb-protocol/) | Binary packet framing, zlib compression, and MTU fragmentation buffer. | Pure Kotlin |
| [`airdb-core`](airdb-core/) | HLC, Vector Clock, and 4-phase synchronization state machine. | Pure Kotlin |
| [`airdb-transport-api`](airdb-transport-api/) | P2P transport interfaces, events, and commands. | Pure Kotlin |
| [`airdb-transport-sim`](airdb-transport-sim/) | In-memory packet radio mesh simulator for fast automated tests. | Pure Kotlin |
| [`airdb-transport-ble`](airdb-transport-ble/) | Production Android BLE GATT server/client and anti-race queue. | Android SDK / Kotlin |
| [`airdb-samples`](airdb-samples/) | Runnable CLI simulation demos demonstrating multi-node sync. | Kotlin |
| [`AirDbKit`](AirDbKit/) | Native Swift package for iOS, macOS, watchOS with CoreBluetooth support. | Swift 5.9+ / Apple SDK |

---

## Testing

AirDB embraces a **Sans-I/O testing discipline**. You can run the entire multi-node test suite without physical hardware:

```bash
./gradlew test
```

To run the interactive off-grid disaster response sync simulation:

```bash
./gradlew :airdb-samples:airdb-sim-demo:run
```

---

## Documentation Directory

- [Architecture Guide](docs/ARCHITECTURE.md): Concurrency, Sans-I/O, and causality contracts.
- [Wire Protocol Specification](docs/PROTOCOL_SPEC.md): Binary packet layouts and fragment headers.
- [Getting Started Guide](docs/GETTING_STARTED.md): Step-by-step Room/SQLite integration guide.
- [Coding Standards](docs/CODING_STANDARDS.md): Guidelines on concurrency, immutability, and tests.
- [Frequently Asked Questions (FAQ)](docs/FAQ.md): Detailed answers to operational and design questions.
- [Architecture Decision Records (ADR)](docs/adr/): Context and justifications for foundational decisions.

---

## License

AirDB is licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for details.
