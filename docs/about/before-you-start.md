# Before You Start

Before diving into code and tutorials, this page gives you a quick overview of what AirDB expects, the real-world characteristics of Bluetooth Low Energy, and the mental models that will help you design reliable off-grid applications.

---

## Who This Documentation Is For

AirDB is designed for:
- **Android Developers** building apps in Kotlin with Jetpack Room, SQLite, or SQLDelight.
- **Apple Developers** building apps for iOS, iPadOS, macOS, or watchOS using Swift, SwiftData, CoreData, or GRDB.
- **Distributed Systems Engineers** looking for an off-grid replication protocol that guarantees convergence without relying on cloud coordination.

You do not need a background in advanced cryptography or distributed math to use AirDB. The high-level APIs handle packet serialization, encryption, and peer discovery out of the box.

---

## Important Mental Models

### 1. Bluetooth Low Energy Is Not Wi-Fi
BLE is designed for power efficiency, not raw file downloading:
- **Bandwidth**: Effective throughput over BLE typically ranges from **20 KB/s to 80 KB/s**, depending on physical distance, radio interference, and device chipset capabilities.
- **Packet Size (MTU)**: Raw BLE packets are small (typically between 23 and 512 bytes). AirDB automatically handles payload compression and splits larger packets into smaller chunks for you.
- **What to sync**: AirDB is designed for **structured data** (such as survivor triage records, inventory changes, sensor logs, and JSON or Protobuf payloads). For large multi-megabyte photo or video attachments, the recommended pattern is to replicate the file metadata via AirDB, and trigger a direct high-speed transfer (such as Wi-Fi Aware or Wi-Fi Direct) only when needed.

### 2. Eventual Consistency & Last-Write-Wins (LWW)
In an off-grid mesh, devices are separated by distance and time. Device A and Device B might both update the status of the same survivor while disconnected from each other:
- AirDB tracks edits using **Hybrid Logical Clocks (HLC)**. Each edit receives an unambiguous timestamp that combines physical time, a counter, and a unique node identifier.
- When Device A and Device B meet later, the update with the higher timestamp cleanly wins.
- Deleted records are marked with **tombstones** so that older updates from stale devices cannot accidentally bring deleted rows back to life.

### 3. Your Database Stays Yours (BYOD)
AirDB does not force you to rewrite your entire database layer. You can use our ready-to-go SQLite adapter, or write a thin wrapper around your existing Room, CoreData, or GRDB schema. AirDB only needs to know how to read new changes and apply incoming updates.

---

## Platform Support & Requirements

| Platform | Minimum Version | Language & Toolchain | Dependencies |
|---|---|---|---|
| **Android** | Android 8.0 (API 26+) | Kotlin 2.0+ / JDK 17+ | Core Android SDK |
| **Apple (iOS / iPadOS)** | iOS 14.0+ | Swift 5.9+ / Xcode 15+ | Zero external SPM dependencies |
| **Apple (macOS)** | macOS 12.0+ | Swift 5.9+ / Xcode 15+ | Zero external SPM dependencies |
| **Apple (watchOS)** | watchOS 7.0+ | Swift 5.9+ / Xcode 15+ | Zero external SPM dependencies |
| **JVM (Server / Desktop)** | Java 17+ | Kotlin 2.0+ | Standard JCA & SQLite3 |

---

## Required Permissions

### Android
Your app will need standard Bluetooth permissions in `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

### Apple (iOS / macOS)
In your app's `Info.plist`, include a description explaining why your app uses Bluetooth:
```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>AirDB uses Bluetooth to discover nearby devices and synchronize offline database records.</string>
```

---

## Where to Get Help

If you run into trouble while setting up AirDB or building your app:
- **Frequently Asked Questions**: Check out our [FAQ page](faq.md) for common operational questions.
- **GitHub Discussions & Issues**: Report unexpected behavior or ask questions on our GitHub repository.
- **Architecture Decisions**: If you are curious why a specific protocol design or cryptographic choice was made, read the [Architecture Decision Records (ADRs)](../community/adr/0001-sans-io-architecture.md).
