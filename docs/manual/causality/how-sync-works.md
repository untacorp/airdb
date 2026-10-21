# How Synchronization Works

This chapter explains how AirDB tracks changes across disconnected devices, decides who edited what, and guarantees that every device converges to identical data without a central server.

---

## 1. Why Physical Clocks Aren't Enough

In normal web development, the backend server assigns timestamps: `created_at = NOW()`.

In an off-grid disaster area or remote field camp, there is no central server. Devices rely on their internal battery clocks. However:
- A phone battery might die, resetting the clock to January 1, 1970.
- Two phones might differ by 3 minutes due to crystal oscillator drift.
- If we simply trusted each phone's physical clock, an edit made yesterday on a device with a fast clock could accidentally overwrite a true newer edit made today.

To solve this, AirDB uses **Hybrid Logical Clocks (HLC)**.

---

## 2. Hybrid Logical Clocks (HLC)

An HLC timestamp consists of three parts:

```
Timestamp = (physical_time, logical_counter, node_id)
```

1. **`physical_time`**: The device's current wall-clock time in milliseconds.
2. **`logical_counter`**: An incrementing counter (0, 1, 2...) for multiple edits that occur within the exact same millisecond.
3. **`node_id`**: A unique device name or ID used as a tie-breaker if two devices create an edit at the exact same physical millisecond and counter.

### How Timestamps Progress
- **Local Writes**: When you save a record locally, your clock sets `physical_time = max(last_known_time, now())`. If `now()` hasn't advanced, it increments `logical_counter`.
- **Learning from Peers**: When you receive an update from a nearby peer with a higher timestamp, your clock immediately ratchets forward to match the peer's time. Your clock will never step backward, even if your phone's battery died.

### Comparing Two Timestamps
Comparing two HLC timestamps is simple and deterministic:
1. Compare `physical_time`. Whichever is larger wins.
2. If `physical_time` is tied, compare `logical_counter`. Whichever is larger wins.
3. If both are tied, compare `node_id` alphabetically as a deterministic tie-breaker.

Because of this total ordering, every device in the mesh reaches the exact same conclusion about which edit came last.

---

## 3. Vector Clocks: Knowing What You Missed

When two phones connect over Bluetooth, we do not want to re-send the entire database over slow radio bandwidth. We only want to transmit what the other device hasn't seen yet.

AirDB tracks this with a **Vector Clock**: a lightweight dictionary mapping each known device name to the latest HLC timestamp seen from that device:

```json
{
  "posko-medis": "1710928300000-0-posko-medis",
  "posko-logistik": "1710928295000-2-posko-logistik"
}
```

When Phone A meets Phone B:
1. Phone B sends its knowledge map: *"Here is the newest timestamp I have seen from each device."*
2. Phone A compares its own changelog against Phone B's map.
3. Phone A only sends records that have timestamps newer than what Phone B has seen.
4. Bandwidth is kept minimal: $\mathcal{O}(|\text{new changes}|)$ instead of $\mathcal{O}(|\text{entire database}|)$.

---

## 4. The 4-Phase Synchronization Handshake

When two devices connect over Bluetooth Low Energy, they execute a 4-step conversation:

```
Device A (Scanner / Central)                         Device B (Advertiser / Peripheral)
       |                                                             |
       |  Phase 1: HANDSHAKE (Hello, I am Device A)                  |
       |------------------------------------------------------------>|
       |                                                             |
       |  Phase 2: HANDSHAKE_ACK (Hello A, I am Device B)            |
       |<------------------------------------------------------------|
       |                                                             |
       |  Phase 3a: RECONCILE_REQ (Here is my Vector Clock)          |
       |------------------------------------------------------------>|
       |                                                             |
       |  Phase 3b: RECONCILE_RESP (Here is my Vector Clock)         |
       |<------------------------------------------------------------|
       |                                                             |
       |  Phase 3c: DELTA_BATCH (Missing changes B -> A)             |
       |<------------------------------------------------------------|
       |                                                             |
       |  Phase 3d: DELTA_BATCH (Missing changes A -> B)             |
       |------------------------------------------------------------>|
       |                                                             |
       |  Phase 4: DELTA_ACK (Confirming batch applied successfully) |
       |<------------------------------------------------------------>|
```

### Real-Time Updates
Once the initial 4-phase synchronization is complete, both devices stay in the `SYNCHRONIZED` state. If a user saves a new record while connected, AirDB streams that single mutation across the active link immediately without repeating the full handshake.

---

## 5. Conflict Resolution & Tombstones

### Last-Write-Wins (LWW)
If Nurse Siti updates survivor #101 on Phone A, and Doctor Budi updates survivor #101 on Phone B while disconnected:
- Both updates are stored locally with their respective HLC timestamps.
- When Phone A and Phone B connect, the record with the newer HLC timestamp wins and overwrites the older version.

### Deletions with Tombstones
If we simply erased a row from SQLite when a user clicked "Delete", an offline phone that hasn't heard about the deletion would send its copy back during the next sync, accidentally resurrecting the record!

To prevent this:
1. Deleting an entity creates a **tombstone** record with `MutationType.DELETE` and an HLC timestamp.
2. When an old update arrives from a peer with an older timestamp than the tombstone, AirDB rejects it.
3. If a user intentionally creates a new record with the same ID *after* the deletion, it receives a newer timestamp and is recreated cleanly.

---

## 6. The Convergence Guarantee

Because every node uses identical HLC comparison rules and deterministic Last-Write-Wins, distributed systems theory guarantees **Strong Eventual Consistency**:

As long as devices occasionally come within Bluetooth range of one another (directly or through intermediate gossip hops), every replica will converge to 100% identical state without needing a central coordinator.
