# API Contracts Reference

This reference summarizes the core public interfaces, classes, and types across both Kotlin and Swift implementations.

---

## 1. `AirDbEngine`

The central coordinator managing state synchronization, clock ratcheting, and peer communication.

=== "Kotlin"

    ```kotlin
    package io.airdb.core.engine

    class AirDbEngine(
        val nodeName: String,
        val adapter: AirDbAdapter,
        val transport: AirDbTransport,
        val clusterKey: ByteArray? = null,
        val authorKeyPair: Ed25519KeyPair? = null,
        val enforceSignatures: Boolean = false
    ) : TransportListener {

        fun start()
        fun stop()
        
        suspend fun saveRecord(
            collection: String,
            id: String,
            type: MutationType,
            payload: ByteArray
        ): ChangeRecord

        fun addSyncListener(listener: (peerId: String, changeCount: Int) -> Unit)
    }
    ```

=== "Swift"

    ```swift
    import AirDbKit

    public actor AirDbEngine: TransportListener {
        public init(
            nodeName: String,
            adapter: any AirDbAdapter,
            transport: any AirDbTransport,
            clusterKey: Data? = nil,
            authorKeyPair: Ed25519KeyPair? = nil,
            enforceSignatures: Boolean = false
        )

        public func start() async
        public func stop() async

        public func saveRecord(
            collection: String,
            id: String,
            type: MutationType,
            payload: Data
        ) async throws -> ChangeRecord

        public func addSyncListener(_ listener: @escaping @Sendable (String, Int) -> Void)
    }
    ```

---

## 2. `AirDbAdapter` & `ChangeRecord`

The persistence seam decoupling database engines from synchronization logic.

=== "Kotlin"

    ```kotlin
    package io.airdb.adapter

    interface AirDbAdapter {
        suspend fun getChangesSince(sinceHlcTimestamp: String?, limit: Int = 100): List<ChangeRecord>
        suspend fun applyChanges(changes: List<ChangeRecord>)
        suspend fun getLatestHlc(): String?
        suspend fun getMissingChanges(peerClock: Map<String, String>, limit: Int = 500): List<ChangeRecord>
    }

    data class ChangeRecord(
        val id: String,
        val collection: String,
        val type: MutationType,
        val payload: ByteArray,
        val hlcTimestamp: String,
        val authorPublicKey: ByteArray? = null,
        val signature: ByteArray? = null
    )
    ```

=== "Swift"

    ```swift
    import AirDbKit

    public protocol AirDbAdapter: Sendable {
        func getChangesSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord]
        func applyChanges(_ changes: [ChangeRecord]) async throws
        func getLatestHlc() async throws -> String?
        func getMissingChanges(peerClock: [String: String], limit: Int) async throws -> [ChangeRecord]
    }

    public struct ChangeRecord: Sendable, Equatable {
        public let id: String
        public let collection: String
        public let type: MutationType
        public let payload: Data
        public let hlcTimestamp: String
        public let authorPublicKey: Data?
        public let signature: Data?
    }
    ```

---

## 3. `AirDbTransport` & `TransportEvent`

The radio abstraction decoupling Bluetooth drivers and simulators from the engine.

=== "Kotlin"

    ```kotlin
    package io.airdb.transport

    interface AirDbTransport {
        fun start()
        fun stop()
        fun send(packet: AirDbPacket, targetPeerId: String? = null)
        fun setListener(listener: TransportListener)
    }

    sealed class TransportEvent {
        data class LinkConnected(val peerId: String, val linkId: String) : TransportEvent()
        data class LinkDisconnected(val peerId: String, val linkId: String) : TransportEvent()
        data class PacketReceived(val packet: AirDbPacket, val peerId: String) : TransportEvent()
    }
    ```

=== "Swift"

    ```swift
    import AirDbKit

    public protocol AirDbTransport: Sendable {
        func start() async
        func stop() async
        func send(packet: AirDbPacket, targetPeerId: String?) async throws
        func setListener(_ listener: (any TransportListener)?) async
    }

    public enum TransportEvent: Sendable {
        case linkConnected(peerId: String, linkId: String)
        case linkDisconnected(peerId: String, linkId: String)
        case packetReceived(packet: AirDbPacket, peerId: String)
    }
    ```
