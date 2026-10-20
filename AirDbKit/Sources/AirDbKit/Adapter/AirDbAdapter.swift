/*
 * Copyright 2026 AirDB Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Foundation

/// Seam interface abstracting local persistence for Apple platforms.
public protocol AirDbAdapter: Sendable {
    /// Retrieves local mutations strictly newer than `sinceHlcTimestamp`, up to `limit` records.
    func getChangesSince(sinceHlcTimestamp: String?, limit: Int) async throws -> [ChangeRecord]

    /// Idempotently applies remote mutations into local storage using Last-Write-Wins (LWW).
    func applyChanges(_ changes: [ChangeRecord]) async throws

    /// Returns the highest local HLC timestamp, or nil if empty.
    func getLatestHlc() async throws -> String?
}

/// Thread-safe in-memory adapter reference implementation for testing and rapid prototyping.
public actor MemoryDbAdapter: AirDbAdapter {
    // collection -> (id -> ChangeRecord)
    private var tables: [String: [String: ChangeRecord]] = [:]
    private var changelog: [ChangeRecord] = []

    public init() {}

    public func getChangesSince(sinceHlcTimestamp: String?, limit: Int = 100) async throws -> [ChangeRecord] {
        if let since = sinceHlcTimestamp {
            return changelog.filter { $0.hlcTimestamp > since }.prefix(limit).map { $0 }
        }
        return Array(changelog.prefix(limit))
    }

    public func applyChanges(_ changes: [ChangeRecord]) async throws {
        for change in changes {
            var table = tables[change.collection] ?? [:]
            let existing = table[change.id]

            if existing == nil || change.hlcTimestamp > existing!.hlcTimestamp {
                table[change.id] = change
                tables[change.collection] = table
                changelog.append(change)
            }
        }
    }

    public func getLatestHlc() async throws -> String? {
        changelog.max(by: { $0.hlcTimestamp < $1.hlcTimestamp })?.hlcTimestamp
    }

    public func getRecord(collection: String, id: String) -> ChangeRecord? {
        tables[collection]?[id]
    }

    public func getActiveRecords(collection: String) -> [String: ChangeRecord] {
        (tables[collection] ?? [:]).filter { $0.value.type != .delete }
    }

    public func isRecordDeleted(collection: String, id: String) -> Bool {
        tables[collection]?[id]?.type == .delete
    }
}
