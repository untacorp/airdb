# Frequently Asked Questions (FAQ)

### 1. How does AirDB handle physical clock skew if a device's battery dies or clock resets to 1970?
AirDB uses **Hybrid Logical Clocks (HLC)**. When a device with a reset or lagging clock receives incoming mutations from peers, its local HLC immediately ratchets forward to match the highest observed physical time and advances the logical counter. Local writes will always be monotonically newer than prior observed events, ensuring data consistency even without NTP access.

---

### 2. What happens if a record is deleted on Node A, while an old offline Node B updates the same record?
AirDB enforces **tombstone causality**:
- When an entity is deleted, a tombstone record with `MutationType.DELETE` is assigned an HLC timestamp.
- If an update from Node B arrives with an HLC timestamp older than the tombstone's timestamp, the update is rejected and the record remains deleted.
- If Node B (or any node) explicitly writes a brand-new update *after* learning about the deletion (giving it a newer HLC timestamp), the record is cleanly recreated.

---

### 3. How does AirDB prevent Bluetooth battery drain?
Continuous BLE scanning consumes significant power. AirDB is designed to integrate with power managers:
- Central scanning can be duty-cycled (e.g. active for 2 seconds, idle for 8 seconds) when devices are stationary.
- Advertising payloads use minimal 31-byte legacy packets (Service UUID + NodeID) to minimize radio airtime.
- Payloads >256 bytes are automatically compressed with zlib, reducing packet transmission time by 50–70%.

---

### 4. Can AirDB sync large binary files (e.g. photos or database dumps)?
BLE throughput is physically bounded (~20 KB/s to 80 KB/s). AirDB is optimized for **structured delta changes, JSON, Protobuf, and transactional records**. For multi-megabyte photo or video attachments, the recommended pattern is:
1. Replicate metadata, checksums, and references via AirDB over BLE.
2. Trigger an out-of-band P2P high-speed transfer (such as Wi-Fi Aware / Wi-Fi Direct) for the heavy binary blob when needed.

---

### 5. Why not use SQLite / Room directly instead of an adapter?
By defining [`AirDbAdapter`](file:///home/auttomus/Documents/Code/PROJECT/ble-research/airdb/airdb-adapter-api/src/main/kotlin/io/airdb/adapter/AirDbAdapter.kt), AirDB remains completely agnostic of your application's schema, ORM, and platform. You can use it with Room, SQLCipher, Realm, Couchbase Lite, or simple in-memory key-value maps.
