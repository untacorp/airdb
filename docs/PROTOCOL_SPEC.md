# AirDB Wire Protocol Specification (v1)

This specification defines the on-wire binary packet formats, framing, fragmentation, and handshake sequence for AirDB over Bluetooth Low Energy (BLE).

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
* **Flags (1 byte)**:
  * Bit 0 (`0x01`): `FLAG_COMPRESSED` (Payload is zlib compressed).
  * Bit 1 (`0x02`): `FLAG_HAS_RECIPIENT` (Recipient ID is present).
* **Timestamp (8 bytes)**: Epoch millisecond timestamp of packet creation.
* **Payload Length (2 bytes)**: Unsigned 16-bit integer defining the byte length of the trailing payload.
* **Sender ID (8 bytes)**: 64-bit identifier of the transmitting node.
* **Recipient ID (8 bytes, optional)**: 64-bit identifier of the target node (if targeted unicast).

---

## 3. Fragmentation Format (`FragmentHeader`)

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

---

## 4. Delta Batch Binary Encoding

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
| Payload Bytes      |
+--------------------+
```
Mutation Type values: `0 = INSERT`, `1 = UPDATE`, `2 = DELETE`.
