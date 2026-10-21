# Frequently Asked Questions (FAQ)

### 1. What happens if a phone's battery dies and its clock resets to the year 1970?
AirDB uses **Hybrid Logical Clocks (HLC)** rather than relying on pure system wall-clock time. 

When a phone with a reset clock connects to a nearby peer, its internal clock automatically ratchets forward to match the highest physical timestamp observed from that peer, plus advances a logical counter. All future local edits will be stamped newer than prior events, preserving correct ordering even without cellular time synchronization (NTP).

---

### 2. What happens if Node A deletes a record while an offline Node B edits it?
AirDB tracks deletions using **tombstones**:
- When you delete a record, AirDB stores a lightweight tombstone record with `MutationType.DELETE` and assigns it a timestamp.
- If Node B reconnects later with an update that has an older timestamp than the tombstone, Node B's update is discarded, and the record remains deleted.
- If a user explicitly edits the record *after* learning about the deletion, that new write will have a newer timestamp and the record will be recreated cleanly.

---

### 3. Does continuous Bluetooth Low Energy scanning drain the phone battery?
AirDB is designed to be power-conscious:
- **Duty cycling**: In production apps, background BLE scanning can be cycled (for example, active for 2 seconds, resting for 8 seconds) when devices are relatively stationary.
- **Compact advertising**: Devices broadcast minimal 31-byte legacy packets (Service UUID and Node ID) to keep radio airtime low.
- **Payload compression**: Packets larger than 256 bytes are automatically compressed using zlib Deflate, cutting transmission time and radio power by 50% to 70%.

---

### 4. Can I use AirDB to sync photos, audio recordings, or database backups?
Bluetooth Low Energy has a physical throughput ceiling around 20 KB/s to 80 KB/s. 

AirDB is optimized for **structured application data** (such as survivor triage records, inventory tables, chat history, and JSON or Protobuf payloads). For large binary attachments (such as high-resolution photos or audio recordings):
1. Save the image to local storage and sync its file path, metadata, and SHA-256 hash across peers via AirDB.
2. When peers need the raw image file, trigger an out-of-band high-speed direct transfer (such as Wi-Fi Aware, Wi-Fi Direct, or Multipeer Connectivity).

---

### 5. Why use the built-in SQLite adapter instead of my existing Room or CoreData setup?
The built-in SQLite adapters (`SqliteDbAdapter` in `:airdb-adapter-sqlite` and `SqliteDbAdapter` in `AirDbKit`) are turnkey, zero-boilerplate engines that configure Write-Ahead Logging (WAL) and indexed changelogs automatically.

However, if your application already uses Room, SQLDelight, GRDB, SwiftData, or CoreData, you do **not** have to switch. You can implement the [`AirDbAdapter`](../manual/persistence/index.md) interface on top of your existing tables in about 50 lines of code.

---

### 6. Can an intermediate phone modify or forge records when relaying data in a mesh?
No. AirDB uses **RFC 8032 Ed25519 digital signatures** on every mutation record:
- The device that creates the change signs the collection name, entity ID, mutation type, timestamp, and payload bytes with its private key.
- Intermediate phones pass the signed record along unchanged.
- Receiving devices verify the signature against the author's public key before saving changes. If a relay modifies a single byte of the payload or changes the timestamp, verification fails and the change is discarded immediately.

---

### 7. Does AirDbKit require external package dependencies on iOS or macOS?
No. `AirDbKit` has **zero external SPM dependencies**. It relies entirely on native Apple standard frameworks:
- Cryptography: `CryptoKit` (`ChaChaPoly`, `Curve25519.Signing`, `HKDF`)
- Persistence: `libsqlite3` (built into the Apple operating system)
- Compression: Apple `Compression` framework (zlib Deflate)
- Radio Transport: `CoreBluetooth`

This means you will never encounter third-party dependency conflicts or supply-chain security alerts when embedding AirDbKit in your Apple apps.
