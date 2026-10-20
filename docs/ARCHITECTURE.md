# AirDB Architecture Guide

This document details the architectural principles, threading model, and design invariants that make AirDB robust in low-bandwidth, partition-heavy Bluetooth Low Energy (BLE) environments.

---

## 1. Sans-I/O Architectural Discipline

A recurring failure mode in peer-to-peer mobile libraries is tightly coupling networking sockets or Bluetooth hardware delegates directly with state logic. This leads to queue-order deadlocks, difficult-to-reproduce race conditions, and tests that depend on physical wall-clock timeouts.

AirDB adopts a strict **Sans-I/O** architecture:
* **Protocol & Core logic are pure state transformations**: They take an inbound event or byte array, compute transitions, and return outbound effects or state updates.
* **Platform boundary**: Bluetooth managers, Android GATT callbacks, and hardware timers exist exclusively in `airdb-transport-ble`.
* **Instant simulation**: Because the core engine communicates via `AirDbTransport` interfaces, `SimulatedTransport` allows testing complex multi-node network topologies (with simulated packet drops, reordering, and partitions) completely in memory.

---

## 2. Concurrency & Threading Model

### The Android Single-Flight GATT Invariant
The native Android Bluetooth stack (BlueDroid/Fluoride) is asynchronous and single-flight per connection. If an application invokes `writeCharacteristic` or `notifyCharacteristicChanged` while an existing operation is pending, the Android stack either rejects the call immediately or drops the packet silently.

AirDB solves this with `GattOperationQueue`:
* **State per link**: Every active physical link has an isolated FIFO operation queue with an `inFlight` boolean flag.
* **Trigger on callback**: The next operation in queue is dispatched ONLY when the driver invokes `onCharacteristicWrite` (Central) or `onNotificationSent` (Server).
* **Backpressure bounds**: Queues enforce a strict capacity limit (default 256 operations or 1 MiB) with exponential backoff on transient stack errors.

---

## 3. Distributed Causality & Conflict Resolution

### Hybrid Logical Clocks (HLC)
In off-grid disaster environments or mobile POS setups, physical system clocks can drift by minutes or hours due to lack of network time synchronization (NTP).

AirDB uses **Hybrid Logical Clocks (HLC)** combining:
* `physicalTime`: Milliseconds since epoch.
* `logicalCounter`: An incrementing counter for events occurring within the same physical millisecond.
* `nodeId`: A unique node string identifier acting as a deterministic tie-breaker.

### Monotonic Causality Rules:
1. **Local Writes**: When generating a new timestamp on node $j$:
   $$pt' = \max(latest.pt, \text{now}())$$
   $$lc' = \begin{cases} latest.lc + 1 & \text{if } pt' = latest.pt \\ 0 & \text{otherwise} \end{cases}$$
2. **Remote Ingest**: When node $j$ observes remote timestamp $T_{remote}$:
   $$pt' = \max(\max(latest.pt, T_{remote}.pt), \text{now}())$$
   $$lc' = \begin{cases} \max(latest.lc, T_{remote}.lc) + 1 & \text{if } pt' = latest.pt = T_{remote}.pt \\ latest.lc + 1 & \text{if } pt' = latest.pt \\ T_{remote}.lc + 1 & \text{if } pt' = T_{remote}.pt \\ 0 & \text{otherwise} \end{cases}$$

### Last-Write-Wins (LWW) Merge
When merging mutations for the same record ID:
$$\text{Winner} = \max_{HLC}(Record_{local}, Record_{remote})$$
Because HLC timestamps are strictly monotonic and globally unique across all nodes, every node independently arrives at identical data states.
