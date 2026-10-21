# Apple SwiftData Integration (iOS 17+ & macOS 14+)

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

## 2. Implementing `AirDbAdapter`

```swift
import AirDbKit
import Foundation
import SwiftData

public final class SwiftDataAirDbAdapter: AirDbAdapter, @unchecked Sendable {
    private let container: ModelContainer

    public init(container: ModelContainer) {
        self.container = container
    }

    @MainActor
    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord] {
        let context = container.mainContext
        
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

        let entities = try context.fetch(descriptor)
        return entities.compactMap { e in
            guard let type = MutationType(rawValue: e.mutationType) else { return nil }
            return ChangeRecord(
                id: e.recordId,
                collection: e.collection,
                type: type,
                payload: e.payload,
                hlcTimestamp: e.hlcTimestamp,
                authorPublicKey: e.authorPublicKey,
                signature: e.signature
            )
        }
    }

    @MainActor
    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        let context = container.mainContext
        
        for change in changes {
            let key = "\(change.collection):\(change.id)"
            var fetchDesc = FetchDescriptor<AirDbChangeEntity>(predicate: #Predicate { $0.compositeKey == key })
            fetchDesc.fetchLimit = 1
            
            let existing = try context.fetch(fetchDesc).first
            if let existing = existing, HlcComparator.compare(change.hlcTimestamp, existing.hlcTimestamp) <= 0 {
                continue
            }

            if let existing = existing {
                existing.mutationType = change.type.rawValue
                existing.payload = change.payload
                existing.hlcTimestamp = change.hlcTimestamp
                existing.authorPublicKey = change.authorPublicKey
                existing.signature = change.signature
            } else {
                let entity = AirDbChangeEntity(
                    collection: change.collection,
                    recordId: change.id,
                    mutationType: change.type.rawValue,
                    payload: change.payload,
                    hlcTimestamp: change.hlcTimestamp,
                    authorPublicKey: change.authorPublicKey,
                    signature: change.signature
                )
                context.insert(entity)
            }
        }
        
        try context.save()
    }

    @MainActor
    public func getLatestHlc() async throws -> String? {
        let context = container.mainContext
        var desc = FetchDescriptor<AirDbChangeEntity>(
            sortBy: [SortDescriptor(\.hlcTimestamp, order: .reverse)]
        )
        desc.fetchLimit = 1
        return try context.fetch(desc).first?.hlcTimestamp
    }
}
```
