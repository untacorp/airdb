# AirDB Wire Protocol Specification (v1)

This specification defines the on-wire binary packet formats, framing, AEAD encryption envelope, fragmentation, and handshake sequence for AirDB over Bluetooth Low Energy (BLE).

---

## 1. Network Byte Order

All multi-byte numeric values (Short, Int, Long) are encoded in **Network Byte Order (Big-Endian)**.

---

## 2. Packet Framing (`AirDbPacket`)

```text
Fixed Header (23 bytes without recipient, 31 bytes with recipient):
+------------+---------+------+-------+-----------+----------------+-----------+--------------------+
| Magic (2B) | Ver(1B) | Type | Flags | Timestamp | Payload Length | Sender ID | Recipient ID (opt) |
| 0x41 0x44  | 0x01    | 1B   | 1B    | 8B (Long) | 2B (UInt16)    | 8B (Long) | 8B (Long)          |
+------------+---------+------+-------+-----------+----------------+-----------+--------------------+

Payload:
+----------------------------------------------------------------------------------------------------+
| Variable Byte Array (0 to 65,535 bytes)                                                            |
+----------------------------------------------------------------------------------------------------+
```

### Header Fields:

* **Magic (2 bytes)**: Fixed ASCII bytes `0x41 0x44` ("AD" for AirDB). Rejects stray packets from unrelated BLE networks.
* **Version (1 byte)**: Protocol version. Currently `0x01`.
* **Type (1 byte)**: Packet type code:
  * `0x01`: `HANDSHAKE`
  * `0x02`: `HANDSHAKE_ACK`
  * `0x03`: `RECONCILE_REQ`
  * `0x04`: `RECONCILE_RESP`
  * `0x05`: `DELTA_BATCH`
  * `0x06`: `DELTA_ACK`
  * `0x0F`: `FRAGMENT`
* **Flags (1 byte)**: Bitmask indicating payload transformations:
  * Bit 0 (`0x01`): `FLAG_COMPRESSED` (Payload is zlib Deflate compressed).
  * Bit 1 (`0x02`): `FLAG_HAS_RECIPIENT` (8-byte Recipient ID is present in header).
  * Bit 2 (`0x04`): `FLAG_ENCRYPTED` (Payload is encrypted with ChaCha20-Poly1305 AEAD).
* **Timestamp (8 bytes)**: Epoch millisecond timestamp of packet creation.
* **Payload Length (2 bytes)**: Unsigned 16-bit integer defining the byte length of the trailing payload (or ciphertext payload).
* **Sender ID (8 bytes)**: 64-bit identifier of the transmitting node.
* **Recipient ID (8 bytes, optional)**: 64-bit identifier of the target node (present only if `FLAG_HAS_RECIPIENT` is set).

---

## 3. Cryptographic Envelope (`FLAG_ENCRYPTED = 0x04`)

When `FLAG_ENCRYPTED` is asserted in the packet header, the payload region consists of an RFC 8439 ChaCha20-Poly1305 AEAD ciphertext:

```text
Encrypted Payload Wire Layout:
+--------------------+-------------------------------------------+--------------------+
| Nonce (12 bytes)   | Ciphertext (N bytes)                      | Poly1305 Tag (16B) |
+--------------------+-------------------------------------------+--------------------+
```

### Authenticated Additional Data (AAD) Binding:
To prevent header tampering, type-confusion attacks, sender spoofing, and replay splicing:
1. The **entire wire header** (23 bytes without recipient, 31 bytes with recipient) is passed directly as the $AAD$ input to the Poly1305 MAC calculator.
2. The decryption routine recalculates the Poly1305 MAC over the received wire header and ciphertext.
3. If any header field (such as `timestamp`, `senderId`, `type`, or `flags`) was altered in transit, the MAC fails verification and the packet is immediately discarded without parsing.

---

## 4. Fragmentation Format (`FragmentHeader`)

When a serialized packet exceeds link MTU boundaries (typically > 460 bytes), it is partitioned into fragments:

```text
Fragment Envelope (13 bytes):
+--------------------+------------------+------------------+--------------------+-------------------+
| Fragment ID (8B)   | Index (2B BE)    | Total (2B BE)    | Original Type (1B) | Chunk Data        |
+--------------------+------------------+------------------+--------------------+-------------------+
```

* **Fragment ID (8 bytes)**: Randomly generated identifier grouping chunks belonging to the same packet.
* **Index (2 bytes)**: 0-based sequence index.
* **Total (2 bytes)**: Total number of fragments in this assembly.
* **Original Type (1 byte)**: Packet type of the original reassembled packet.
* **Chunk Data**: Sub-slice of the original packet's binary wire data.
* **Assembly Buffer Lifecycle**: Slices are buffered in an `AssemblyBuffer` with a 30-second sliding TTL. Expired or incomplete frames are purged automatically to prevent memory leaks.

---

## 5. Delta Batch Binary Encoding (`DELTA_BATCH = 0x05`)

Inside a `DELTA_BATCH` packet, mutations are serialized in compact binary:

```text
+--------------------+
| Change Count (2B)  |
+--------------------+
Followed by repeated entries:
+--------------------+--------------------+--------------------+--------------------+
| ID Length (2B)     | ID (UTF-8)         | Collection Len(2B) | Collection (UTF-8) |
+--------------------+--------------------+--------------------+--------------------+
| Mutation Type (1B) | HLC Length (2B)    | HLC String (UTF-8) | Payload Length(4B) |
+--------------------+--------------------+--------------------+--------------------+
| Payload Bytes      | [Author PK (32B)]* | [Signature (64B)]* |
+--------------------+--------------------+--------------------+
```

### Mutation Type Byte:
* Bits 0–6:
  * `0x00`: `INSERT`
  * `0x01`: `UPDATE`
  * `0x02`: `DELETE`
* Bit 7 (`0x80`): `FLAG_RECORD_SIGNED`
  * When `0`: Unsigned record (no trailing public key or signature).
  * When `1`: Signed record. Trailing 32 bytes contain the author's Ed25519 public key, followed by 64 bytes containing the Ed25519 digital signature.

### Canonical Signing Format:
The 64-byte Ed25519 signature is evaluated over the canonical byte concatenation:

```text
SignBytes = UTF8(collection) + UTF8(id) + MutationTypeByte(0..2) + UTF8(hlcTimestamp) + payloadBytes
```

---

## 6. Four-Phase Synchronization Sequence

```text
Node A (Central)                                       Node B (Peripheral)
       |                                                        |
       |  Phase 1: HANDSHAKE [VectorClock A]                    |
       |------------------------------------------------------->|
       |                                                        |
       |  Phase 2: HANDSHAKE_ACK [VectorClock B]                |
       |<-------------------------------------------------------|
       |                                                        |
       |  Phase 3a: RECONCILE_REQ [DeltaVector]                 |
       |------------------------------------------------------->|
       |                                                        |
       |  Phase 3b: DELTA_BATCH [DeltaRecords B -> A]           |
       |<-------------------------------------------------------|
       |                                                        |
       |  Phase 3c: DELTA_BATCH [DeltaRecords A -> B]           |
       |------------------------------------------------------->|
       |                                                        |
       |  Phase 4: DELTA_ACK                                    |
       |<-------------------------------------------------------|
       |                                                        |
```
