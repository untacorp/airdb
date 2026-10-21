# AirDB Architecture Guide

This document details the architectural principles, threading model, causal mathematics, persistence seam, and zero-trust cryptographic mesh that make AirDB robust in low-bandwidth, partition-heavy Bluetooth Low Energy (BLE) environments.

---

## 1. Sans-I/O Architectural Discipline

A recurring failure mode in peer-to-peer mobile libraries is tightly coupling networking sockets or Bluetooth hardware delegates directly with state logic. This leads to queue-order deadlocks, difficult-to-reproduce race conditions, and tests that depend on physical wall-clock timeouts.

AirDB adopts a strict **Sans-I/O** architecture:
* **Protocol & Core logic are pure state transformations**: They take an inbound event or byte array, compute transitions, and return outbound effects or state updates.
* **Platform boundary**: Bluetooth managers, Android GATT callbacks, and hardware timers exist exclusively in `airdb-transport-ble` and `CoreBluetoothTransport`.
* **Instant simulation**: Because the core engine communicates via `AirDbTransport` interfaces, `SimulatedTransport` allows testing complex multi-node network topologies (with simulated packet drops, reordering, and partitions) completely in memory in milliseconds.

---

## 2. Concurrency & Threading Model

### The Android Single-Flight GATT Invariant
The native Android Bluetooth stack (BlueDroid/Fluoride) is asynchronous and single-flight per connection. If an application invokes `writeCharacteristic` or `notifyCharacteristicChanged` while an existing operation is pending, the Android stack either rejects the call immediately or drops the packet silently.

AirDB solves this with `GattOperationQueue`:
* **State per link**: Every active physical link has an isolated FIFO operation queue with an `inFlight` boolean flag.
* **Trigger on callback**: The next operation in queue is dispatched ONLY when the driver invokes `onCharacteristicWrite` (Central) or `onNotificationSent` (Server).
* **Backpressure bounds**: Queues enforce a strict capacity limit (default 256 operations or 1 MiB) with exponential backoff on transient stack errors.

### Swift Actor Model on Apple Platforms
On Apple platforms, `AirDbKit` implements the core engine as a Swift `actor`:
* **Data Race Freedom**: Compile-time enforcement of isolated mutable state across threads.
* **Non-Blocking Delegates**: Asynchronous CoreBluetooth delegate callbacks forward frames into structured Swift tasks without blocking radio threads.
* **Zero Third-Party Dependencies**: Relies 100% on Apple standard frameworks (`CryptoKit`, `Compression`, `CoreBluetooth`, and native `libsqlite3`).

---

## 3. Distributed Causality & Formal Mathematics

### Hybrid Logical Clocks (HLC)

In off-grid disaster environments or mobile POS setups, physical system clocks can drift by minutes or hours due to lack of network time synchronization (NTP).

AirDB uses **Hybrid Logical Clocks (HLC)** combining physical time, an incrementing counter, and a node tie-breaker:

```math
T = (pt, lc, nodeId)
```

Where:
* $pt \in \mathbb{N}_0$: Physical time (epoch milliseconds).
* $lc \in \mathbb{N}_0$: Monotonic logical counter for events sharing the same physical millisecond.
* $nodeId \in \Sigma^+$: Globally unique node identifier ensuring total ordering.

### Total Order Relation ($<_{HLC}$)

For any two timestamps $T_1 = (pt_1, lc_1, node_1)$ and $T_2 = (pt_2, lc_2, node_2)$, the strict total order $<_{HLC}$ is defined lexicographically:

```math
T_1 <_{HLC} T_2 \iff \begin{cases}
pt_1 < pt_2 \\
pt_1 = pt_2 \land lc_1 < lc_2 \\
pt_1 = pt_2 \land lc_1 = lc_2 \land node_1 <_{lex} node_2
\end{cases}
```

This relation forms a strict total order: irreflexive, asymmetric, transitive, and connex. Every pair of timestamps is deterministically comparable across all distributed replicas.

### Monotonic Causality Progression Rules

#### Rule 1: Local Mutation
When generating a new timestamp on node $j$:

```math
pt' = \max(latest.pt, \text{now}())
```

