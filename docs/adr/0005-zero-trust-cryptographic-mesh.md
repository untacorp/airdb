# ADR 0005: Zero-Trust Cryptographic Mesh & Provenance

## Status
Accepted

## Context
AirDB operates in ad-hoc, off-grid environments where nodes communicate via open Bluetooth Low Energy (BLE) radio channels and multi-hop gossip topologies. This exposes data to three primary vulnerabilities:
1. **Passive Eavesdropping**: Third parties scanning 2.4 GHz RF channels can read plain-text survivor registries, medical notes, or commercial transactions.
2. **Unauthorized Injection**: Rogue devices within radio range can forge handshakes or inject corrupt database records.
3. **Malicious Intermediate Relays**: In a decentralized gossip mesh, intermediate devices forward packets. A compromised relay could alter record contents, swap IDs, or backdate HLC timestamps without detection.

## Decision
We implement a two-layer **Zero-Trust Cryptographic Mesh**:
1. **Cluster-Level Symmetric AEAD (RFC 8439 ChaCha20-Poly1305)**:
   - All packets between cluster members are encrypted with a 256-bit symmetric `clusterKey`.
   - Every packet generates a fresh 12-byte CSPRNG nonce.
   - The entire 23-byte (or 31-byte) wire header is passed as Authenticated Additional Data ($AAD$), binding version, packet type, flags, timestamp, and node IDs into the Poly1305 MAC tag.
   - Any packet with invalid MAC tag is discarded before parsing.
2. **Record-Level Asymmetric Signatures (RFC 8032 Ed25519)**:
   - Each mutation is signed by its originating author using a private Ed25519 key.
   - The author's 32-byte public key and 64-byte signature are attached to the `ChangeRecord`.
   - The signature covers the canonical tuple: `collection`, `id`, `mutationType`, `hlcTimestamp`, and `payload`.
   - When `enforceSignatures` is active, receiving nodes verify the signature against the author public key. Compromised relays cannot tamper with records or timestamps without invalidating the cryptographic signature.
3. **Key Derivation (RFC 5869 HKDF-SHA256)**:
   - Symmetric cluster keys and session materials are derived using HKDF-SHA256 with distinct domain separation tags.
4. **Platform Native Implementations**:
   - JVM & Android: Standard Java 17 JCA (`ChaCha20-Poly1305` and `Ed25519`).
   - Apple Platforms: Pure native Apple `CryptoKit` (`ChaChaPoly` and `Curve25519.Signing`).

## Consequences
- **Positive**:
  - Full confidentiality against over-the-air RF sniffing.
  - Authentic framing prevents spoofed packet injections.
  - End-to-end cryptographic provenance protects against compromised or untrusted intermediate mesh relays.
  - Zero external third-party dependencies on both JVM and Apple platforms.
- **Negative**:
  - Over-the-air packet overhead: 12-byte nonce + 16-byte Poly1305 tag per encrypted frame.
  - Record overhead: 32-byte public key + 64-byte signature per signed mutation.
  - Increased CPU consumption for cryptographic signature verification during large initial sync batches.
