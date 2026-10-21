# Introduction to AirDB

Welcome to the AirDB documentation!

**AirDB** is an open-source, local-first database synchronization engine designed to work completely off-grid. It allows mobile phones and embedded devices to sync database records directly with each other over **Bluetooth Low Energy (BLE)** without needing an internet connection, cellular coverage, Wi-Fi routers, or central cloud servers.

---

## The Problem AirDB Solves

Most modern software assumes an always-on internet connection. Applications save data to centralized cloud databases like Firebase, DynamoDB, or PostgreSQL. When you lose internet access, things break:

- **Disaster zones and emergency camps**: Cell towers and power grids are frequently destroyed during earthquakes, floods, or hurricanes. First responders need to register survivors, track medical triage status, and manage emergency supplies immediately across dozens of handheld devices.
- **Off-grid commerce**: Pop-up shops, night markets, and outdoor festival booths need multiple cashier tablets to share order queues, inventory counts, and payment tallies in crowded locations where mobile data networks are congested or unavailable.
- **Field operations and conservation**: Wildlife rangers and field inspectors in national parks need to collect observation logs, GPS coordinates, and inspection checklists that automatically sync across patrol teams when they meet along the trail.

AirDB was built to solve these exact situations. When devices running AirDB come within Bluetooth range (around 10 to 30 meters), they automatically discover each other, negotiate what data is missing, and sync updates in seconds.

---

## How AirDB Compares to Other Solutions

| Feature | Centralized Cloud DBs (Firebase, Supabase) | Ephemeral Mesh Chat (BitChat, Bridgefy) | AirDB |
|---|---|---|---|
| **Requires Internet** | Yes | No | **No (100% off-grid)** |
| **Data Model** | Structured tables or JSON documents | Ephemeral chat messages | **Structured database tables / collections** |
| **Persistence** | Remote server | Device memory or chat log | **Durable local SQLite with WAL mode** |
| **Conflict Resolution** | Server-side timestamps / locks | None (append-only timeline) | **Automatic Last-Write-Wins (LWW) via HLC** |
| **Security Model** | Cloud API tokens / OAuth | End-to-end user encryption | **Zero-Trust mesh (cluster AEAD + author signatures)** |
| **Relay Support** | Central server relay | Opportunistic gossip | **Multi-hop store-and-forward gossip** |

---

## Core Design Principles

AirDB is built on three straightforward principles:

### 1. Local-First Means Local Writes Never Block
Your application writes directly to its local database first. You never have to wait for a network response, spin a loading wheel, or worry about timeouts. The data is saved immediately to local disk using durable SQLite with Write-Ahead Logging (WAL). AirDB synchronizes those changes in the background whenever nearby peers appear.

### 2. Clean Separation (Sans-I/O)
The engine separates business logic from hardware radios. The algorithms that calculate timestamps, resolve conflicts, and format binary packets do not contain any socket calls, Android Bluetooth delegates, or Apple CoreBluetooth objects. This makes the system extremely fast, stable, and testable: you can simulate a 10-node mesh network completely in memory in milliseconds without touching physical hardware.

### 3. Zero-Trust Security Across Untrusted Relays
In a disaster area or public market, data often hops through intermediate phones before reaching its destination. AirDB protects your data on two levels:
- **Over-the-air encryption**: Transmissions are scrambled using ChaCha20-Poly1305 AEAD so eavesdroppers cannot read or inject packets.
- **Author signatures**: Every mutation is digitally signed by the original author with an Ed25519 private key. Even if an intermediate relay phone is running modified software, it cannot alter or forge your records without invalidating the signature.

---

## Next Steps

- Check out [Before You Start](before-you-start.md) for prerequisites and Bluetooth physical realities.
- Browse the complete [List of Features](features.md).
- Jump straight to code with the [5-Minute Quickstart](../getting-started/quickstart.md).
