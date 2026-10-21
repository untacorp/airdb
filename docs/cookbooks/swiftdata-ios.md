# Cookbook: Apple SwiftData Integration (iOS 17+ & macOS 14+)

This guide shows how to integrate AirDB with **SwiftData**, Apple's modern persistence framework using `@Model` and `ModelContainer`.

---

## 1. Defining the `@Model` Changelog Entity

```swift
import Foundation
import SwiftData

@Model
public final class AirDbChangeEntity {
    @Attribute(.unique)
    public var compositeKey: String // collection + ":" + id
    
    public var collection: String
    public var recordId: String
    public var mutationType: Int
    public var payload: Data
    
    #Index([\.hlcTimestamp])
    public var hlcTimestamp: String
    
    public var authorPublicKey: Data?
    public var signature: Data?
    public var createdAt: Date

    public init(
        collection: String,
        recordId: String,
        mutationType: Int,
        payload: Data,
        hlcTimestamp: String,
        authorPublicKey: Data? = nil,
        signature: Data? = nil,
        createdAt: Date = Date()
    ) {
        self.compositeKey = "\(collection):\(recordId)"
        self.collection = collection
        self.recordId = recordId
        self.mutationType = mutationType
        self.payload = payload
        self.hlcTimestamp = hlcTimestamp
        self.authorPublicKey = authorPublicKey
        self.signature = signature
        self.createdAt = createdAt
    }
}
```

---

## 2. Implementing `AirDbAdapter` with a Background `ModelContext`

Because SwiftData's `ModelContext` is bound to specific actors/threads, use `ModelContainer.newContext()` or a dedicated background actor:

```swift
import AirDbKit
import Foundation
import SwiftData

@ModelActor
public actor SwiftDataAirDbAdapter: AirDbAdapter {

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int) throws -> [ChangeRecord] {
        var descriptor: FetchDescriptor<AirDbChangeEntity>
        
        if let since = sinceHlcTimestamp {
            descriptor = FetchDescriptor<AirDbChangeEntity>(
                predicate: #Predicate { $0.hlcTimestamp > since },
                sortBy: [SortDescriptor(\.hlcTimestamp, order: .forward)]
            )
        } else {
            descriptor = FetchDescriptor<AirDbChangeEntity>(
                sortBy: [SortDescriptor(\.hlcTimestamp, order: .forward)]
            )
        }
        
        descriptor.fetchLimit = limit
        let entities = try modelContext.fetch(descriptor)
        
        return entities.compactMap { entity in
            guard let type = MutationType(rawValue: entity.mutationType) else { return nil }
            return ChangeRecord(
                id: entity.recordId,
                collection: entity.collection,
                type: type,
                payload: entity.payload,
                hlcTimestamp: entity.hlcTimestamp,
                authorPublicKey: entity.authorPublicKey,
                signature: entity.signature
            )
        }
    }

    public func applyChanges(_ changes: [ChangeRecord]) throws {
        for change in changes {
            let targetKey = "\(change.collection):\(change.id)"
            var descriptor = FetchDescriptor<AirDbChangeEntity>(
                predicate: #Predicate { $0.compositeKey == targetKey }
            )
            descriptor.fetchLimit = 1
            
            let existing = try modelContext.fetch(descriptor).first
            
            // Last-Write-Wins (LWW) check
            if existing == nil || change.hlcTimestamp > existing!.hlcTimestamp {
                if let existing = existing {
                    existing.mutationType = change.type.rawValue
                    existing.payload = change.payload
                    existing.hlcTimestamp = change.hlcTimestamp
                    existing.authorPublicKey = change.authorPublicKey
                    existing.signature = change.signature
                    existing.createdAt = Date()
                } else {
                    let newEntity = AirDbChangeEntity(
                        collection: change.collection,
                        recordId: change.id,
                        mutationType: change.type.rawValue,
                        payload: change.payload,
                        hlcTimestamp: change.hlcTimestamp,
                        authorPublicKey: change.authorPublicKey,
                        signature: change.signature
                    )
                    modelContext.insert(newEntity)
                }
            }
        }
        
        try modelContext.save()
    }

    public func getLatestHlc() throws -> String? {
        var descriptor = FetchDescriptor<AirDbChangeEntity>(
            sortBy: [SortDescriptor(\.hlcTimestamp, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first?.hlcTimestamp
    }
}
```

---

## 3. Initialization in SwiftUI / iOS Application

```swift
import AirDbKit
import SwiftData
import SwiftUI

@main
struct PoskoApp: App {
    let container: ModelContainer
    let adapter: SwiftDataAirDbAdapter
    let engine: AirDbEngine

    init() {
        do {
            let schema = Schema([AirDbChangeEntity.self, SurvivorModel.self])
            container = try ModelContainer(for: schema)
            adapter = SwiftDataAirDbAdapter(modelContainer: container)
            
            let transport = CoreBluetoothTransport()
            engine = AirDbEngine(
                nodeName: "ios-responder-device",
                adapter: adapter,
                transport = transport
            )
            engine.start()
        } catch {
            fatalError("Failed to initialize database container: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(container)
    }
}
```
