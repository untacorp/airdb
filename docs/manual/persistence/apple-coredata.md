# Apple CoreData Integration (iOS & macOS)

This guide shows how to integrate AirDB with **CoreData** in iOS and macOS applications using `NSPersistentContainer` and background execution contexts.

---

## 1. Managed Object Model Setup

In your `.xcdatamodeld` file, add an entity named `AirDbChangeEntity`:

| Attribute | Type | Optional | Indexed |
|---|---|---|---|
| `compositeKey` | String | No | Yes |
| `collection` | String | No | No |
| `recordId` | String | No | No |
| `mutationType` | Integer 16 | No | No |
| `payload` | Binary Data | Yes | No |
| `hlcTimestamp` | String | No | Yes |
| `authorPublicKey` | Binary Data | Yes | No |
| `signature` | Binary Data | Yes | No |
| `createdAt` | Date | No | No |

---

## 2. Implementing `AirDbAdapter`

```swift
import AirDbKit
import CoreData
import Foundation

public final class CoreDataAirDbAdapter: AirDbAdapter, @unchecked Sendable {
    private let persistentContainer: NSPersistentContainer

    public init(persistentContainer: NSPersistentContainer) {
        self.persistentContainer = persistentContainer
    }

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord] {
        try await persistentContainer.performBackgroundTask { context in
            let request = NSFetchRequest<NSManagedObject>(entityName: "AirDbChangeEntity")
            
            if let since = sinceHlcTimestamp {
                request.predicate = NSPredicate(format: "hlcTimestamp > %@", since)
            }
            request.sortDescriptors = [NSSortDescriptor(key: "hlcTimestamp", ascending: true)]
            request.fetchLimit = limit
            
            let results = try context.fetch(request)
            return results.compactMap { obj -> ChangeRecord? in
                guard let id = obj.value(forKey: "recordId") as? String,
                      let collection = obj.value(forKey: "collection") as? String,
                      let rawType = obj.value(forKey: "mutationType") as? Int16,
                      let type = MutationType(rawValue: Int(rawType)),
                      let hlc = obj.value(forKey: "hlcTimestamp") as? String else {
                    return nil
                }
                
                let payload = obj.value(forKey: "payload") as? Data ?? Data()
                let authorPublicKey = obj.value(forKey: "authorPublicKey") as? Data
                let signature = obj.value(forKey: "signature") as? Data

                return ChangeRecord(
                    id: id,
                    collection: collection,
                    type: type,
                    payload: payload,
                    hlcTimestamp: hlc,
                    authorPublicKey: authorPublicKey,
                    signature: signature
                )
            }
        }
    }

    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        try await persistentContainer.performBackgroundTask { context in
            for change in changes {
                let key = "\(change.collection):\(change.id)"
                let request = NSFetchRequest<NSManagedObject>(entityName: "AirDbChangeEntity")
                request.predicate = NSPredicate(format: "compositeKey == %@", key)
                request.fetchLimit = 1

                let existing = try context.fetch(request).first
                if let existing = existing,
                   let existingHlc = existing.value(forKey: "hlcTimestamp") as? String,
                   HlcComparator.compare(change.hlcTimestamp, existingHlc) <= 0 {
                    continue
                }

                let target = existing ?? NSEntityDescription.insertNewObject(forEntityName: "AirDbChangeEntity", into: context)
                target.setValue(key, forKey: "compositeKey")
                target.setValue(change.collection, forKey: "collection")
                target.setValue(change.id, forKey: "recordId")
                target.setValue(Int16(change.type.rawValue), forKey: "mutationType")
                target.setValue(change.payload, forKey: "payload")
                target.setValue(change.hlcTimestamp, forKey: "hlcTimestamp")
                target.setValue(change.authorPublicKey, forKey: "authorPublicKey")
                target.setValue(change.signature, forKey: "signature")
                target.setValue(Date(), forKey: "createdAt")
            }

            if context.hasChanges {
                try context.save()
            }
        }
    }

    public func getLatestHlc() async throws -> String? {
        try await persistentContainer.performBackgroundTask { context in
            let request = NSFetchRequest<NSManagedObject>(entityName: "AirDbChangeEntity")
            request.sortDescriptors = [NSSortDescriptor(key: "hlcTimestamp", ascending: false)]
            request.fetchLimit = 1
            return try context.fetch(request).first?.value(forKey: "hlcTimestamp") as? String
        }
    }
}
```
