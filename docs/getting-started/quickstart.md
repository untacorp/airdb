# 5-Minute Quickstart

This quickstart shows you how to initialize a persistent SQLite database, start the AirDB synchronization engine, record a mutation, and listen for incoming updates from nearby peers.

---

## Step 1: Initialize Database & Cryptographic Keys

AirDB encrypts all transmissions with a cluster key and signs each record with an author key pair:

=== "Kotlin (Android / JVM)"

    ```kotlin
    import io.airdb.adapter.sqlite.SqliteDbAdapter
    import io.airdb.core.engine.AirDbEngine
    import io.airdb.crypto.Ed25519Signer
    import io.airdb.crypto.HkdfSha256
    import io.airdb.transport.ble.AndroidBleTransport
    import java.io.File

    // 1. Open a durable SQLite database file
    val dbFile = File(context.filesDir, "airdb_triage.sqlite")
    val adapter = SqliteDbAdapter.open(dbFile)

    // 2. Derive a 256-bit cluster AEAD key from a pre-shared cluster passphrase
    val clusterKey = HkdfSha256.deriveKey(
        ikm = "merapi-emergency-cluster-2026".toByteArray(Charsets.UTF_8),
        salt = "airdb-salt-v1".toByteArray(Charsets.UTF_8),
        info = "cluster-aead-key".toByteArray(Charsets.UTF_8)
    )

    // 3. Generate or load an Ed25519 signing key pair for this device
    val authorKeyPair = Ed25519Signer.generateKeyPair()
    ```

=== "Swift (Apple Platforms)"

    ```swift
    import AirDbKit
    import Foundation

    // 1. Open a durable SQLite database file in the Documents directory
    let docsUrl = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    let dbPath = docsUrl.appendingPathComponent("airdb_triage.sqlite").path
    let adapter = try SqliteDbAdapter(databasePath: dbPath)

    // 2. Derive a 256-bit cluster AEAD key from a pre-shared cluster passphrase
    let clusterKey = try HkdfSha256.deriveKey(
        secret: "merapi-emergency-cluster-2026".data(using: .utf8)!,
        salt: "airdb-salt-v1".data(using: .utf8)!,
        info: "cluster-aead-key".data(using: .utf8)!
    )

    // 3. Generate or load an Ed25519 signing key pair for this device
    let authorKeyPair = try Ed25519Signer.generateKeyPair()
    ```

---

## Step 2: Initialize & Start AirDbEngine

Connect the persistence adapter and the radio transport to create the central coordinating engine:

=== "Kotlin (Android / JVM)"

    ```kotlin
    // 4. Create the BLE transport and start the engine
    val transport = AndroidBleTransport(context)
    val engine = AirDbEngine(
        nodeName = "posko-merapi-1",
        adapter = adapter,
        transport = transport,
        clusterKey = clusterKey,
        authorKeyPair = authorKeyPair,
        enforceSignatures = true
    )

    // 5. Register a listener to receive notifications when changes arrive from peers
    engine.addSyncListener { peerId, changeCount ->
        println("Synchronized $changeCount verified changes from peer: $peerId")
    }

    // 6. Start Bluetooth advertising and scanning
    engine.start()
    ```

=== "Swift (Apple Platforms)"

    ```swift
    // 4. Create the CoreBluetooth transport and initialize the actor engine
    let transport = CoreBluetoothTransport()
    let engine = AirDbEngine(
        nodeName: "posko-merapi-ios-1",
        adapter: adapter,
        transport = transport,
        clusterKey: clusterKey,
        authorKeyPair: authorKeyPair,
        enforceSignatures: true
    )

    // 5. Register a listener for synchronization progress
    await engine.addSyncListener { peerId, changeCount in
        print("Synchronized \(changeCount) verified changes from peer: \(peerId)")
    }

    // 6. Start Bluetooth radio operations
    await engine.start()
    ```

---

## Step 3: Record a Local Mutation

When you write data locally, use `engine.saveRecord`. The engine assigns it an authoritative Hybrid Logical Clock (HLC) timestamp, signs it with your device's private key, writes it to the local SQLite database, and streams the change immediately to any connected peers:

=== "Kotlin (Android / JVM)"

    ```kotlin
    import io.airdb.adapter.MutationType
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.runBlocking

    val survivorJson = """{"name": "Budi Santoso", "status": "Treated", "triage": "GREEN"}"""

    // Save record to the "survivors" collection
    val changeRecord = engine.saveRecord(
        collection = "survivors",
        id = "survivor-101",
        type = MutationType.INSERT,
        payload = survivorJson.toByteArray(Charsets.UTF_8)
    )

    println("Saved record with HLC timestamp: ${changeRecord.hlcTimestamp}")
    ```

=== "Swift (Apple Platforms)"

    ```swift
    let survivorJson = """{"name": "Budi Santoso", "status": "Treated", "triage": "GREEN"}"""
    let payload = survivorJson.data(using: .utf8)!

    // Save record to the "survivors" collection
    let changeRecord = try await engine.saveRecord(
        collection: "survivors",
        id: "survivor-101",
        type: .insert,
        payload: payload
    )

    print("Saved record with HLC timestamp: \(changeRecord.hlcTimestamp)")
    ```

---

## What Happens Next?

1. When another device running AirDB with the same cluster key comes within Bluetooth range, both phones detect each other.
2. They perform a 4-phase synchronization handshake.
3. They compare what they have seen using vector clocks, and stream only the missing records.
4. The listener callback fires with the number of verified records applied!

Continue to the [Hands-On Tutorial](first-app-tutorial.md) for a deeper look at building an offline disaster triage app with multi-node replication.
