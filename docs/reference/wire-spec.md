# Wire Protocol Specification Reference

This document serves as the formal specification for AirDB binary packet serialization over Bluetooth Low Energy.

---

## 1. Encoding Standard

- **Endianness**: All multi-byte numeric values (`Short`, `Int`, `Long`) are encoded in **Network Byte Order (Big-Endian)**.
- **String Encodings**: All string identifiers (node IDs, collection names, entity keys, HLC timestamps) are encoded as raw **UTF-8** bytes.

---

## 2. Fixed Packet Header (`AirDbPacket`)

Every AirDB packet transmitted over the wire begins with a fixed header (23 bytes without recipient, or 31 bytes if a recipient ID is present):

```text
Fixed Header Layout:
+-------------------+------------+-----------+------------+
| Field Name        | Size       | Type      | Offset (B) |
+-------------------+------------+-----------+------------+
| Magic Bytes       | 2 bytes    | ASCII     | 0 - 1      | Fixed 0x41 0x44 ("AD")
| Protocol Version  | 1 byte     | UInt8     | 2          | Current: 0x01
| Packet Type Code  | 1 byte     | UInt8     | 3          | See Section 3
| Bitmask Flags     | 1 byte     | Bitmask   | 4          | See Section 4
| Creation Time     | 8 bytes    | Int64 BE  | 5 - 12     | Epoch milliseconds
| Payload Length    | 2 bytes    | UInt16 BE | 13 - 14    | Byte length of payload
| Sender Node ID    | 8 bytes    | Int64 BE  | 15 - 22    | 64-bit sender hash
| [Recipient ID]*   | 8 bytes    | Int64 BE  | 23 - 30    | Present if FLAG_HAS_RECIPIENT
+-------------------+------------+-----------+------------+
| Variable Trailing Payload (0 to 65,535 bytes)           |
+---------------------------------------------------------+
```

---

## 3. Packet Type Codes

| Code | Name | Description |
|---|---|---|
| `0x01` | `HANDSHAKE` | Initiates peer link session; payload contains sender node name UTF-8. |
| `0x02` | `HANDSHAKE_ACK` | Confirms link session; payload contains responder node name UTF-8. |
| `0x03` | `RECONCILE_REQ` | Transmits local Vector Clock to request missing changes. |
| `0x04` | `RECONCILE_RESP` | Transmits peer Vector Clock in response. |
| `0x05` | `DELTA_BATCH` | Carries serialized database mutations missing on the remote peer. |
| `0x06` | `DELTA_ACK` | Confirms that a delta batch was verified and applied to local storage. |
| `0x0F` | `FRAGMENT` | Encapsulates a chunk of an oversized packet exceeding link MTU. |

---

## 4. Bitmask Flags

| Bit Position | Value | Constant Name | Description |
|---|---|---|---|
| Bit 0 | `0x01` | `FLAG_COMPRESSED` | Payload is compressed with zlib Deflate. |
| Bit 1 | `0x02` | `FLAG_HAS_RECIPIENT` | Packet header contains 8-byte recipient ID (31-byte total header). |
| Bit 2 | `0x04` | `FLAG_ENCRYPTED` | Payload is encrypted with ChaCha20-Poly1305 AEAD. |

---

## 5. AEAD Cryptographic Envelope (`FLAG_ENCRYPTED = 0x04`)

When `FLAG_ENCRYPTED` is asserted, the trailing payload is formatted as:

```text
+--------------------+-------------------------------------------+--------------------+
| Nonce (12 bytes)   | Ciphertext (N bytes)                      | Poly1305 Tag (16B) |
+--------------------+-------------------------------------------+--------------------+
```

### Authenticated Additional Data (AAD)
The entire packet header (23 bytes or 31 bytes) is passed directly as $AAD$ to the cipher. Any tampering with header fields invalidates the Poly1305 tag and causes the packet to be discarded immediately.

---

## 6. Fragment Envelope (`FRAGMENT = 0x0F`)

When a packet exceeds MTU bounds, it is split into slices wrapped in a 13-byte fragment envelope:

```text
+--------------------+------------------+------------------+--------------------+------------+
| Fragment ID (8B)   | Index (2B BE)    | Total (2B BE)    | Original Type (1B) | Chunk Data |
+--------------------+------------------+------------------+--------------------+------------+
```

- **Fragment ID (8 bytes)**: Random `Long` identifying all chunks of a single packet.
- **Index (2 bytes)**: 0-based chunk index.
- **Total (2 bytes)**: Total chunks in this assembly.
- **Original Type (1 byte)**: Packet type code of the unfragmented message.
- **AssemblyBuffer TTL**: Receivers purge incomplete assemblies after 30 seconds.

---

## 7. Delta Batch Binary Encoding (`DELTA_BATCH = 0x05`)

```text
+--------------------+
| Change Count (2B)  |
+--------------------+
Followed by repeated change entries:
+--------------------+--------------------+--------------------+--------------------+
| ID Length (2B)     | ID (UTF-8)         | Collection Len(2B) | Collection (UTF-8) |
+--------------------+--------------------+--------------------+--------------------+
| Mutation Type (1B) | HLC Length (2B)    | HLC String (UTF-8) | Payload Length(4B) |
+--------------------+--------------------+--------------------+--------------------+
| Payload Bytes      | [Author PK (32B)]* | [Signature (64B)]* |
+--------------------+--------------------+--------------------+
```

### Mutation Type Byte:
- Bits 0–6:
  - `0x00`: `INSERT`
  - `0x01`: `UPDATE`
  - `0x02`: `DELETE`
- Bit 7 (`0x80`): `FLAG_RECORD_SIGNED` (asserted if author public key and signature follow payload bytes).
