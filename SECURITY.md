# Security Policy

## Supported Versions
Security updates are actively applied to the following versions of AirDB:

| Version | Supported |
| ------- | --------- |
| 0.1.x   | Yes       |

---

## Threat Model & Security Design

AirDB operates over peer-to-peer wireless links (Bluetooth Low Energy) designed primarily for ad-hoc, off-grid environments.

### 1. Packet Framing Integrity
- The binary wire codec strictly validates magic framing bytes (`0x41 0x44`), version bytes, and payload bounds.
- Payloads exceeding maximum buffer sizes or malformed zlib streams are rejected immediately without memory allocation or crashes.

### 2. End-to-End Encryption (E2EE) Seams
- AirDB is transport-agnostic at the application payload layer.
- If payload confidentiality across intermediate untrusted relays is required, applications should encrypt `ChangeRecord.payload` prior to passing it to `saveRecord` (e.g. using AES-256-GCM or the Noise Protocol Framework).
- AirDB's wire protocol preserves arbitrary binary ciphertext without inspecting entity internals.

### 3. Reporting a Vulnerability
If you discover a security vulnerability within AirDB, please do not open a public GitHub issue. Send a responsible disclosure report to the maintainers at:
`security@airdb.dev` (or via private GitHub Advisory).
