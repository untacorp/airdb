# Security Policy & Cryptographic Threat Model

AirDB is designed from the ground up for hostile, disconnected, and untrusted peer-to-peer environments. This document outlines the cryptographic threat model, security guarantees, and vulnerability reporting procedures.

---

## 1. Supported Versions

Security updates are actively applied to the following versions of AirDB:

| Version | Supported |
| ------- | --------- |
| 0.2.x   | Yes       |
| 0.1.x   | Yes       |

---

## 2. Threat Model & Security Guarantees

In an off-grid BLE mesh network, communication occurs across an open RF broadcast channel and propagates through intermediate mobile devices acting as multi-hop gossip relays.

### Threat Vectors & Mitigations

| Threat | Attack Vector | AirDB Defense Mechanism |
|---|---|---|
| **Eavesdropping** | Passive RF sniffing of over-the-air BLE packets. | **ChaCha20-Poly1305 AEAD** (256-bit symmetric cluster key). All payload data is ciphertext in flight. |
| **Packet Injection** | Rogue device broadcasts unauthorized frames into the cluster. | **Poly1305 16-byte MAC verification**. Packets from unauthorized senders fail MAC checks and are dropped before parsing. |
| **Header Tampering** | Attacker modifies flags, timestamps, or recipient IDs in transit. | **Authenticated Additional Data (AAD)**. The entire 23B/31B header is cryptographically bound into the Poly1305 MAC. |
| **Malicious Mesh Relay** | Compromised intermediate peer alters record content or HLC timestamps. | **Ed25519 Asymmetric Signatures**. Every mutation is signed by the originating author's private key. Intermediate relays cannot alter records without breaking the author's signature. |
| **Replay Attacks** | Attacker re-transmits valid past frames to roll back database state. | **Monotonic HLC & Vector Clock Filter**. Replayed records with older or identical HLC timestamps are rejected by the LWW state machine. |
| **Buffer Overflow / DoS** | Malformed packet frames designed to crash JVM or Swift runtime. | **Defensive Wire Decoding**. Strict bounds checking on magic bytes (`0x41 0x44`), payload lengths, Deflate decompressed buffer ceilings, and fragment reassembly timeouts (30s TTL). |

---

## 3. Cryptographic Implementation Details

### RFC 8439 ChaCha20-Poly1305 AEAD
- **Android & Kotlin/JVM**: Standard Java 17 Cryptographic Architecture (`Cipher.getInstance("ChaCha20-Poly1305")`).
- **Apple Platforms**: Pure native Apple `CryptoKit.ChaChaPoly`.
- **Nonce Strategy**: 12-byte cryptographically secure pseudo-random number generator (CSPRNG) generated per encrypted packet.
- **Tag Verification**: 16-byte Poly1305 evaluation over AAD and ciphertext.

### RFC 8032 Ed25519 Digital Signatures
- **Android & Kotlin/JVM**: Java 17 `Ed25519` via standard JCA `KeyFactory` and ASN.1 encoding bridges for raw 32-byte public keys and 32-byte private seed keys.
- **Apple Platforms**: Pure native Apple `CryptoKit.Curve25519.Signing`.
- **Key Sizes**: 32-byte public key, 64-byte signature.

### RFC 5869 HKDF-SHA256 Key Derivation
- Uses HMAC-SHA256 extract-and-expand pipeline to derive 256-bit symmetric encryption keys from human-readable passwords, pre-shared cluster passphrases, or initial key material (IKM).

---

## 4. Reporting a Vulnerability

If you discover a security vulnerability within AirDB, please do not open a public GitHub issue. Send a responsible disclosure report to the maintainers at:

`security@airdb.dev` (or via private GitHub Security Advisory).

Please include:
1. Module and platform affected (JVM, Android, or Apple AirDbKit).
2. Steps or proof-of-concept code to reproduce the issue.
3. Potential impact of the vulnerability.
