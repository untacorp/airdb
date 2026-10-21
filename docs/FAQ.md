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
- Payloads >256 bytes are automatically compressed with zlib Deflate, reducing packet transmission time by 50–70%.

---

### 4. Can AirDB sync large binary files (e.g. photos or database dumps)?
BLE throughput is physically bounded (~20 KB/s to 80 KB/s). AirDB is optimized for **structured delta changes, JSON, Protobuf, and transactional records**. For multi-megabyte photo or video attachments, the recommended pattern is:
1. Replicate metadata, checksums, and references via AirDB over BLE.
2. Trigger an out-of-band P2P high-speed transfer (such as Wi-Fi Aware / Wi-Fi Direct) for the heavy binary blob when needed.

---

### 5. Why use the built-in SQLite adapter vs a custom Room adapter?
AirDB provides `SqliteAirDbAdapter` (`airdb-adapter-sqlite`) and `SqliteDbAdapter` (`AirDbKit`) as turnkey, production-ready storage engines with Write-Ahead Logging (WAL) and indexed HLC ranges. However, by defining [`AirDbAdapter`](file:///home/auttomus/Documents/Code/PROJECT/ble-research/airdb/airdb-adapter-api/src/main/kotlin/io/airdb/adapter/AirDbAdapter.kt), AirDB remains completely agnostic of your application's schema, ORM, and platform. You can plug in Room, SQLCipher, Realm, CoreData, GRDB, or simple in-memory key-value maps.

---

### 6. How does AirDB secure communication against eavesdropping and rogue nodes?
AirDB incorporates **RFC 8439 ChaCha20-Poly1305 AEAD** symmetric encryption at the cluster layer. When a `clusterKey` is configured:
- All packet payloads are encrypted on the wire.
- The entire wire header (including packet types, timestamp, flags, sender ID, and recipient ID) is bound into the Poly1305 MAC as Authenticated Additional Data ($AAD$).
- Any unauthorized injection or wire bit-flip fails authentication and is silently dropped prior to parsing.

---

### 7. Can an intermediate relay node alter records during multi-hop gossip synchronization?
No. AirDB employs **RFC 8032 Ed25519 digital signatures** on every mutation record:
- The originating author node cryptographically signs the record's collection, ID, mutation type, HLC timestamp, and payload with its private key.
- Relaying nodes forward the signed record verbatim.
- Receiving nodes verify the Ed25519 signature against the author's public key before applying changes. If an intermediate node modifies the payload or manipulates the HLC timestamp, the signature check fails and the mutation is rejected.

---

### 8. Does AirDbKit require third-party dependencies on iOS or macOS?
Zero. `AirDbKit` is implemented entirely with Apple standard library frameworks:
- Cryptography: `CryptoKit` (`ChaChaPoly`, `Curve25519.Signing`, `HKDF`)
- Persistence: `libsqlite3` (native SQLite C-bindings)
- Compression: Apple `Compression` framework (zlib Deflate)
- Transport: `CoreBluetooth`
This ensures zero supply-chain risk and zero dependency management overhead.