```math
lc' = \begin{cases}
latest.lc + 1 & \text{if } pt' = latest.pt \\
0 & \text{otherwise}
\end{cases}
```

#### Rule 2: Remote Message Ingestion
When node $j$ observes remote timestamp $T_{remote} = (pt_{rem}, lc_{rem}, node_{rem})$:

```math
pt' = \max(\max(latest.pt, pt_{rem}), \text{now}())
```

```math
lc' = \begin{cases}
\max(latest.lc, lc_{rem}) + 1 & \text{if } pt' = latest.pt = pt_{rem} \\
latest.lc + 1 & \text{if } pt' = latest.pt \\
lc_{rem} + 1 & \text{if } pt' = pt_{rem} \\
0 & \text{otherwise}
\end{cases}
```

---

## 4. Vector Clock State Tracking & Delta Reconciliation

### Formal Definition of Vector Clock

A Vector Clock $V$ on a node is a partial function mapping originating node identifiers to their highest known causal HLC timestamp:

```math
V : \text{NodeId} \to \text{HLC}
```

### Causal Dominance & Partial Order

Given two vector clocks $V_A$ and $V_B$:

```math
V_A \le V_B \iff \forall k \in \text{dom}(V_A): V_A[k] \le_{HLC} V_B[k]
```

```math
V_A < V_B \iff (V_A \le V_B) \land (V_A \ne V_B)
```

If neither $V_A \le V_B$ nor $V_B \le V_A$, the states are causally concurrent:

```math
V_A \parallel V_B \iff \neg(V_A \le V_B) \land \neg(V_B \le V_A)
```

### Exact Delta Reconciliation Set

When node $B$ reconciles with node $A$ presenting knowledge vector $V_A$, the minimal missing delta batch $\Delta(B \to A)$ is computed as:

```math
\Delta(B \to A) = \left\{ r \in \text{Records}_B \mid r.hlc >_{HLC} V_A[r.nodeId] \right\}
```

Where $V_A[r.nodeId] = -\infty$ if $r.nodeId \notin \text{dom}(V_A)$.

This invariant guarantees that:
1. Re-connecting nodes transmit only novel mutations created during partition intervals.
2. Bandwidth consumption over BLE is strictly bounded to missing records $\mathcal{O}(|\Delta|)$ rather than total database size $\mathcal{O}(|\text{DB}|)$.

### Conflict Resolution: Last-Write-Wins (LWW)

For any record key $K$ subject to concurrent mutations $M_A$ and $M_B$:

```math
\text{Winner}(K) = \begin{cases}
M_A & \text{if } M_A.hlc >_{HLC} M_B.hlc \\
M_B & \text{otherwise}
\end{cases}
```

### Convergence Theorem

Under epidemic pairwise gossip across any connected graph component $G = (V, E)$, the database state $S_i(t)$ of every node $i$ monotonically converges:

```math
\lim_{t \to \infty} S_i(t) = S_j(t) = \bigcup_{k \in \text{Keys}} \max_{<_{HLC}} \left( \text{Mutations}(k) \right)
```

All replicas independently reach 100% causal identity without central coordination or distributed consensus rounds.

---

## 5. Durable SQLite Persistence Seam

AirDB provides production-ready, durable storage engines across all platforms:
* **Android / JVM**: `SqliteAirDbAdapter` in `:airdb-adapter-sqlite`.
* **Apple Platforms**: `SqliteDbAdapter` in `AirDbKit` utilizing native `libsqlite3`.

### Schema Architecture & Change Tracking

The underlying storage maintains an append-only, indexed changelog table alongside domain tables:

```sql
CREATE TABLE IF NOT EXISTS _airdb_changes (
    collection TEXT NOT NULL,
    id TEXT NOT NULL,
    mutation_type INTEGER NOT NULL,
    payload BLOB,
    hlc_timestamp TEXT NOT NULL,
    author_public_key BLOB,
    signature BLOB,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (collection, id)
);

CREATE INDEX IF NOT EXISTS idx_airdb_changes_hlc
ON _airdb_changes(hlc_timestamp ASC);
```

### Durability & ACID Guarantees
1. **Write-Ahead Logging (WAL)**: All adapters enable `PRAGMA journal_mode = WAL` and `PRAGMA synchronous = NORMAL`, permitting non-blocking concurrent readers during write transactions.
2. **Atomic Batch Application**: Inbound delta batches are applied inside an immediate transaction (`BEGIN IMMEDIATE` ... `COMMIT`). If verification fails or disk write aborts, the transaction rolls back cleanly.
3. **Anti-Resurrection Tombstone Lifecycle**: Soft-deleted entities retain an entry with `mutation_type = 2` (DELETE) and the tombstone HLC timestamp. Any inbound mutation with an older timestamp is discarded, preventing obsolete sync passes from resurrecting deleted records.

---

## 6. Zero-Trust Cryptographic Mesh & Provenance

In off-grid, multi-hop mesh deployments (such as disaster survivor coordination or remote mobile POS networks), packets pass through intermediate relay devices that may be untrusted, misconfigured, or adversarial. AirDB implements a two-tier **Zero-Trust Cryptographic Mesh**.

```
+---------------------------------------------------------------------------------+
| Layer 1: Cluster-Level Symmetric AEAD (RFC 8439 ChaCha20-Poly1305)             |
| Encrypts over-the-air payload, binds packet header as AAD, authenticates link  |
+---------------------------------------------------------------------------------+
| Layer 2: Record-Level Asymmetric Signatures (RFC 8032 Ed25519)                 |
| Cryptographically binds each mutation to author public key, prevents forgery   |
+---------------------------------------------------------------------------------+
| Layer 3: Key Derivation & Session Materials (RFC 5869 HKDF-SHA256)             |
| Deterministically derives cluster and session keys from shared pre-shared keys |
+---------------------------------------------------------------------------------+
```

### Layer 1: Cluster-Level Symmetric AEAD (ChaCha20-Poly1305)

To prevent eavesdropping and unauthorized packet injection into a local operational cluster:
1. **AEAD Cipher**: ChaCha20-Poly1305 (RFC 8439) with a 256-bit symmetric `clusterKey`.
2. **Header Authentication Binding (AAD)**: The entire 23-byte (or 31-byte) wire header—including `magic`, `version`, `type`, `flags`, `timestamp`, `payloadLength`, `senderId`, and optional `recipientId`—is passed as Authenticated Additional Data ($AAD$).
3. **Nonce Construction**: A fresh 12-byte CSPRNG nonce is generated for every encrypted transmission.
4. **Wire Representation**:

```math
\text{WirePayload} = \text{Nonce}_{12\text{B}} \parallel \text{Ciphertext}_{N\text{B}} \parallel \text{Tag}_{16\text{B}}
```

Any modification to the header flags, timestamp, or ciphertext causes Poly1305 verification failure, resulting in silent drop before payload parsing.

### Layer 2: Record-Level Asymmetric Signatures (Ed25519)

Cluster encryption protects over-the-air frames between immediate peers. However, in a multi-hop mesh network, intermediate relay nodes decrypt and forward packets. To ensure malicious or compromised relays cannot forge, alter, or backdate database records, every mutation is digitally signed by the original author.

#### Canonical Signing Tuple

For a record $R$, the canonical byte representation for signing is:

```math
M(R) = \text{collection} \parallel \text{id} \parallel \text{type} \parallel \text{hlcTimestamp} \parallel \text{payload}
```

#### Signature Generation

The originating node signs $M(R)$ using its private Ed25519 key:

```math
\sigma = \text{Ed25519Sign}(\text{PrivateKey}_{author}, M(R))
```

The resulting 32-byte author public key and 64-byte signature $\sigma$ are attached directly to the `ChangeRecord`.

#### Zero-Trust Verification Rule

When a receiving node processes incoming records with `enforceSignatures = true`:

```math
\text{Valid}(R) = \begin{cases}
1 & \text{if } \text{Ed25519Verify}(\text{PublicKey}_{author}, M(R), \sigma) = \text{true} \\
0 & \text{otherwise}
\end{cases}
```

If $\text{Valid}(R) = 0$, the mutation is dropped immediately. It is never applied to the local database adapter nor forwarded to downstream gossip peers.
